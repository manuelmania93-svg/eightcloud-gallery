package app.eightcloud.gallery.ui

data class FeatureToggle(val key: String, val title: String, val subtitle: String, val available: Boolean = true)

/** Optional features, each switchable in Settings. Default: on. */
val FEATURE_TOGGLES = listOf(
    FeatureToggle("feat_slideshow", "Slideshow", "Play your photos as a slideshow"),
    FeatureToggle("feat_on_this_day", "On this day", "Memories from past years"),
    FeatureToggle("feat_auto_albums", "Auto albums", "Screenshots, camera, videos, by month"),
    FeatureToggle("feat_map", "Map view", "See your photos on a map", available = false),
    FeatureToggle("feat_widget", "Photo of the day widget", "A random photo on your home screen"),
    FeatureToggle("feat_edit", "Basic editing", "Rotate, crop, brightness, filters", available = false),
    FeatureToggle("feat_secure_folder", "Secure folder", "Hide items behind the app lock"),
    FeatureToggle("feat_cloud_share", "Share from cloud", "Share the backed-up copy of an item"),
    FeatureToggle("feat_cleanup", "Swipe-to-sort cleanup", "Swipe left to trash, right to keep"),
    FeatureToggle("feat_storage_stats", "Storage stats", "Sizes per folder and biggest files"),
)
