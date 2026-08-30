package com.frame.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateManagerTest {
    @Test
    fun `newer semantic release is available`() {
        assertTrue(isNewerVersion("1.10.0", "1.9.9"))
        assertFalse(isNewerVersion("1.2.0", "1.2.0"))
        assertFalse(isNewerVersion("1.2.0", "2.0.0"))
    }

    @Test
    fun `selects apk for the device ABI`() {
        val assets = listOf(
            ReleaseAsset("app-armeabi-v7a-release.apk", "arm32"),
            ReleaseAsset("app-arm64-v8a-release.apk", "arm64"),
        )

        assertEquals("arm64", selectApk(assets, "arm64-v8a")?.url)
        assertEquals(null, selectApk(assets, "x86_64"))
    }
}
