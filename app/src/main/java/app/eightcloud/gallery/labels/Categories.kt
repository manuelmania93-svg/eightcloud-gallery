package app.eightcloud.gallery.labels

/**
 * Maps on-device analysis (faces + ML Kit labels with confidence + OCR +
 * file facts) to the smart-album categories. Pure functions, unit-testable.
 *
 * Accuracy rules (v0.9.0):
 * - Per-category confidence thresholds; weak labels alone don't qualify.
 * - Scene categories need one strong label or two medium ones, and never
 *   fire on screenshots (a screenshot of a forest is not a nature photo).
 * - People needs a face of meaningful size (no tiny background faces).
 * - Documents/Memes use real OCR text, not label-name guesses.
 */
object Categories {

    const val PEOPLE = "cat_people"
    const val DOCUMENTS = "cat_documents"
    const val MEMES = "cat_memes"
    const val OUTDOOR = "cat_outdoor"
    const val FOOD = "cat_food"
    const val ANIMALS = "cat_animals"
    const val CARS = "cat_cars"
    const val NIGHT = "cat_night"
    const val BEACH = "cat_beach"
    const val ADULT = "cat_adult"

    val ALL = listOf(PEOPLE, DOCUMENTS, MEMES, OUTDOOR, FOOD, ANIMALS, CARS, NIGHT, BEACH, ADULT)

    const val NSFW_THRESHOLD = 0.7f

    // People: one face covering >= 1% of the image, or 2+ faces with the
    // largest >= 0.3%.
    private const val FACE_RATIO_SINGLE = 0.01f
    private const val FACE_RATIO_GROUP = 0.003f

    // Documents: real OCR text on a camera photo.
    private const val DOC_MIN_OCR_CHARS = 150

    // Memes: screenshot carrying real text.
    private const val MEME_MIN_OCR_CHARS = 30

    fun title(key: String): String = when (key) {
        PEOPLE -> "People"
        DOCUMENTS -> "Documents"
        MEMES -> "Memes"
        OUTDOOR -> "Outdoor & nature"
        FOOD -> "Food"
        ANIMALS -> "Animals"
        CARS -> "Cars"
        NIGHT -> "Night"
        BEACH -> "Beach"
        ADULT -> "Sensitive"
        else -> key
    }

    private val OUTDOOR_LABELS = setOf(
        "Sky", "Tree", "Plant", "Flower", "Mountain", "Grass", "Forest", "Lake",
        "River", "Landscape", "Nature", "Cloud", "Sunset", "Sunrise", "Hiking"
    )
    private val FOOD_LABELS = setOf("Food", "Dish", "Cuisine", "Dessert", "Drink", "Beverage", "Meal")
    private val ANIMAL_LABELS = setOf(
        "Animal", "Dog", "Cat", "Bird", "Horse", "Wildlife", "Pet", "Fish", "Insect"
    )
    private val CAR_LABELS = setOf("Car", "Vehicle", "Automotive", "Motorcycle", "Truck", "Bicycle")
    private val NIGHT_LABELS = setOf("Night", "Midnight", "Darkness")
    private val BEACH_LABELS = setOf("Beach", "Sea", "Ocean", "Sand", "Coast", "Wave")
    /** Parses the stored "label:confidence" csv into pairs. */
    fun parseConfs(labelConfs: String): List<Pair<String, Float>> =
        labelConfs.split(',').mapNotNull { part ->
            val i = part.lastIndexOf(':')
            if (i <= 0) return@mapNotNull null
            val conf = part.substring(i + 1).toFloatOrNull() ?: return@mapNotNull null
            part.substring(0, i) to conf
        }

    /** One strong (>= strong) hit, or at least two medium (>= medium) hits. */
    private fun voted(confs: List<Pair<String, Float>>, vocab: Set<String>, strong: Float, medium: Float): Boolean {
        val hits = confs.filter { it.first in vocab }
        return hits.any { it.second >= strong } || hits.count { it.second >= medium } >= 2
    }

    /** Categories for one item. Old rows (scanVersion 1) have no confs/OCR and fall back to label names only. */
    fun forItem(
        faceCount: Int,
        faceMaxRatio: Float,
        labelConfs: String,
        ocrChars: Int,
        nsfwScore: Float,
        isScreenshot: Boolean,
        cameraBucket: Boolean
    ): Set<String> {
        val confs = parseConfs(labelConfs)
        val out = mutableSetOf<String>()

        // People: meaningful faces only. Legacy rows (no ratio stored yet)
        // keep the old behavior until their lazy rescan.
        if (faceMaxRatio >= FACE_RATIO_SINGLE ||
            (faceCount >= 2 && faceMaxRatio >= FACE_RATIO_GROUP) ||
            (faceMaxRatio == 0f && faceCount > 0)
        ) out += PEOPLE

        if (nsfwScore >= NSFW_THRESHOLD) out += ADULT

        if (!isScreenshot) {
            if (voted(confs, OUTDOOR_LABELS, 0.75f, 0.6f)) out += OUTDOOR
            if (voted(confs, FOOD_LABELS, 0.8f, 0.65f)) out += FOOD
            if (voted(confs, ANIMAL_LABELS, 0.8f, 0.65f)) out += ANIMALS
            if (voted(confs, CAR_LABELS, 0.8f, 0.65f)) out += CARS
            if (voted(confs, NIGHT_LABELS, 0.85f, 0.7f)) out += NIGHT
            if (voted(confs, BEACH_LABELS, 0.75f, 0.6f)) out += BEACH
        }

        // Documents: a camera photo with lots of real OCR-verified text.
        if (cameraBucket && ocrChars >= DOC_MIN_OCR_CHARS) out += DOCUMENTS

        // Memes: screenshots carrying real text (posts, chat screenshots).
        if (isScreenshot && ocrChars >= MEME_MIN_OCR_CHARS) out += MEMES

        return out
    }
}
