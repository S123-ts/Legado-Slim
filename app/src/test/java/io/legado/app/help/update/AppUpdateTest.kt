package io.legado.app.help.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {

    @Test
    fun normalizeAddsSchemeAndTrimsSlash() {
        assertEquals("http://192.168.1.10:8765", AppUpdate.normalizeServerUrl("192.168.1.10:8765"))
        assertEquals("http://192.168.1.10:8765", AppUpdate.normalizeServerUrl("http://192.168.1.10:8765/"))
        assertEquals(
            "http://192.168.1.10:8765",
            AppUpdate.normalizeServerUrl("  http://192.168.1.10:8765/update.json  ")
        )
    }

    @Test
    fun normalizeKeepsPathPrefix() {
        assertEquals(
            "http://192.168.1.10:8765/legado",
            AppUpdate.normalizeServerUrl("http://192.168.1.10:8765/legado/")
        )
    }

    @Test
    fun normalizeRejectsBlank() {
        assertNull(AppUpdate.normalizeServerUrl(null))
        assertNull(AppUpdate.normalizeServerUrl(""))
        assertNull(AppUpdate.normalizeServerUrl("   "))
    }

    @Test
    fun normalizeGitHubRepoAcceptsCommonShapes() {
        assertEquals("S123-ts/Legado-Slim", AppUpdate.normalizeGitHubRepo("S123-ts/Legado-Slim"))
        assertEquals(
            "S123-ts/Legado-Slim",
            AppUpdate.normalizeGitHubRepo("https://github.com/S123-ts/Legado-Slim")
        )
        assertEquals(
            "S123-ts/Legado-Slim",
            AppUpdate.normalizeGitHubRepo("https://github.com/S123-ts/Legado-Slim.git")
        )
        assertEquals(
            "S123-ts/Legado-Slim",
            AppUpdate.normalizeGitHubRepo("github.com/S123-ts/Legado-Slim/")
        )
        assertEquals(
            "S123-ts/Legado-Slim",
            AppUpdate.normalizeGitHubRepo("https://github.com/S123-ts/Legado-Slim/releases/latest")
        )
    }

    @Test
    fun normalizeGitHubRepoRejectsGarbage() {
        assertNull(AppUpdate.normalizeGitHubRepo(null))
        assertNull(AppUpdate.normalizeGitHubRepo(""))
        assertNull(AppUpdate.normalizeGitHubRepo("abc"))
        assertNull(AppUpdate.normalizeGitHubRepo("a/b/c"))
        assertNull(AppUpdate.normalizeGitHubRepo("https://github.com/only-owner"))
    }

    @Test
    fun githubBaseUrlPointsToLatestReleaseAssets() {
        assertEquals(
            "https://github.com/S123-ts/Legado-Slim/releases/latest/download",
            AppUpdate.githubBaseUrl("S123-ts/Legado-Slim")
        )
        assertEquals(
            "https://github.com/S123-ts/Legado-Slim/releases/latest/download/update.json",
            AppUpdate.manifestUrl(AppUpdate.githubBaseUrl("S123-ts/Legado-Slim"))
        )
    }

    @Test
    fun manifestAndApkUrlJoinBase() {
        val manifest = AppUpdate.ReleaseManifest(
            versionName = "3.26111522",
            versionCode = 38501,
            apkName = "legado_3.26111522.apk"
        )
        assertEquals(
            "https://github.com/S123-ts/Legado-Slim/releases/latest/download/legado_3.26111522.apk",
            AppUpdate.apkUrl(AppUpdate.githubBaseUrl("S123-ts/Legado-Slim"), manifest)
        )
        assertEquals(
            "http://192.168.1.10:8765/legado_3.26111522.apk",
            AppUpdate.apkUrl("http://192.168.1.10:8765", manifest)
        )
    }

    @Test
    fun apkUrlPrefersAbsoluteAddress() {
        val manifest = AppUpdate.ReleaseManifest(
            apkName = "legado_3.26111522.apk",
            apkUrl = "http://10.0.0.2:9000/a.apk"
        )
        assertEquals(
            "http://10.0.0.2:9000/a.apk",
            AppUpdate.apkUrl("http://192.168.1.10:8765", manifest)
        )
    }

    @Test
    fun compareVersionNameComparesNumericParts() {
        assertEquals(1, compareVersionName("3.26111522", "3.26111410"))
        assertEquals(-1, compareVersionName("3.26111410", "3.26111522"))
        assertEquals(0, compareVersionName("3.26111522", "3.26111522"))
        assertEquals(1, compareVersionName("3.26111522", "3"))
    }

    @Test
    fun versionCodeWinsWhenBothPresent() {
        assertTrue(
            isNewerVersion(
                remoteVersionCode = 38501,
                remoteVersionName = "3.26111522",
                localVersionCode = 38500,
                localVersionName = "3.26111315"
            )
        )
        assertFalse(
            isNewerVersion(
                remoteVersionCode = 38500,
                remoteVersionName = "3.26111522",
                localVersionCode = 38501,
                localVersionName = "3.26111315"
            )
        )
        assertFalse(
            isNewerVersion(
                remoteVersionCode = 38501,
                remoteVersionName = "3.26111522",
                localVersionCode = 38501,
                localVersionName = "3.26111522"
            )
        )
    }

    @Test
    fun fallsBackToVersionNameWithoutVersionCode() {
        assertTrue(
            isNewerVersion(
                remoteVersionCode = 0L,
                remoteVersionName = "3.26111522",
                localVersionCode = 38501,
                localVersionName = "3.26111315"
            )
        )
        assertFalse(
            isNewerVersion(
                remoteVersionCode = 0L,
                remoteVersionName = "3.26111315",
                localVersionCode = 38501,
                localVersionName = "3.26111522"
            )
        )
    }
}
