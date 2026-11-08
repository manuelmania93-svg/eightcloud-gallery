package app.eightcloud.gallery.installcontroller

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * External install controller: runs in its OWN instrumentation (this package, not the
 * gallery app), so it survives the A->B package replacement that kills the app's
 * process. The app-side UpdateFlowTest stops at the system install sheet; this
 * controller owns everything after that handoff and independently asserts the actual
 * replacement from PackageManager state.
 */
@RunWith(AndroidJUnit4::class)
class InstallControllerTest {

    private val evidenceDir = File("/sdcard/update-evidence")
    private val targetPackage = "app.eightcloud.gallery.debug"

    @Test
    fun drivesSystemSheetAndVerifiesReplacement() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        evidenceDir.mkdirs()

        // The app-side flow left the system install sheet on screen. If the sheet is
        // gone (e.g. the app process was reaped), fail loudly instead of pretending.
        // Pixel evidence (run 42455960836): this API 29 image labels the button INSTALL
        // even for updates - "Do you want to install an update to this existing
        // application?" with CANCEL / INSTALL.
        val sheet = waitAnyText(device, 30_000, "Update", "UPDATE", "Install", "INSTALL")
        assertNotNull("system install sheet still present for the controller", sheet)
        device.takeScreenshot(File(evidenceDir, "controller-install-sheet.png"))
        sheet!!.click()

        // Casing and wording vary by API level; poll the known completion labels.
        val done = waitAnyText(device, 90_000, "Done", "DONE", "App updated", "APP UPDATED", "UPDATED", "App installed", "APP INSTALLED", "INSTALLED")
        assertNotNull("system installer reported completion", done)
        device.takeScreenshot(File(evidenceDir, "install-done.png"))
        done!!.click()

        // Independent post-replacement verification from this process, not the app's.
        val pm = instrumentation.context.packageManager
        val info = pm.getPackageInfo(targetPackage, PackageManager.GET_SIGNING_CERTIFICATES)
        assertEquals("candidate B replaced fixture A", 3L, info.longVersionCode)

        val cert = info.signingInfo.apkContentsSigners.first()
        val hex = MessageDigest.getInstance("SHA-256").digest(cert.toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(
            "updated app keeps the stable signing identity",
            "501ffd102c711f96d8c3e196225167f14b58276c6e7fb462b58f301b2cc493e1",
            hex,
        )
        device.takeScreenshot(File(evidenceDir, "controller-after-done.png"))
    }

    private fun waitAnyText(device: UiDevice, timeoutMs: Long, vararg texts: String): UiObject2? {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            for (t in texts) {
                device.findObject(By.text(t))?.let { return it }
            }
            Thread.sleep(250)
        }
        return null
    }
}
