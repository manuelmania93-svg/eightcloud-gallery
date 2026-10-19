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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.longClick
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
        composeRule.onNodeWithText("Sort: Date", substring = true).performClick()
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

    @Test
    fun statusFilterChips() {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val uri = insertTestImage("FilterA.png", System.currentTimeMillis())
        try {
            composeRule.activityRule.scenario.onActivity { activity ->
                ViewModelProvider(activity)[GalleryViewModel::class.java].loadMedia()
            }
            composeRule.waitUntil(timeoutMillis = 15000) {
                composeRule.onAllNodesWithContentDescription("FilterA.png")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            // Fresh image is not backed up: visible under Pending, hidden under Backed up.
            composeRule.onNodeWithText("Pending", substring = true).performClick()
            composeRule.waitUntil(timeoutMillis = 5000) {
                composeRule.onAllNodesWithContentDescription("FilterA.png")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("Backed up", substring = true).performClick()
            composeRule.waitUntil(timeoutMillis = 5000) {
                composeRule.onAllNodesWithText("Nothing in this section")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("All", substring = true).performClick()
            composeRule.waitUntil(timeoutMillis = 5000) {
                composeRule.onAllNodesWithContentDescription("FilterA.png")
                    .fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    @Test
    fun longPressSelectsPhoto() {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val uri = insertTestImage("SelectA.png", System.currentTimeMillis())
        try {
            composeRule.activityRule.scenario.onActivity { activity ->
                ViewModelProvider(activity)[GalleryViewModel::class.java].loadMedia()
            }
            composeRule.waitUntil(timeoutMillis = 15000) {
                composeRule.onAllNodesWithContentDescription("SelectA.png")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithContentDescription("SelectA.png")
                .performTouchInput { longClick() }
            composeRule.waitUntil(timeoutMillis = 5000) {
                composeRule.onAllNodesWithText("1 selected")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("1 selected").assertIsDisplayed()
        } finally {
            resolver.delete(uri, null, null)
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

    @Test
    fun viewerCloseRestoresGridPosition() {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val now = System.currentTimeMillis()
        val uris = (0 until 40).map { i ->
            insertTestImage("Anchor%02d.png".format(i), now - i * 60_000L)
        }
        try {
            composeRule.activityRule.scenario.onActivity { activity ->
                ViewModelProvider(activity)[GalleryViewModel::class.java].loadMedia()
            }
            composeRule.waitUntil(timeoutMillis = 15000) {
                composeRule.onAllNodesWithContentDescription("Anchor00.png")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            // Scroll far down and open an item that is off the first screen.
            composeRule.onNodeWithTag("mediaGrid").performScrollToIndex(30)
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("Anchor30.png").performClick()
            composeRule.waitUntil(timeoutMillis = 5000) {
                composeRule.onAllNodesWithText("Anchor30.png").fetchSemanticsNodes().isNotEmpty()
            }
            // Close the viewer; the grid must come back at the same item,
            // not scrolled to the top.
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                composeRule.activity.onBackPressedDispatcher.onBackPressed()
            }
            composeRule.waitUntil(timeoutMillis = 5000) {
                composeRule.onAllNodesWithContentDescription("Anchor30.png")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithContentDescription("Anchor30.png").assertIsDisplayed()
        } finally {
            uris.forEach { resolver.delete(it, null, null) }
        }
    }

    @Test
    fun viewerOpensVideoItemWithoutCrashing() {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "test-video-8cg.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.Video.Media.DURATION, 1000L)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { out ->
            // Dummy payload: ExoPlayer must reject it and the viewer must show
            // its error state instead of crashing on a black screen.
            out.write(byteArrayOf(0, 0, 0, 24, 0x66, 0x74, 0x79, 0x70))
        }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        try {
            composeRule.activityRule.scenario.onActivity { activity ->
                ViewModelProvider(activity)[GalleryViewModel::class.java].loadMedia()
            }
            composeRule.waitUntil(timeoutMillis = 15000) {
                composeRule.onAllNodesWithContentDescription("test-video-8cg.mp4")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithContentDescription("test-video-8cg.mp4").performClick()
            composeRule.waitUntil(timeoutMillis = 20000) {
                val hasPlayer =
                    composeRule.onAllNodesWithContentDescription("Mute").fetchSemanticsNodes().isNotEmpty() ||
                    composeRule.onAllNodesWithContentDescription("Unmute").fetchSemanticsNodes().isNotEmpty()
                val hasError =
                    composeRule.onAllNodesWithText("can't be played", substring = true)
                        .fetchSemanticsNodes().isNotEmpty()
                hasPlayer || hasError
            }
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    @Test
    fun gridScrollsWith41kSyntheticItems() {
        val vm = ViewModelProvider(composeRule.activity)[GalleryViewModel::class.java]
        composeRule.waitUntil(timeoutMillis = 20000) { !vm.loading.value }
        composeRule.runOnIdle { vm.loadSyntheticForTest(41_052) }
        composeRule.waitUntil(timeoutMillis = 20000) { vm.items.value.size == 41_052 }
        composeRule.onNodeWithTag("mediaGrid").assertIsDisplayed()
        composeRule.onNodeWithTag("mediaGrid").performScrollToIndex(41_000)
        composeRule.waitForIdle()
    }

    @Test
    fun sliderDragAcross41kItemsDoesNotCrash() {
        val vm = ViewModelProvider(composeRule.activity)[GalleryViewModel::class.java]
        composeRule.waitUntil(timeoutMillis = 20000) { !vm.loading.value }
        composeRule.runOnIdle { vm.loadSyntheticForTest(41_052) }
        composeRule.waitUntil(timeoutMillis = 20000) { vm.items.value.size == 41_052 }
        val strip = composeRule.onNodeWithTag("fastScrollStrip")
        strip.assertIsDisplayed()
        // Scrub the whole library like a user dragging the slider: any
        // exception in the drag -> scrollToItem -> relayout path fails here.
        strip.performTouchInput {
            down(centerRight.copy(y = top + 10))
            var y = top + 10
            while (y < bottom - 10) {
                y += 120
                moveTo(centerRight.copy(y = y.coerceAtMost(bottom - 10)), delayMillis = 16)
            }
            up()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("mediaGrid").assertIsDisplayed()
        strip.performTouchInput {
            down(centerRight.copy(y = bottom - 10))
            var y = bottom - 10
            while (y > top + 10) {
                y -= 240
                moveTo(centerRight.copy(y = y.coerceAtLeast(top + 10)), delayMillis = 16)
            }
            up()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("mediaGrid").assertIsDisplayed()
    }
}
