package app.eightcloud.gallery

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.content.ContentValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.eightcloud.gallery.ui.GalleryViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

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

    @Test
    fun appStarts_showsTitleAndGrid() {
        composeRule.onNodeWithText("8CloudGallery").assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText("No photos or videos yet")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun sortButtonOpensSortMenu() {
        composeRule.onNodeWithContentDescription("Sort").performClick()
        composeRule.waitUntil(timeoutMillis = 5000) {
            composeRule.onAllNodesWithText("Date (newest first)", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Size (largest first)", substring = true).assertIsDisplayed()
    }

    @Test
    fun viewerSwipesBetweenPhotos() {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val now = System.currentTimeMillis()
        val uriA = insertTestImage("SwipeA.png", now - 60_000)
        val uriB = insertTestImage("SwipeB.png", now)
        try {
            composeRule.activityRule.scenario.onActivity { activity ->
                ViewModelProvider(activity)[GalleryViewModel::class.java].loadMedia()
            }
            composeRule.waitUntil(timeoutMillis = 15000) {
                composeRule.onAllNodesWithContentDescription("SwipeB.png")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithContentDescription("SwipeB.png").performClick()
            composeRule.waitUntil(timeoutMillis = 5000) {
                composeRule.onAllNodesWithText("SwipeB.png").fetchSemanticsNodes().isNotEmpty()
            }
            // Swipe left on the photo: the pager must move to the next (older) image.
            composeRule.onNodeWithContentDescription("SwipeB.png")
                .performTouchInput { swipeLeft() }
            composeRule.waitUntil(timeoutMillis = 5000) {
                composeRule.onAllNodesWithText("SwipeA.png").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("SwipeA.png").assertIsDisplayed()
        } finally {
            resolver.delete(uriA, null, null)
            resolver.delete(uriB, null, null)
        }
    }

    private fun insertTestImage(displayName: String, dateTakenMs: Long): Uri {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.DATE_TAKEN, dateTakenMs)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { out ->
            val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(0xFF3366CC.toInt())
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }
}
