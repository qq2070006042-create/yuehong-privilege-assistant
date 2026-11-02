package com.GhostKey.app

import com.GhostKey.app.device.DeviceBinding
import com.GhostKey.app.device.DeviceBinding.KernelSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * CVE-2026-98260 内核影响范围判定测试。
 *
 * 官方范围（奇安信 CERT / kernel.org stable，2026-10）：
 *  - 自 Linux 5.7 引入；修复于 6.6.158 / 6.12.112 / 6.18.54 / 7.2.8 / 7.3-rc4+。
 *  - 5.10 / 5.15 / 6.1 等分支未见修复 → 仍受影响。
 */
class KernelRangeTest {

    private fun supportOf(release: String): KernelSupport = DeviceBinding.kernelSupportOf(release)

    @Test
    fun `真实 Android GKI 内核都在影响范围内`() {
        assertEquals(KernelSupport.IN_RANGE, supportOf("6.6.118-android15-8-g9bc34d5b0c79-abogki537459655-4k"))
        assertEquals(KernelSupport.IN_RANGE, supportOf("6.6.75-android15-8-gc4127a25dcf3-abogki"))
        assertEquals(KernelSupport.IN_RANGE, supportOf("5.15.41-android13-8-gabc"))
        assertEquals(KernelSupport.IN_RANGE, supportOf("5.10.157-android12-9-00001-gabc"))
        assertEquals(KernelSupport.IN_RANGE, supportOf("6.1.99-android14-11"))
        assertEquals(KernelSupport.IN_RANGE, supportOf("6.18.21-android17-5"))
    }

    @Test
    fun `已修复的 stable 版本不在影响范围内`() {
        assertEquals(KernelSupport.OUT_OF_RANGE, supportOf("6.6.158-android15-8"))
        assertEquals(KernelSupport.OUT_OF_RANGE, supportOf("6.6.200"))
        assertEquals(KernelSupport.OUT_OF_RANGE, supportOf("6.12.112"))
        assertEquals(KernelSupport.OUT_OF_RANGE, supportOf("6.18.54"))
        assertEquals(KernelSupport.OUT_OF_RANGE, supportOf("7.2.8"))
        assertEquals(KernelSupport.OUT_OF_RANGE, supportOf("7.3.0"))
        assertEquals(KernelSupport.OUT_OF_RANGE, supportOf("7.4.1"))
    }

    @Test
    fun `低于 5-7 的内核不受影响`() {
        assertEquals(KernelSupport.OUT_OF_RANGE, supportOf("5.4.210"))
        assertEquals(KernelSupport.OUT_OF_RANGE, supportOf("4.19.300-gabc"))
        // 边界：5.7.0 起受影响
        assertEquals(KernelSupport.IN_RANGE, supportOf("5.7.0"))
    }

    @Test
    fun `未修复分支的高补丁号仍在范围内`() {
        // 6.6 分支只有 ≥158 修复；118 仍受影响（真实测试机型）
        assertEquals(KernelSupport.IN_RANGE, supportOf("6.6.157"))
        assertEquals(KernelSupport.IN_RANGE, supportOf("6.6.118"))
        assertEquals(KernelSupport.OUT_OF_RANGE, supportOf("6.6.159"))
        // 6.12 分支 ≥112 修复
        assertEquals(KernelSupport.IN_RANGE, supportOf("6.12.111"))
        assertEquals(KernelSupport.OUT_OF_RANGE, supportOf("6.12.112"))
    }

    @Test
    fun `模拟器与不可读版本保持 UNKNOWN`() {
        assertEquals(KernelSupport.UNKNOWN, supportOf("unknown"))
        assertEquals(KernelSupport.UNKNOWN, supportOf(""))
        assertEquals(KernelSupport.UNKNOWN, supportOf("6.1.9-generic"))
        assertEquals(KernelSupport.UNKNOWN, supportOf("5.15.0-android13-8-emulator"))
    }

    @Test
    fun `版本号解析`() {
        assertEquals(DeviceBinding.KernelVer(6, 6, 118), DeviceBinding.parseKernelVer("6.6.118-android15-8-gabc"))
        assertEquals(DeviceBinding.KernelVer(5, 15, 41), DeviceBinding.parseKernelVer("5.15.41-android13-8"))
        assertNull(DeviceBinding.parseKernelVer("unknown"))
        assertNull(DeviceBinding.parseKernelVer("abc-def"))
    }
}
