package app.eightcloud.gallery

import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.eightcloud.gallery.data.AppUpdate
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.FixMethodOrder

/**
 * On-device A->B update handoff fixture, fully isolated on the emulator:
 * CI serves the release manifests and candidate APKs (built with the stable key) from
 * a HOST-side HTTP server, bridged to the emulator's loopback via `adb reverse` - a
 * guest-side ServerSocket is not possible on the CI emulator (bind EPERMs even with
 * INTERNET granted). The real updater UI is driven through check -> download -> verify
 * -> system install sheet. Rejection paths (bad sha256, wrong signer, not newer, wrong
 * package) must refuse and delete the staged file.
 *
 * APK A = the installed app under test (versionCode 2, stable key).
 * b.apk = candidate B (versionCode 3, stable key); c.apk = B re-signed with a throwaway key.
 */
@RunWith(AndroidJUnit4::class)
// The A->B install replaces the app under instrumentation and kills its process;
// run it LAST so no later rule cleanup or test can hang on the dead activity.
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class UpdateFlowTest {

    private val composeRule = createAndroidComposeRule<MainActivity>()

    private val mediaPermissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(
            android.Manifest.permission.READ_MEDIA_IMAGES,
            android.Manifest.permission.READ_MEDIA_VIDEO
        ) else arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)

    @get:Rule
    val chain: TestRule = RuleChain
        .outerRule(GrantPermissionRule.grant(*mediaPermissions))
        .around(composeRule)

    private val evidenceDir = File("/sdcard/update-evidence")

    // Host-side fixture server (see the android-test CI job), bridged by adb reverse.
    private val fixtureBase = "http://127.0.0.1:8799"

    @Before
    fun setUp() {
        evidenceDir.mkdirs()
        AppUpdate.allowedApkUrlPrefixOverride = "$fixtureBase/"
    }

    @After
    fun tearDown() {
        AppUpdate.latestReleaseUrlOverride = null
        AppUpdate.allowedApkUrlPrefixOverride = null
    }

    /** Point the updater at one of the CI-staged release manifests (release-<scenario>.json). */
    private fun useFixture(scenario: String) {
        AppUpdate.latestReleaseUrlOverride = "$fixtureBase/release-$scenario.json"
    }

    private fun shot(name: String) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.takeScreenshot(File(evidenceDir, "$name.png"))
    }

    private fun waitAnyText(device: UiDevice, timeoutMs: Long, vararg texts: String): androidx.test.uiautomator.UiObject2? {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            for (t in texts) {
                device.findObject(By.text(t))?.let { return it }
            }
            Thread.sleep(250)
        }
        return null
    }

    private fun waitTag(tag: String, timeoutMs: Long = 30_000) {
        composeRule.waitUntil(timeoutMs) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitText(text: String, timeoutMs: Long = 30_000) {
        composeRule.waitUntil(timeoutMs) {
            composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun zz_last_installsVerifiedNewerApk() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val installedBefore = target.packageManager.getPackageInfo(target.packageName, 0).longVersionCode
        assertEquals("fixture A is versionCode 2", 2L, installedBefore)

        useFixture("good")

        // Grid banner proves silent once-per-process discovery found the update.
        waitText("Update available: 0.15.0")
        shot("grid-banner-available")

        composeRule.onNodeWithContentDescription("Settings").performClick()
        waitTag("update_download")
        shot("settings-update-available")
        composeRule.onNodeWithTag("update_download").performClick()
        waitTag("update_install")
        shot("settings-ready-to-install")
        composeRule.onNodeWithTag("update_install").performClick()

        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        // First update needs the one-time unknown-sources grant; the app then re-prompts
        // on resume (pendingGrantReturn).
        val allowSwitch = device.wait(Until.findObject(By.textContains("Allow from this source")), 5_000)
        if (allowSwitch != null) {
            allowSwitch.click()
            shot("unknown-sources-grant")
            device.pressBack()
        }
        // System installer button casing varies by API level ("Update" vs "UPDATE"),
        // so poll exact variants instead of relying on a regex selector.
        val sheet = waitAnyText(device, 15_000, "Update", "UPDATE")
        assertNotNull("system install sheet shows UPDATE", sheet)
        shot("system-install-sheet")
        sheet!!.click()
        val done = waitAnyText(device, 60_000, "Done", "DONE", "App updated", "APP UPDATED", "UPDATED")
        assertNotNull("install completed", done)
        shot("install-done")
        done!!.click()

        val installedAfter = target.packageManager.getPackageInfo(target.packageName, 0).longVersionCode
        assertEquals("A->B install handoff landed", 3L, installedAfter)
    }

    @Test
    fun rejectsBadSha256() {
        useFixture("badsha")
        val info = runBlocking { AppUpdate.fetchLatest() }
        assertNotNull(info)
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val staged = File(target.cacheDir, "updates/update.apk")
        // The digest is enforced inside download: a mismatch throws and the staged
        // file is deleted, so nothing unverifiable can ever reach the install sheet.
        val result = runBlocking {
            runCatching { AppUpdate.download(target, info!!) {} }
        }
        assertTrue("sha mismatch must abort the download", result.isFailure)
        assertFalse("mismatched file is deleted", staged.exists())
    }

    @Test
    fun rejectsWrongSigner() {
        useFixture("wrongsig")
        val info = runBlocking { AppUpdate.fetchLatest() }
        assertNotNull(info)
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val installed = target.packageManager.getPackageInfo(target.packageName, 0).longVersionCode
        val apk = runBlocking { AppUpdate.download(target, info!!) {} }
        try {
            assertFalse("throwaway cert must be rejected", AppUpdate.verifyApk(target, apk, installed))
        } finally {
            apk.delete()
        }
    }

    @Test
    fun rejectsNotNewer() {
        // Installed fixture A is versionCode 2; a manifest at 2 is never offered.
        useFixture("old")
        val info = runBlocking { AppUpdate.fetchLatest() }
        assertNotNull(info)
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val installed = target.packageManager.getPackageInfo(target.packageName, 0).longVersionCode
        assertFalse("same versionCode is not newer", AppUpdate.isNewer(info!!, installed))
    }

    @Test
    fun rejectsWrongPackage() {
        // The instrumentation APK itself: a real signed APK of a DIFFERENT package.
        val testApk = File(InstrumentationRegistry.getInstrumentation().context.packageCodePath)
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        assertFalse(
            "foreign package must be rejected",
            AppUpdate.verifyApk(target, testApk, 0),
        )
    }

}
