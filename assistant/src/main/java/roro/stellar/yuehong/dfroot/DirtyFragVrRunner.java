package roro.stellar.yuehong.dfroot;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.IpSecAlgorithm;
import android.net.IpSecManager;
import android.net.IpSecTransform;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/** Stages the manager selected by the assistant and runs the DirtyFrag VR chain. */
public final class DirtyFragVrRunner {
    private static final String NATIVE_BIN = "libdfroot.so";
    private static final String BOOTSTRAP_BIN = "libbootstrap.so";
    private static final String WORK_DIRECTORY = "dfroot";
    private static final String BOOTSTRAP_FILE = "bootstrap";
    private static final String KSUD_FILE = "ksud";
    private static final String PREFS_NAME = "dfroot";
    private static final String PREF_SU_MANAGER = "su_manager";
    private static final String PREF_SOFT_REBOOT = "soft_reboot";
    private static final String PREF_AUTO_SOFT_REBOOT = "auto_soft_reboot";
    private static final String PREF_DISABLE_MODULES = "disable_modules";

    private static final String PREF_VR_DISARM = "vr_disarm";
    private static final String PREF_VR_AUTO = "vr_auto";
    private static final String PREF_VR_OFFSET = "vr_off";

    private DirtyFragVrRunner() {
    }

    public static boolean isArmed() {
        return new File("/dev/df").exists();
    }

    public static synchronized int run(Context context, String managerPackage,
                                       String ksudPath, DirtyFragVrReporter reporter)
            throws Exception {
        Context appContext = context.getApplicationContext();
        Context deContext = appContext.createDeviceProtectedStorageContext();
        if (isArmed()) {
            reporter.report("already hooked, skipping\n");
            return 0;
        }
        if (managerPackage == null || managerPackage.trim().isEmpty()) {
            throw new IOException("No selected KernelSU manager package was provided");
        }
        if (ksudPath == null || ksudPath.trim().isEmpty()) {
            throw new IOException("No recognized ksud path was provided for " + managerPackage);
        }

        File work = new File(deContext.getFilesDir(), WORK_DIRECTORY);
        if (!work.exists() && !work.mkdirs()) {
            throw new IOException("unable to create DirtyFrag staging directory");
        }
        File ksud = new File(deContext.getFilesDir().getParentFile(), KSUD_FILE);
        stageManagerKsud(new File(ksudPath), ksud);
        stageBinary(new File(deContext.getApplicationInfo().nativeLibraryDir, BOOTSTRAP_BIN),
                new File(work, BOOTSTRAP_FILE));

        // The native bootstrap reads these DE preferences after the kernel hand-off.
        SharedPreferences prefs = deContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean autoSoftReboot = prefs.getBoolean(PREF_AUTO_SOFT_REBOOT, false);
        boolean vrDisarm = prefs.getBoolean(PREF_VR_DISARM, false);
        boolean vrAuto = prefs.getBoolean(PREF_VR_AUTO, true);
        String vrOffset = prefs.getString(PREF_VR_OFFSET, "").trim();
        if (!prefs.edit()
                .putString(PREF_SU_MANAGER, managerPackage)
                .putBoolean(PREF_SOFT_REBOOT, autoSoftReboot)
                .putBoolean(PREF_DISABLE_MODULES, false)
                .commit()) {
            throw new IOException("unable to persist DirtyFrag preferences");
        }

        File bin = new File(deContext.getApplicationInfo().nativeLibraryDir, NATIVE_BIN);
        if (!bin.isFile()) throw new IOException(NATIVE_BIN + " is missing from nativeLibraryDir");

        reporter.report("DirtyFrag VR v1.16: KernelSU manager hand-off.\n");
        reporter.report("DirtyFrag VR: probe removal " + (vrDisarm ? "enabled" : "disabled") + ".\n");
        reporter.report("DirtyFrag manager package: " + managerPackage + "\n");
        reporter.report("DirtyFrag model: " + Build.MANUFACTURER + " " + Build.MODEL + "\n");
        reporter.report("DirtyFrag kernel: " + System.getProperty("os.version", "unknown") + "\n\n");

        List<String> vrArgs = new ArrayList<>();
        if (vrDisarm) {
            if (vrAuto) {
                vrArgs.add("--vr-auto");
                File resolvedOffset = new File(work, "vr_offset.txt");
                if (resolvedOffset.exists() && !resolvedOffset.delete()) {
                    throw new IOException("unable to clear the previous VR offset result");
                }
                reporter.report("VR offset source: running kernel BTF.\n");
            } else {
                long offset = parseVrOffset(vrOffset);
                vrArgs.add("--vr-off");
                vrArgs.add(Long.toString(offset));
                reporter.report("VR funcs offset: " + offset + " (0x"
                        + Long.toHexString(offset) + ").\n");
            }
        }

        IpSecManager ipsec = (IpSecManager) deContext.getSystemService(Context.IPSEC_SERVICE);
        if (ipsec == null) throw new IllegalStateException("IpSecManager unavailable");
        IpSecManager.UdpEncapsulationSocket encapSock = ipsec.openUdpEncapsulationSocket();
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        IpSecManager.SecurityParameterIndex spiObj =
                ipsec.allocateSecurityParameterIndex(loopback);
        byte[] aesKey = new byte[32];
        new SecureRandom().nextBytes(aesKey);
        DatagramSocket senderSock = new DatagramSocket();
        int senderPort = senderSock.getLocalPort();
        senderSock.close();
        IpSecTransform transform = new IpSecTransform.Builder(deContext)
                .setEncryption(new IpSecAlgorithm(IpSecAlgorithm.CRYPT_AES_CBC, aesKey))
                .setIpv4Encapsulation(encapSock, senderPort)
                .buildTransportModeTransform(loopback, spiObj);
        try {
            int exitCode = runBinary(bin, reporter, encapSock.getPort(), spiObj.getSpi(),
                    aesKey, senderPort, vrArgs);
            if (vrDisarm && vrAuto) {
                reportResolvedOffset(new File(work, "vr_offset.txt"), reporter);
            }
            return exitCode;
        } finally {
            transform.close();
            spiObj.close();
            encapSock.close();
        }
    }

    private static void stageManagerKsud(File source, File destination) throws IOException {
        if (!source.isFile()) throw new IOException("selected ksud is missing: " + source);
        stageBinary(source, destination);
    }

    private static long parseVrOffset(String value) throws IOException {
        if (value == null || value.trim().isEmpty()) {
            throw new IOException("VR probe removal is enabled but the funcs offset is empty");
        }
        String normalized = value.trim();
        try {
            long offset = normalized.startsWith("0x") || normalized.startsWith("0X")
                    ? Long.parseLong(normalized.substring(2), 16)
                    : Long.parseLong(normalized, 10);
            if (offset <= 0 || offset > 0x100) {
                throw new IOException("VR funcs offset must be between 1 and 0x100");
            }
            return offset;
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid VR funcs offset: " + normalized, exception);
        }
    }

    private static void reportResolvedOffset(File resultFile, DirtyFragVrReporter reporter) {
        if (!resultFile.isFile()) return;
        try (BufferedReader reader = new BufferedReader(new FileReader(resultFile))) {
            String value = reader.readLine();
            if (value == null) return;
            value = value.trim();
            if ("fail".equalsIgnoreCase(value)) {
                reporter.report("VR offset resolution failed; activation was aborted before manager hand-off.\n");
                return;
            }
            long offset = Long.parseLong(value);
            if (offset > 0 && offset <= 0x100) {
                reporter.report("Resolved VR funcs offset: " + offset + " (0x"
                        + Long.toHexString(offset) + ").\n");
            }
        } catch (Exception exception) {
            reporter.report("Unable to read the resolved VR offset: " + exception.getMessage() + "\n");
        }
    }

    private static void stageBinary(File source, File destination) throws IOException {
        if (!source.isFile()) throw new IOException(source + " not found");
        try (InputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            input.transferTo(output);
        }
        if (!destination.setExecutable(true, false)) {
            throw new IOException("unable to mark " + destination + " executable");
        }
    }

    private static int runBinary(File bin, DirtyFragVrReporter reporter, int encapPort,
                                 int spi, byte[] aesKey, int senderPort,
                                 List<String> vrArgs) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(bin.getAbsolutePath());
        command.add("--encap-port");
        command.add(String.valueOf(encapPort));
        command.add("--sender-port");
        command.add(String.valueOf(senderPort));
        command.add("--spi");
        command.add(Integer.toUnsignedString(spi));
        command.add("--aes-key");
        command.add(hex(aesKey));
        command.addAll(vrArgs);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) {
                reporter.report(line + "\n");
            }
        }
        return process.waitFor();
    }

    private static String hex(byte[] value) {
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte item : value) out.append(String.format("%02x", item & 0xff));
        return out.toString();
    }
}
