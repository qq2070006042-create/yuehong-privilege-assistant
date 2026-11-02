package roro.stellar.manager.adb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.system.Os
import android.util.Base64
import android.util.Base64OutputStream
import roro.stellar.manager.adb.AdbProtocol.ADB_AUTH_RSAPUBLICKEY
import roro.stellar.manager.adb.AdbProtocol.ADB_AUTH_SIGNATURE
import roro.stellar.manager.adb.AdbProtocol.ADB_AUTH_TOKEN
import roro.stellar.manager.adb.AdbProtocol.A_AUTH
import roro.stellar.manager.adb.AdbProtocol.A_CLSE
import roro.stellar.manager.adb.AdbProtocol.A_CNXN
import roro.stellar.manager.adb.AdbProtocol.A_OKAY
import roro.stellar.manager.adb.AdbProtocol.A_OPEN
import roro.stellar.manager.adb.AdbProtocol.A_WRTE
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.BufferOverflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream

/** Direct ADB transport over Android USB host mode. */
class UsbAdbClient(
    usbManager: UsbManager,
    val device: UsbDevice,
    private val key: AdbKey,
    private val temporaryDirectory: File,
) : Closeable {
    data class CommandResult(val output: String, val exitCode: Int)

    /** The transport is alive, but adbd explicitly rejected a service open. */
    class ServiceRejectedException(
        val service: String,
    ) : IOException("目标设备拒绝打开 ADB 服务：$service")

    /** A USB request failed; the caller can recover it without recreating the ADB transport. */
    class TransportException(
        message: String,
        cause: Throwable? = null,
        val transferPartIndex: Int? = null,
    ) : IOException(message, cause)

    private val adbInterface: UsbInterface = findAdbInterface(device)
        ?: throw IOException("目标设备没有可用的 ADB USB 接口")
    private val inputEndpoint: UsbEndpoint = findEndpoint(adbInterface, UsbConstants.USB_DIR_IN)
        ?: throw IOException("目标设备缺少 ADB 输入端点")
    private val outputEndpoint: UsbEndpoint = findEndpoint(adbInterface, UsbConstants.USB_DIR_OUT)
        ?: throw IOException("目标设备缺少 ADB 输出端点")
    private val connection: UsbDeviceConnection = usbManager.openDevice(device)
        ?: throw IOException("USB 设备打开失败，请重新授予 USB 权限")

    private var connected = false
    private var maxPayload = USB_ADB_LEGACY_MAX_PAYLOAD_BYTES
    private var skipChecksum = false
    private val nextLocalId = AtomicInteger(1)
    private var outputRequest = UsbRequest()
    private var inputRequest = UsbRequest()
    private var outputBuffer = ByteBuffer.allocateDirect(USB_ADB_MAX_PAYLOAD_BYTES)
    private var inputBuffer = ByteBuffer.allocateDirect(USB_REQUEST_READ_CHUNK_BYTES)
    private var outputRequestQueued = false
    private var inputRequestQueued = false
    private var closed = false

    init {
        initializeUsbRequests()
    }

    private fun initializeUsbRequests() {
        if (!connection.claimInterface(adbInterface, true)) {
            throw IOException("ADB USB 接口占用失败")
        }
        outputRequest = UsbRequest()
        inputRequest = UsbRequest()
        val outputReady = outputRequest.initialize(connection, outputEndpoint)
        val inputReady = inputRequest.initialize(connection, inputEndpoint)
        if (!outputReady || !inputReady) {
            runCatching { outputRequest.close() }
            runCatching { inputRequest.close() }
            runCatching { connection.releaseInterface(adbInterface) }
            throw IOException("ADB USB 请求初始化失败")
        }
    }

    fun connect() {
        // Advertise the modern ADB transport only for USB host mode. The
        // wireless activation client keeps its existing protocol constants.
        // The first CNXN retains a checksum so pre-2017 adbd can still parse it.
        write(A_CNXN, USB_ADB_VERSION, USB_ADB_MAX_PAYLOAD_BYTES, "host::yuehong-wired")
        var message = read()
        if (message.command == A_AUTH) {
            if (message.arg0 != ADB_AUTH_TOKEN || message.data == null) {
                throw IOException("目标设备返回了无效的 ADB 授权请求")
            }
            write(A_AUTH, ADB_AUTH_SIGNATURE, 0, key.sign(message.data))
            message = read()
            if (message.command != A_CNXN) {
                write(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, key.adbPublicKey)
                message = try {
                    read(AUTH_TIMEOUT_MS)
                } catch (error: IOException) {
                    throw IOException("请在目标设备上确认“允许 USB 调试”", error)
                }
            }
        }
        if (message.command != A_CNXN) {
            throw IOException("目标设备未完成 ADB 连接")
        }
        val negotiatedVersion = minOf(message.arg0, USB_ADB_VERSION)
        skipChecksum = negotiatedVersion >= USB_ADB_SKIP_CHECKSUM_VERSION
        maxPayload = minOf(
            message.arg1.coerceAtLeast(MIN_PAYLOAD_BYTES),
            USB_ADB_MAX_PAYLOAD_BYTES,
        )
        connected = true
    }

    /** Describes the USB target and the negotiated ADB transport without opening another service. */
    fun connectionSummary(): String {
        val manufacturer = runCatching { device.manufacturerName }.getOrNull().orEmpty().trim()
        val product = runCatching { device.productName }.getOrNull().orEmpty().trim()
        val serial = runCatching { device.serialNumber }.getOrNull().orEmpty().trim()
        val identity = listOf(manufacturer, product).filter(String::isNotBlank).distinct().joinToString(" ")
        val usb = "%04X:%04X".format(device.vendorId, device.productId)
        val serialText = serial.takeIf(String::isNotBlank)?.let { " · 序列号=$it" }.orEmpty()
        return buildString {
            append(identity.ifBlank { "USB ADB 设备" })
            append(" · USB=$usb$serialText")
            append('\n')
            append(
                "ADB IN=0x%02X/%d · OUT=0x%02X/%d · MAXDATA=%d".format(
                    inputEndpoint.address,
                    inputEndpoint.maxPacketSize,
                    outputEndpoint.address,
                    outputEndpoint.maxPacketSize,
                    maxPayload,
                ),
            )
        }
    }

    /** Sends Android's native reboot service request to the connected target. */
    fun reboot(target: String = "") {
        ensureConnected()
        require(target.isEmpty() || REBOOT_TARGET_PATTERN.matches(target)) { "无效的重启目标" }
        val localId = nextLocalId.getAndIncrement()
        write(A_OPEN, localId, 0, "reboot:$target")
        val response = read(REBOOT_ACK_TIMEOUT_MS)
        when (response.command) {
            A_OKAY -> {
                // reboot: is a one-shot service.  Close the acknowledged stream
                // before dropping USB, otherwise adbd can retain a half-open
                // stream and the next transport may reject shell opens.
                runCatching { write(A_CLSE, localId, response.arg0) }
            }
            A_CLSE -> {
                runCatching { write(A_CLSE, localId, response.arg0) }
            }
            else -> {
                throw IOException("目标设备拒绝了重启请求")
            }
        }
    }

    fun shell(command: String): CommandResult {
        ensureConnected()
        val marker = "__YHROOT_EXIT_${System.nanoTime()}__="
        val wrapped = "($command) 2>&1; printf '\\n$marker%d\\n' \$?"
        val raw = readService("shell:$wrapped")
        val text = raw.toString(Charsets.UTF_8)
        val markerIndex = text.lastIndexOf(marker)
        if (markerIndex < 0) return CommandResult(text.trimEnd(), UNKNOWN_EXIT_CODE)
        val exitText = text.substring(markerIndex + marker.length).lineSequence().firstOrNull().orEmpty().trim()
        return CommandResult(
            output = text.substring(0, markerIndex).trimEnd(),
            exitCode = exitText.toIntOrNull() ?: UNKNOWN_EXIT_CODE,
        )
    }

    /** Executes a raw adbd service such as root:, remount:, tcpip:5555 or usb:. */
    fun service(name: String): String {
        ensureConnected()
        require(name.isNotBlank() && name.length <= MAX_SERVICE_NAME_CHARS && '\u0000' !in name) {
            "无效的 ADB 服务名"
        }
        return readService(name).toString(Charsets.UTF_8).trimEnd()
    }

    fun push(
        source: File,
        destination: String,
        mode: Int = DEFAULT_FILE_MODE,
        resumeRemoteParts: Boolean = false,
        completedPartIndices: MutableSet<Int>? = null,
        deferredPartIndices: MutableSet<Int>? = null,
        onLog: ((message: String) -> Unit)? = null,
        onProgress: ((transferredBytes: Long, totalBytes: Long) -> Unit)? = null,
    ) {
        require(source.isFile) { "待传输文件不存在" }
        FileInputStream(source).use { input ->
            push(
                input,
                destination,
                mode,
                source.length(),
                resumeRemoteParts,
                completedPartIndices,
                deferredPartIndices,
                onLog,
                onProgress,
            )
        }
    }

    fun pull(
        source: String,
        destination: OutputStream,
        onProgress: ((transferredBytes: Long) -> Unit)? = null,
    ): Long {
        ensureConnected()
        require(source.startsWith('/') && !source.contains("..")) { "无效的目标设备文件路径" }
        val stream = openService("sync:")
        val pending = ByteArrayOutputStream()
        var transferredBytes = 0L
        try {
            sendSyncPacket(stream, SYNC_RECV, source.toByteArray(Charsets.UTF_8))
            while (true) {
                val response = readSyncPacket(stream, pending)
                when (response.first) {
                    SYNC_DATA -> {
                        destination.write(response.second)
                        transferredBytes += response.second.size
                        onProgress?.invoke(transferredBytes)
                    }
                    SYNC_DONE -> break
                    SYNC_FAIL -> {
                        val detail = response.second.toString(Charsets.UTF_8).trim()
                        throw IOException(detail.ifBlank { "目标设备文件读取失败" })
                    }
                    else -> throw IOException("目标设备返回了无效的文件读取响应：${response.first}")
                }
            }
            destination.flush()
            return transferredBytes
        } finally {
            closeService(stream)
        }
    }

    fun push(
        source: InputStream,
        destination: String,
        mode: Int = DEFAULT_FILE_MODE,
        totalBytes: Long = -1L,
        resumeRemoteParts: Boolean = false,
        completedPartIndices: MutableSet<Int>? = null,
        deferredPartIndices: MutableSet<Int>? = null,
        onLog: ((message: String) -> Unit)? = null,
        onProgress: ((transferredBytes: Long, totalBytes: Long) -> Unit)? = null,
    ) {
        ensureConnected()
        require(destination.startsWith('/') && !destination.contains("..")) { "无效的目标路径" }
        require(
            destination != REMOTE_BASE64_FILE &&
                destination != REMOTE_DECODED_FILE &&
                !destination.startsWith(REMOTE_BASE64_PART_PREFIX) &&
                !destination.startsWith(REMOTE_WIRE_PART_PREFIX),
        ) {
            "目标路径与 ADB 传输临时文件冲突"
        }

        setCacheMode775(temporaryDirectory)
        val removedCacheEntries = clearStaleLocalTransferCache()
        onLog?.invoke(
            "本机 ADB 缓存目录固定为 ${temporaryDirectory.absolutePath}，权限=775，" +
                "已清理残留=$removedCacheEntries 项",
        )
        val encodedPath = shellQuote(REMOTE_BASE64_FILE)
        val encodedPartGlob = "${shellQuote(REMOTE_BASE64_PART_PREFIX)}*"
        val wirePartGlob = "${shellQuote(REMOTE_WIRE_PART_PREFIX)}*"
        val decodedPath = shellQuote(REMOTE_DECODED_FILE)
        val destinationPath = shellQuote(destination)
        val cleanupCommand = "rm -f -- $encodedPath $encodedPartGlob $wirePartGlob $decodedPath"
        // Keep one deterministic encoded cache file instead of creating a
        // timestamped temp file for every push. A process crash can therefore
        // leave at most this single file, which the next transfer overwrites
        // after the stale-cache cleanup above.
        val encodedFile = File(temporaryDirectory, LOCAL_BASE64_FILE_NAME)
        if (encodedFile.exists() && !encodedFile.delete()) {
            throw IOException("无法清理本机旧 ADB 编码文件")
        }
        if (!encodedFile.createNewFile()) {
            throw IOException("无法创建本机 ADB 编码文件")
        }
        setCacheMode775(encodedFile)
        val localPartsDirectory = File(
            temporaryDirectory,
            LOCAL_BASE64_PART_DIRECTORY_NAME,
        )
        if (localPartsDirectory.exists() && !localPartsDirectory.deleteRecursively()) {
            encodedFile.delete()
            throw IOException("无法清理本机旧 ADB 分片目录")
        }
        if (!localPartsDirectory.mkdir()) {
            encodedFile.delete()
            throw IOException("无法创建本机 ADB 分片目录")
        }
        setCacheMode775(localPartsDirectory)
        var preserveRemoteParts = false
        try {
            val sourceDigest = java.security.MessageDigest.getInstance("SHA-256")
            val sourceBytes = FileOutputStream(encodedFile).use { fileOutput ->
                val base64Output = Base64OutputStream(fileOutput, Base64.NO_WRAP)
                GZIPOutputStream(base64Output).use { gzipOutput ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copiedBytes = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        sourceDigest.update(buffer, 0, count)
                        gzipOutput.write(buffer, 0, count)
                        copiedBytes += count
                    }
                    copiedBytes
                }
            }
            if (totalBytes >= 0L && sourceBytes != totalBytes) {
                throw IOException("本地文件读取大小不一致：$sourceBytes/$totalBytes")
            }
            val restoredBytes = totalBytes.takeIf { it >= 0L } ?: sourceBytes
            val restoredSha256 = sourceDigest.digest().joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
            val encodedBytes = encodedFile.length()
            onLog?.invoke(
                "本机编码完成：原始=$restoredBytes 字节，GZIP+Base64=$encodedBytes 字节，" +
                    "原始SHA-256=$restoredSha256",
            )
            val localParts = splitIntoLocalPartFiles(encodedFile, localPartsDirectory)
            onLog?.invoke(
                "本机真实分片已创建：${localParts.size} 个，每片最大=$BASE64_PART_BYTES 字节，" +
                    "目录=${localPartsDirectory.absolutePath}",
            )
            if (!resumeRemoteParts) {
                onLog?.invoke("清理目标设备上一次传输留下的临时分片")
                val cleanup = shell(cleanupCommand)
                if (cleanup.exitCode != 0) {
                    throw IOException(
                        "目标设备传输临时文件清理失败" +
                            cleanup.output.takeIf(String::isNotBlank)?.let { "：$it" }.orEmpty(),
                    )
                }
            }

            val completedParts = completedPartIndices ?: linkedSetOf()
            completedParts.removeIf { it !in localParts.indices }
            val deferredParts = deferredPartIndices ?: linkedSetOf()
            deferredParts.removeIf { it !in localParts.indices }
            deferredParts.removeAll(completedParts)
            var uploadedEncodedBytes = 0L
            var reportedEncodedBytes = 0L

            fun reportPartProgress(transferred: Long) {
                val encodedProgress = (uploadedEncodedBytes + transferred)
                    .coerceIn(reportedEncodedBytes, encodedBytes)
                reportedEncodedBytes = encodedProgress
                val restoredProgress = if (encodedBytes > 0L) {
                    (encodedProgress * restoredBytes / encodedBytes).coerceIn(0L, restoredBytes)
                } else {
                    0L
                }
                onProgress?.invoke(restoredProgress, restoredBytes)
            }

            fun processPart(partIndex: Int, checkRemote: Boolean) {
                val localPart = localParts[partIndex]
                val partBytes = localPart.length()
                val partName = partIndex.toString().padStart(5, '0')
                val remotePart = REMOTE_BASE64_PART_PREFIX + partName
                val reportProgress = { transferred: Long ->
                    reportPartProgress(transferred)
                }
                onLog?.invoke(
                    "检查分片 ${partIndex + 1}/${localParts.size}：" +
                        "${localPart.name}，本机=$partBytes 字节，目标=$remotePart",
                )
                if (checkRemote && remotePartMatches(localPart, remotePart)) {
                    onLog?.invoke(
                        "分片 ${partIndex + 1}/${localParts.size} 远端大小和SHA-256一致，跳过传输",
                    )
                    reportProgress(partBytes)
                } else {
                    pushBase64Part(
                        source = localPart,
                        destination = remotePart,
                        partNumber = partIndex + 1,
                        totalParts = localParts.size,
                        onLog = onLog,
                        onProgress = reportProgress,
                    )
                }
                completedParts += partIndex
                deferredParts.remove(partIndex)
                uploadedEncodedBytes += partBytes
            }

            // Previously completed parts are trusted from this in-memory
            // transfer session. Do not issue hundreds of shell/sha256 probes.
            completedParts.sorted().forEach { partIndex ->
                val partBytes = localParts[partIndex].length()
                reportPartProgress(partBytes)
                uploadedEncodedBytes += partBytes
            }
            if (completedParts.isNotEmpty()) {
                onLog?.invoke(
                    "复用本次会话已完成分片 ${completedParts.size}/${localParts.size}，" +
                        "跳过远端重复检查和重复发送",
                )
            }

            // Complete the remaining parts first. Failed parts are deliberately
            // left for the deferred pass below instead of blocking the batch.
            for (partIndex in localParts.indices) {
                if (partIndex in completedParts || partIndex in deferredParts) continue
                processPart(
                    partIndex = partIndex,
                    checkRemote = resumeRemoteParts && completedPartIndices == null,
                )
            }

            if (deferredParts.isNotEmpty()) {
                onLog?.invoke(
                    "后续分片已完成，开始处理延后的失败分片：" +
                        deferredParts.sorted().joinToString(",") { it.toString().padStart(5, '0') },
                )
                for (partIndex in deferredParts.toList().sorted()) {
                    processPart(partIndex = partIndex, checkRemote = resumeRemoteParts)
                }
            }
            if (uploadedEncodedBytes != encodedBytes) {
                throw IOException("Base64 分片传输大小不一致：$uploadedEncodedBytes/$encodedBytes")
            }

            onLog?.invoke("全部 ${localParts.size} 个分片传输完成，开始按编号合并和校验")
            val permissionMode = (mode and FILE_PERMISSION_MASK).toString(8).padStart(3, '0')
            val restore = shell(
                "toybox cat $encodedPartGlob > $encodedPath && " +
                    "[ \"\$(toybox wc -c < $encodedPath)\" -eq $encodedBytes ] && " +
                    "toybox base64 -d $encodedPath | toybox gzip -dc > $decodedPath && " +
                    "[ \"\$(toybox wc -c < $decodedPath)\" -eq $restoredBytes ] && " +
                    "decoded_sha256=\$(toybox sha256sum $decodedPath) && " +
                    "[ \"\${decoded_sha256%% *}\" = '$restoredSha256' ] && " +
                    "chmod $permissionMode $decodedPath && mv -f -- $decodedPath $destinationPath",
            )
            if (restore.exitCode != 0) {
                throw IOException(
                    "目标设备 Base64/GZIP 还原或文件完整性校验失败" +
                        restore.output.takeIf(String::isNotBlank)?.let { "：$it" }.orEmpty(),
                )
            }
            onLog?.invoke(
                "目标文件还原完成：大小=$restoredBytes 字节，SHA-256=$restoredSha256，路径=$destination",
            )
            onProgress?.invoke(restoredBytes, restoredBytes)
        } catch (error: TransportException) {
            preserveRemoteParts = true
            throw error
        } finally {
            encodedFile.delete()
            localPartsDirectory.deleteRecursively()
            onLog?.invoke("本机编码文件和真实分片临时目录已清理")
            if (!preserveRemoteParts) runCatching { shell(cleanupCommand) }
        }
    }

    private fun splitIntoLocalPartFiles(encodedFile: File, partsDirectory: File): List<File> {
        val parts = mutableListOf<File>()
        FileInputStream(encodedFile).use { encodedInput ->
            val partBuffer = ByteArray(BASE64_PART_BYTES)
            while (true) {
                val partBytes = readChunk(encodedInput, partBuffer)
                if (partBytes == 0) break
                val partFile = File(
                    partsDirectory,
                    LOCAL_BASE64_PART_FILE_PREFIX + parts.size.toString().padStart(5, '0'),
                )
                FileOutputStream(partFile).use { output -> output.write(partBuffer, 0, partBytes) }
                setCacheMode775(partFile)
                parts += partFile
            }
        }
        if (parts.isEmpty()) throw IOException("本机 ADB 分片结果为空")
        return parts
    }

    /** Push one real local part through one independent ADB Sync session. */
    private fun pushBase64Part(
        source: File,
        destination: String,
        partNumber: Int,
        totalParts: Int,
        onLog: ((message: String) -> Unit)?,
        onProgress: (transferredBytes: Long) -> Unit,
    ) {
        var lastError: IOException? = null
        repeat(BASE64_PART_PUSH_ATTEMPTS) { attempt ->
            try {
                val wirePart = createRandomizedWirePart(source)
                val partName = destination.substringAfterLast('.')
                val remoteWirePart = REMOTE_WIRE_PART_PREFIX + partName
                val remoteWirePath = shellQuote(remoteWirePart)
                val stablePartPath = shellQuote(destination)
                val sourceBytes = source.length()
                val sourceSha256 = sha256Hex(source)
                rotateOutputRequest()
                onLog?.invoke(
                    "分片 $partNumber/$totalParts 建立独立 ADB Sync 传输并轮换 OUT 请求；" +
                        "线上内容已随机化=${wirePart.tag}，原始=$sourceBytes 字节，线上=${wirePart.bytes.size} 字节" +
                        if (attempt == 0) "" else "（重试 ${attempt + 1}/$BASE64_PART_PUSH_ATTEMPTS）",
                )
                ByteArrayInputStream(wirePart.bytes).use { input ->
                    pushRaw(
                        source = input,
                        destination = remoteWirePart,
                        mode = BASE64_FILE_MODE,
                        totalBytes = wirePart.bytes.size.toLong(),
                        onFrame = { frameNumber, frameCount, offset, dataBytes, adbPayloadBytes ->
                            onLog?.invoke(
                                "分片 $partNumber/$totalParts 已完成内部帧 $frameNumber/$frameCount：" +
                                    "offset=$offset，DATA=$dataBytes 字节，" +
                                    "ADB payload=$adbPayloadBytes 字节",
                            )
                        },
                    ) { transferred, total ->
                        val stableProgress = if (total > 0L) {
                            (transferred * sourceBytes / total).coerceIn(0L, sourceBytes)
                        } else {
                            0L
                        }
                        onProgress(stableProgress)
                    }
                }
                val normalize = shell(
                    "toybox base64 -d $remoteWirePath | toybox gzip -dc | " +
                        "toybox tail -c +${WIRE_SALT_BYTES + 1} > $stablePartPath && " +
                        "[ \"\$(toybox wc -c < $stablePartPath)\" -eq $sourceBytes ] && " +
                        "part_sha256=\$(toybox sha256sum $stablePartPath) && " +
                        "[ \"\${part_sha256%% *}\" = '$sourceSha256' ] && " +
                        "rm -f -- $remoteWirePath",
                )
                if (normalize.exitCode != 0) {
                    throw IOException(
                        "随机化分片还原或校验失败" +
                            normalize.output.takeIf(String::isNotBlank)?.let { "：$it" }.orEmpty(),
                    )
                }
                onProgress(sourceBytes)
                onLog?.invoke(
                    "分片 $partNumber/$totalParts 传输完成并已还原校验：" +
                        "${source.name}，$sourceBytes 字节，SHA-256=$sourceSha256",
                )
                return
            } catch (error: IOException) {
                lastError = error
                if (error is TransportException) {
                    val partName = destination.substringAfterLast('.')
                    onLog?.invoke(
                        "分片 $partNumber/$totalParts USB transport 中断：${error.message.orEmpty()}",
                    )
                    throw TransportException(
                        "Base64分片${partName}传输中断：${error.message.orEmpty()}",
                        error,
                        transferPartIndex = partName.toIntOrNull(),
                    )
                }
                onLog?.invoke(
                    "分片 $partNumber/$totalParts 传输失败：${error.message.orEmpty()}",
                )
                if (attempt + 1 >= BASE64_PART_PUSH_ATTEMPTS) throw error
            }
        }
        throw lastError ?: IOException("Base64 分片传输失败")
    }

    /**
     * Re-encode a part with a fresh salt before every attempt. The target
     * removes the salt after decoding, so the assembled part remains byte for
     * byte identical while a retry never repeats the previous USB payload.
     */
    private fun createRandomizedWirePart(source: File): WirePart {
        val salt = ByteArray(WIRE_SALT_BYTES).also(WIRE_RANDOM::nextBytes)
        val output = ByteArrayOutputStream(source.length().toInt() + WIRE_ENCODING_OVERHEAD_BYTES)
        val base64 = Base64OutputStream(output, Base64.NO_WRAP)
        GZIPOutputStream(base64).use { gzip ->
            gzip.write(salt)
            FileInputStream(source).use { input -> input.copyTo(gzip) }
        }
        val tag = java.security.MessageDigest.getInstance("SHA-256")
            .digest(salt)
            .take(WIRE_TAG_BYTES)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return WirePart(output.toByteArray(), tag)
    }

    private fun readChunk(source: InputStream, destination: ByteArray): Int {
        var offset = 0
        while (offset < destination.size) {
            val count = source.read(destination, offset, destination.size - offset)
            if (count < 0) break
            if (count == 0) continue
            offset += count
        }
        return offset
    }

    private fun clearStaleLocalTransferCache(): Int {
        val entries = temporaryDirectory.listFiles() ?: return 0
        var removed = 0
        entries.filter { entry ->
            entry.name == LOCAL_BASE64_PART_DIRECTORY_NAME ||
                entry.name.startsWith(LOCAL_BASE64_PART_DIRECTORY_PREFIX) ||
                entry.name.startsWith(LOCAL_BASE64_FILE_PREFIX)
        }.forEach { entry ->
            if (entry.deleteRecursively()) removed += 1
        }
        return removed
    }

    private fun setCacheMode775(file: File) {
        try {
            Os.chmod(file.absolutePath, LOCAL_CACHE_MODE_775)
        } catch (error: Throwable) {
            throw IOException("无法设置本机 ADB 缓存权限=775：${file.absolutePath}", error)
        }
    }

    private fun remotePartMatches(source: File, destination: String): Boolean {
        val sha256 = sha256Hex(source)
        val path = shellQuote(destination)
        val check = shell(
            "[ \"\$(toybox wc -c < $path)\" -eq ${source.length()} ] && " +
                "part_sha256=\$(toybox sha256sum $path) && " +
                "[ \"\${part_sha256%% *}\" = '$sha256' ]",
        )
        return check.exitCode == 0
    }

    private fun sha256Hex(source: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        FileInputStream(source).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    /** Sends an already complete file through the ordinary ADB Sync push path. */
    private fun pushRaw(
        source: InputStream,
        destination: String,
        mode: Int,
        totalBytes: Long,
        onFrame: ((
            frameNumber: Int,
            frameCount: Int,
            offset: Long,
            dataBytes: Int,
            adbPayloadBytes: Int,
        ) -> Unit)? = null,
        onProgress: ((transferredBytes: Long, totalBytes: Long) -> Unit)?,
    ) {
        val stream = openService("sync:")
        try {
            var transferredBytes = 0L
            onProgress?.invoke(transferredBytes, totalBytes)
            sendSyncPacket(stream, SYNC_SEND, "$destination,$mode".toByteArray(Charsets.UTF_8))
            val syncDataBytes = minOf(
                (maxPayload - SYNC_HEADER_BYTES).coerceAtLeast(1),
                SYNC_CHUNK_BYTES,
            )
            val buffer = ByteArray(syncDataBytes)
            val frameCount = if (totalBytes > 0L) {
                ((totalBytes + syncDataBytes - 1L) / syncDataBytes).toInt()
            } else {
                0
            }
            var frameNumber = 0
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                frameNumber += 1
                val adbPayloadBytes = SYNC_HEADER_BYTES + count
                try {
                    sendSyncPacket(stream, SYNC_DATA, buffer, count)
                } catch (error: TransportException) {
                    throw TransportException(
                        "Sync DATA内部帧$frameNumber/$frameCount 中断：" +
                            "offset=$transferredBytes，DATA=$count，" +
                            "ADB payload=$adbPayloadBytes；${error.message.orEmpty()}",
                        error,
                    )
                }
                onFrame?.invoke(frameNumber, frameCount, transferredBytes, count, adbPayloadBytes)
                transferredBytes += count
                onProgress?.invoke(transferredBytes, totalBytes)
            }
            sendSyncDone(stream)
            val response = readSyncResponse(stream)
            if (response.first != SYNC_OKAY) {
                throw IOException(response.second.ifBlank { "目标设备文件传输失败" })
            }
            onProgress?.invoke(totalBytes, totalBytes)
        } finally {
            closeService(stream)
        }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun readService(service: String): ByteArray {
        val stream = openService(service)
        val output = ByteArrayOutputStream()
        try {
            while (true) {
                val message = read(COMMAND_TIMEOUT_MS)
                when (message.command) {
                    A_WRTE -> {
                        if (message.arg1 == stream.localId) {
                            message.data?.let(output::write)
                            stream.remoteId = message.arg0
                            write(A_OKAY, stream.localId, stream.remoteId)
                        } else {
                            // ADB streams are multiplexed. A delayed packet from
                            // an older shell must not be consumed as this stream.
                            acknowledgeForeignMessage(message)
                        }
                    }
                    A_CLSE -> {
                        if (message.arg1 == stream.localId) {
                            write(A_CLSE, stream.localId, message.arg0)
                            break
                        }
                        acknowledgeForeignMessage(message)
                    }
                    A_OKAY -> Unit
                    else -> throw IOException("ADB 命令返回了异常数据")
                }
            }
        } finally {
            output.flush()
        }
        return output.toByteArray()
    }

    private fun openService(name: String): AdbStream {
        val localId = nextLocalId.getAndIncrement()
        write(A_OPEN, localId, 0, name)
        while (true) {
            val response = read()
            when (response.command) {
                A_OKAY -> {
                    if (response.arg1 == localId) {
                        return AdbStream(localId, response.arg0)
                    }
                    // An A_OKAY for another local id belongs to a different
                    // stream and must not be used to open this service.
                }
                A_CLSE -> {
                    if (response.arg1 == localId) {
                        // ADB requires the host to acknowledge a rejected local
                        // stream. Keep the IDs from the matching packet.
                        runCatching { write(A_CLSE, localId, response.arg0) }
                        throw ServiceRejectedException(name)
                    }
                    acknowledgeForeignMessage(response)
                }
                A_WRTE -> acknowledgeForeignMessage(response)
                else -> throw IOException("ADB 服务握手失败：$name")
            }
        }
    }

    private fun closeService(stream: AdbStream) {
        runCatching {
            write(A_CLSE, stream.localId, stream.remoteId)
            repeat(CLOSE_DRAIN_MESSAGES) {
                val response = read(CLOSE_TIMEOUT_MS)
                when (response.command) {
                    A_CLSE -> {
                        if (response.arg1 == stream.localId || response.arg0 == stream.remoteId) {
                            return@runCatching
                        }
                        acknowledgeForeignMessage(response)
                    }
                    A_WRTE -> {
                        if (response.arg1 == stream.localId) {
                            write(A_OKAY, stream.localId, response.arg0)
                        } else {
                            acknowledgeForeignMessage(response)
                        }
                    }
                    A_OKAY -> Unit
                    else -> return@repeat
                }
            }
        }
    }

    /** Acknowledge a packet that belongs to another still-draining ADB stream. */
    private fun acknowledgeForeignMessage(message: AdbMessage) {
        if (message.arg1 <= 0) return
        when (message.command) {
            A_WRTE -> write(A_OKAY, message.arg1, message.arg0)
            A_CLSE -> write(A_CLSE, message.arg1, message.arg0)
        }
    }

    private fun sendSyncPacket(
        stream: AdbStream,
        id: String,
        payload: ByteArray,
        payloadBytes: Int = payload.size,
    ) {
        val packet = ByteArrayOutputStream(SYNC_HEADER_BYTES + payloadBytes)
        appendSyncPacket(packet, id, payload, payloadBytes)
        writeStream(stream, packet.toByteArray())
    }

    private fun appendSyncPacket(
        destination: ByteArrayOutputStream,
        id: String,
        payload: ByteArray,
        payloadBytes: Int,
    ) {
        require(id.length == 4 && payloadBytes in 0..payload.size) { "ADB Sync 数据无效" }
        val header = ByteBuffer.allocate(SYNC_HEADER_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(id.toByteArray(Charsets.US_ASCII))
            .putInt(payloadBytes)
            .array()
        destination.write(header)
        destination.write(payload, 0, payloadBytes)
    }

    private fun sendSyncDone(stream: AdbStream) {
        val packet = ByteBuffer.allocate(SYNC_HEADER_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(SYNC_DONE.toByteArray(Charsets.US_ASCII))
            .putInt((System.currentTimeMillis() / 1000L).toInt())
            .array()
        writeStream(stream, packet)
    }

    private fun writeStream(stream: AdbStream, data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val length = minOf(maxPayload, data.size - offset)
            write(A_WRTE, stream.localId, stream.remoteId, data.copyOfRange(offset, offset + length))
            while (true) {
                val response = read()
                when (response.command) {
                    A_OKAY -> {
                        if (response.arg1 == stream.localId) {
                            stream.remoteId = response.arg0
                            offset += length
                            break
                        }
                    }
                    A_CLSE -> {
                        if (response.arg1 == stream.localId) {
                            throw IOException("ADB 文件传输被目标设备中断")
                        }
                        acknowledgeForeignMessage(response)
                    }
                    A_WRTE -> {
                        if (response.arg1 == stream.localId) {
                            throw IOException("ADB 文件传输收到未预期的数据报文")
                        }
                        acknowledgeForeignMessage(response)
                    }
                    else -> throw IOException("ADB 文件传输返回了异常报文")
                }
            }
        }
    }

    private fun readSyncPacket(
        stream: AdbStream,
        pending: ByteArrayOutputStream,
    ): Pair<String, ByteArray> {
        while (true) {
            val buffered = pending.toByteArray()
            if (buffered.size >= SYNC_HEADER_BYTES) {
                val header = ByteBuffer.wrap(buffered, 0, SYNC_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                val idBytes = ByteArray(4).also(header::get)
                val length = header.int
                if (length !in 0..MAX_SYNC_FRAME_BYTES) {
                    throw IOException("ADB Sync 数据长度无效：$length")
                }
                val packetBytes = SYNC_HEADER_BYTES + length
                if (buffered.size >= packetBytes) {
                    val payload = buffered.copyOfRange(SYNC_HEADER_BYTES, packetBytes)
                    pending.reset()
                    if (buffered.size > packetBytes) {
                        pending.write(buffered, packetBytes, buffered.size - packetBytes)
                    }
                    return idBytes.toString(Charsets.US_ASCII) to payload
                }
            }
            val message = read()
            when (message.command) {
                A_WRTE -> {
                    if (message.arg1 == stream.localId) {
                        message.data?.let(pending::write)
                        stream.remoteId = message.arg0
                        write(A_OKAY, stream.localId, stream.remoteId)
                    } else {
                        acknowledgeForeignMessage(message)
                    }
                }
                A_CLSE -> {
                    if (message.arg1 == stream.localId) {
                        throw IOException("ADB 文件传输响应提前结束")
                    }
                    acknowledgeForeignMessage(message)
                }
                A_OKAY -> Unit
                else -> throw IOException("ADB 文件传输响应无效")
            }
        }
    }

    private fun readSyncResponse(stream: AdbStream): Pair<String, String> {
        val response = readSyncPacket(stream, ByteArrayOutputStream())
        return response.first to response.second.toString(Charsets.UTF_8).trim()
    }

    private fun write(command: Int, arg0: Int, arg1: Int, data: String) {
        write(AdbMessage(command, arg0, arg1, data))
    }

    private fun write(command: Int, arg0: Int, arg1: Int, data: ByteArray? = null) {
        write(AdbMessage(command, arg0, arg1, data))
    }

    private fun write(message: AdbMessage) {
        val packet = message.toByteArray(skipChecksum)
        writeUsbOut(
            source = packet,
            startOffset = 0,
            byteCount = AdbMessage.HEADER_LENGTH,
            phase = "header",
        )
        if (packet.size > AdbMessage.HEADER_LENGTH) {
            writeUsbOut(
                source = packet,
                startOffset = AdbMessage.HEADER_LENGTH,
                byteCount = packet.size - AdbMessage.HEADER_LENGTH,
                phase = "payload",
            )
        }
    }

    private fun read(timeoutMs: Int = IO_TIMEOUT_MS): AdbMessage {
        val header = ByteArray(AdbMessage.HEADER_LENGTH)
        readFully(header, timeoutMs)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val dataLength = buffer.int
        val checksum = buffer.int
        val magic = buffer.int
        if (dataLength !in 0..USB_ADB_MAX_PAYLOAD_BYTES) throw IOException("ADB 数据长度无效：$dataLength")
        val data = ByteArray(dataLength)
        if (data.isNotEmpty()) readFully(data, timeoutMs)
        return AdbMessage(command, arg0, arg1, dataLength, checksum, magic, data)
            .also { message ->
                // A modern adbd switches AUTH/CNXN to checksum-free packets
                // immediately after reading our advertised protocol version.
                // Accept only those two well-defined handshake forms before
                // final negotiation, then use the negotiated mode exclusively.
                val checksumlessHandshake = !connected && checksum == 0 &&
                    (command == A_AUTH ||
                        (command == A_CNXN && arg0 >= USB_ADB_SKIP_CHECKSUM_VERSION))
                runCatching { message.validateOrThrow(skipChecksum || checksumlessHandshake) }
                    .getOrElse { throw IOException("ADB 报文校验失败", it) }
                if (checksumlessHandshake) skipChecksum = true
            }
    }

    private fun readFully(destination: ByteArray, timeoutMs: Int) {
        var offset = 0
        while (offset < destination.size) {
            val length = minOf(USB_REQUEST_READ_CHUNK_BYTES, destination.size - offset)
            val count = readUsbRequest(destination, offset, length, timeoutMs)
            offset += count
        }
    }

    private fun writeUsbOut(
        source: ByteArray,
        startOffset: Int,
        byteCount: Int,
        phase: String,
    ) {
        require(startOffset >= 0 && byteCount >= 0 && startOffset + byteCount <= source.size) {
            "无效的 USB ADB 写入范围"
        }
        require(byteCount > 0) { "USB ADB 写入数据为空" }

        // A timed-out native request may still be queued after cancel(). Never
        // let a new request reuse its ByteBuffer: a late native completion would
        // otherwise advance the new frame's position (for example 24 -> 1536).
        val buffer = outputBuffer
        require(byteCount <= buffer.capacity()) { "USB ADB 写入超出缓冲区容量" }
        buffer.clear()
        buffer.put(source, startOffset, byteCount)
        buffer.flip()
        val request = outputRequest
        try {
            request.clientData = phase
            if (!request.queue(buffer)) {
                throw TransportException(
                    "USB ADB OUT 请求入队失败：phase=$phase，bytes=$byteCount，" +
                        "endpoint=0x${outputEndpoint.address.toString(16)}",
                )
            }
            outputRequestQueued = true
            waitForOutputCompletion(request, phase, byteCount)
            val completedBytes = buffer.position()
            if (completedBytes != byteCount) {
                throw TransportException(
                    "USB ADB OUT 写入不完整：phase=$phase，" +
                        "completed=$completedBytes/$byteCount，" +
                        "endpoint=0x${outputEndpoint.address.toString(16)}",
                )
            }
        } finally {
            request.clientData = null
        }
    }

    /** Wait for this request; a timeout is not recoverable until it is dequeued. */
    private fun waitForOutputCompletion(request: UsbRequest, phase: String, byteCount: Int) {
        val completed = try {
            connection.requestWait(USB_OUT_TIMEOUT_MS.toLong())
        } catch (_: TimeoutException) {
            val drained = cancelAndDrainRequest(request)
            outputRequestQueued = !drained
            val result = if (drained) "已取消并出队" else "取消后未出队，需重建连接"
            throw TransportException(
                "USB ADB OUT 请求超时：phase=$phase，bytes=$byteCount；$result",
            )
        } catch (error: BufferOverflowException) {
            outputRequestQueued = false
            throw TransportException("USB ADB OUT 完成长度异常，旧请求已出队", error)
        } catch (error: IllegalArgumentException) {
            // UsbRequest.dequeue() clears its queued flag before advancing the
            // ByteBuffer position, so this request is already safe to replace.
            outputRequestQueued = false
            throw TransportException("USB ADB OUT 完成缓冲区异常，旧请求已出队", error)
        } ?: run {
            throw TransportException("USB ADB OUT 请求失败或设备已断开，需重建连接")
        }
        if (completed === request) {
            outputRequestQueued = false
            return
        }
        val drained = cancelAndDrainRequest(request)
        outputRequestQueued = !drained
        throw TransportException("USB ADB OUT 返回了未知请求，需重建连接")
    }

    /** Give each file part a fresh OUT request and an independent native buffer. */
    private fun rotateOutputRequest() {
        if (outputRequestQueued) {
            throw TransportException("USB ADB OUT 请求仍在队列，禁止开始新分片")
        }
        replaceOutputRequest("USB ADB OUT 分片请求初始化失败")
    }

    private fun replaceOutputRequest(errorMessage: String) {
        val replacement = UsbRequest()
        if (!replacement.initialize(connection, outputEndpoint)) {
            replacement.close()
            throw TransportException(errorMessage)
        }
        val previous = outputRequest
        outputRequest = replacement
        outputBuffer = ByteBuffer.allocateDirect(USB_ADB_MAX_PAYLOAD_BYTES)
        previous.clientData = null
        previous.close()
    }

    /**
     * cancel() only submits cancellation. requestWait() is still required to
     * clear UsbRequest.mIsUsingNewQueue before another request can be queued.
     */
    private fun cancelAndDrainRequest(request: UsbRequest): Boolean {
        runCatching { request.cancel() }
        return try {
            connection.requestWait(CANCEL_DRAIN_TIMEOUT_MS.toLong()) === request
        } catch (_: TimeoutException) {
            false
        } catch (_: BufferOverflowException) {
            // dequeue() cleared mIsUsingNewQueue before buffer.position() failed.
            true
        } catch (_: IllegalArgumentException) {
            // Same Android dequeue ordering as the BufferOverflowException path.
            true
        }
    }

    private fun readUsbRequest(
        destination: ByteArray,
        startOffset: Int,
        byteCount: Int,
        timeoutMs: Int,
    ): Int {
        require(byteCount <= inputBuffer.capacity()) { "USB ADB 读取超出缓冲区容量" }
        inputBuffer.clear()
        inputBuffer.limit(byteCount)
        try {
            inputRequest.clientData = inputBuffer
            if (!inputRequest.queue(inputBuffer)) {
                throw TransportException("USB ADB IN 请求入队失败")
            }
            inputRequestQueued = true
            val completed = try {
                connection.requestWait(timeoutMs.toLong())
            } catch (_: TimeoutException) {
                val drained = cancelAndDrainRequest(inputRequest)
                inputRequestQueued = !drained
                if (drained) replaceInputRequest()
                val result = if (drained) "已取消并出队" else "取消后未出队，需重建连接"
                throw TransportException("USB ADB IN 请求超时或设备已断开；$result")
            }
            if (completed !== inputRequest) {
                val drained = cancelAndDrainRequest(inputRequest)
                inputRequestQueued = !drained
                if (drained) replaceInputRequest()
                throw TransportException("USB ADB IN 返回了未知请求，需重建连接")
            }
            inputRequestQueued = false
            val completedBytes = inputBuffer.position()
            if (completedBytes <= 0 || completedBytes > byteCount) {
                throw TransportException("USB ADB IN 读取长度无效：$completedBytes/$byteCount")
            }
            inputBuffer.flip()
            inputBuffer.get(destination, startOffset, completedBytes)
            return completedBytes
        } finally {
            inputRequest.clientData = null
        }
    }

    private fun replaceInputRequest() {
        val replacement = UsbRequest()
        if (!replacement.initialize(connection, inputEndpoint)) {
            replacement.close()
            throw TransportException("USB ADB IN 恢复请求初始化失败，需重建连接")
        }
        val previous = inputRequest
        inputRequest = replacement
        inputBuffer = ByteBuffer.allocateDirect(USB_REQUEST_READ_CHUNK_BYTES)
        inputRequestQueued = false
        previous.clientData = null
        previous.close()
    }

    private fun ensureConnected() {
        if (!connected) throw IOException("目标设备尚未建立 ADB 连接")
    }

    override fun close() {
        if (closed) return
        if (outputRequestQueued) {
            outputRequestQueued = !runCatching { cancelAndDrainRequest(outputRequest) }.getOrDefault(false)
        }
        if (inputRequestQueued) {
            inputRequestQueued = !runCatching { cancelAndDrainRequest(inputRequest) }.getOrDefault(false)
        }
        closed = true
        connected = false
        runCatching { outputRequest.cancel() }
        runCatching { inputRequest.cancel() }
        runCatching { outputRequest.close() }
        runCatching { inputRequest.close() }
        runCatching { connection.releaseInterface(adbInterface) }
        connection.close()
    }

    private data class AdbStream(val localId: Int, var remoteId: Int)
    private data class WirePart(val bytes: ByteArray, val tag: String)
    companion object {
        init {
            System.loadLibrary("adb")
        }

        private const val ADB_CLASS = 0xff
        private const val ADB_SUBCLASS = 0x42
        private const val ADB_PROTOCOL = 0x01
        private const val IO_TIMEOUT_MS = 15_000
        private const val USB_OUT_TIMEOUT_MS = 5_000
        private const val CANCEL_DRAIN_TIMEOUT_MS = 3_000
        private const val COMMAND_TIMEOUT_MS = 180_000
        private const val AUTH_TIMEOUT_MS = 60_000
        private const val REBOOT_ACK_TIMEOUT_MS = 10_000
        private const val CLOSE_TIMEOUT_MS = 2_000
        private const val CLOSE_DRAIN_MESSAGES = 4
        private const val MIN_PAYLOAD_BYTES = 1024
        private const val USB_ADB_LEGACY_MAX_PAYLOAD_BYTES = 4 * 1024
        private const val USB_ADB_MAX_PAYLOAD_BYTES = 1024 * 1024
        private const val USB_ADB_SKIP_CHECKSUM_VERSION = 0x01000001
        private const val USB_ADB_VERSION = USB_ADB_SKIP_CHECKSUM_VERSION
        private const val UNKNOWN_EXIT_CODE = -1
        private const val MAX_SERVICE_NAME_CHARS = 1024
        private const val DEFAULT_FILE_MODE = 0x81ED // regular file + 0755
        private const val BASE64_FILE_MODE = 0x81A4 // regular file + 0644
        private const val FILE_PERMISSION_MASK = 0x1FF
        private const val SYNC_HEADER_BYTES = 8
        // Split the complete GZIP+Base64 stream into independently acknowledged
        // remote files. Each file is then sent as smaller Sync DATA records.
        private const val BASE64_PART_BYTES = 16 * 1024
        private const val BASE64_PART_PUSH_ATTEMPTS = 3
        private const val SYNC_CHUNK_BYTES = 4 * 1024
        private const val WIRE_SALT_BYTES = 24
        private const val WIRE_TAG_BYTES = 6
        private const val WIRE_ENCODING_OVERHEAD_BYTES = 512
        private const val LOCAL_BASE64_FILE_NAME = "yh-adb-push.base64"
        private const val LOCAL_BASE64_FILE_PREFIX = "yh-adb-push"
        private const val LOCAL_BASE64_PART_DIRECTORY_PREFIX = "yh-adb-parts-"
        private const val LOCAL_BASE64_PART_DIRECTORY_NAME = "yh-adb-parts"
        private const val LOCAL_BASE64_PART_FILE_PREFIX = "part."
        private const val LOCAL_CACHE_MODE_775 = 0x1FD
        private const val REMOTE_BASE64_FILE = "/data/local/tmp/.yuehong_adb_push.b64"
        private const val REMOTE_BASE64_PART_PREFIX = "/data/local/tmp/.yuehong_adb_push.b64.part."
        private const val REMOTE_WIRE_PART_PREFIX = "/data/local/tmp/.yuehong_adb_wire.part."
        private const val REMOTE_DECODED_FILE = "/data/local/tmp/.yuehong_adb_push.part"
        private const val SYNC_SEND = "SEND"
        private const val SYNC_RECV = "RECV"
        private const val SYNC_DATA = "DATA"
        private const val SYNC_DONE = "DONE"
        private const val SYNC_FAIL = "FAIL"
        private const val SYNC_OKAY = "OKAY"
        private const val MAX_SYNC_FRAME_BYTES = USB_ADB_MAX_PAYLOAD_BYTES
        private const val USB_REQUEST_READ_CHUNK_BYTES = 16 * 1024
        private val REBOOT_TARGET_PATTERN = Regex("^[A-Za-z0-9_-]{1,32}$")
        private val WIRE_RANDOM = SecureRandom()

        fun findAdbDevices(usbManager: UsbManager): List<UsbDevice> =
            usbManager.deviceList.values.filter { findAdbInterface(it) != null }

        fun isAdbDevice(device: UsbDevice): Boolean = findAdbInterface(device) != null

        private fun findAdbInterface(device: UsbDevice): UsbInterface? {
            for (index in 0 until device.interfaceCount) {
                val candidate = device.getInterface(index)
                if (candidate.interfaceClass == ADB_CLASS &&
                    candidate.interfaceSubclass == ADB_SUBCLASS &&
                    candidate.interfaceProtocol == ADB_PROTOCOL
                ) {
                    return candidate
                }
            }
            return null
        }

        private fun findEndpoint(adbInterface: UsbInterface, direction: Int): UsbEndpoint? {
            for (index in 0 until adbInterface.endpointCount) {
                val endpoint = adbInterface.getEndpoint(index)
                if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK && endpoint.direction == direction) {
                    return endpoint
                }
            }
            return null
        }
    }
}
