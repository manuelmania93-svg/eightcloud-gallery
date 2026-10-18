package app.eightcloud.gallery

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.os.Build
import androidx.test.rule.GrantPermissionRule
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
}
