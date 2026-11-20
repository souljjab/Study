package kr.souljjab.studyblocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test
    fun parsesAndroidSection() {
        val r = Updater.parse(
            """{"windows":{"version":"2.1.0"},"android":{"versionCode":4,"versionName":"2.2",
               "url":"https://raw.githubusercontent.com/souljjab/Study/HEAD/dist/StudyBlocker.apk",
               "sha256":"ABCDEF0123456789abcdef0123456789abcdef0123456789abcdef0123456789","size":1234,"notes":"버그 수정"}}""",
        )!!
        assertEquals(4, r.versionCode)
        assertEquals("2.2", r.versionName)
        assertEquals(1234L, r.size)
        assertEquals("abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", r.sha256)
        assertEquals("버그 수정", r.notes)
        assertNull(Updater.parse("""{"windows":{}}"""))
        assertNull(Updater.parse("not json"))
    }

    @Test
    fun onlyDownloadsFromGitHubOverHttps() {
        assertTrue(Updater.allowedUrl("https://raw.githubusercontent.com/souljjab/Study/HEAD/dist/StudyBlocker.apk"))
        assertFalse(Updater.allowedUrl("http://raw.githubusercontent.com/x.apk"))
        assertFalse(Updater.allowedUrl("https://evil.example.com/x.apk"))
        assertFalse(Updater.allowedUrl("https://raw.githubusercontent.com.evil.com/x.apk"))
    }

    @Test
    fun sha256() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Updater.sha256Hex("".byteInputStream()),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Updater.sha256Hex("abc".byteInputStream()),
        )
    }

    @Test
    fun statusTexts() {
        assertEquals("새 버전 2.2을 받아 뒀어요", updateStatus(UpdateState(state = "ready", latest = "2.2")))
        assertEquals("확인하지 못했어요: 오프라인", updateStatus(UpdateState(state = "error", error = "오프라인")))
    }
}
