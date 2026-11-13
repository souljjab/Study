package kr.souljjab.studyblocker

import org.junit.Assert.assertEquals
import org.junit.Test

class SecureFolderTest {
    private val self = "kr.souljjab.studyblocker"
    private val blocked = setOf("com.google.android.youtube")

    private fun verdict(pkg: String, sfBlock: Boolean = true, installedHere: Boolean = true) =
        SecureFolder.verdict(pkg, self, blocked, sfBlock, installedHere)

    @Test
    fun secureFolderScreenIsBlocked() {
        assertEquals(SecureFolder.SECURE_FOLDER, verdict(SecureFolder.PKG))
        // 스위치를 끄면 통과
        assertEquals(SecureFolder.PASS, verdict(SecureFolder.PKG, sfBlock = false))
    }

    @Test
    fun appOnlyInsideSecureFolderIsBlocked() {
        // 본체에 없는 앱의 화면이 떴다 = 보안 폴더 안의 앱
        assertEquals(SecureFolder.SECURE_FOLDER, verdict("com.some.browser", installedHere = false))
        assertEquals(SecureFolder.PASS, verdict("com.some.browser", sfBlock = false, installedHere = false))
    }

    @Test
    fun ordinaryAppsAreUnaffected() {
        assertEquals(SecureFolder.PASS, verdict("com.sec.android.app.launcher"))
        assertEquals(SecureFolder.PASS, verdict("com.android.systemui"))
        assertEquals(SecureFolder.BLOCKED_APP, verdict("com.google.android.youtube"))
        assertEquals(SecureFolder.BLOCKED_APP, verdict("com.google.android.youtube", sfBlock = false))
    }

    @Test
    fun ownScreensNeverBlocked() {
        assertEquals(SecureFolder.PASS, verdict(self, installedHere = false))
    }
}
