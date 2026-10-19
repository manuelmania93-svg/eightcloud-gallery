package app.eightcloud.gallery.labels

/**
 * Maps on-device analysis (face count + ML Kit labels + file facts) to the
 * smart-album categories. Pure functions so they are unit-testable.
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
    private val DOCUMENT_LABELS = setOf(
        "Text", "Document", "Paper", "Handwriting", "Whiteboard", "Receipt", "Page", "Font"
    )

    /** Categories for one item. labels = ML Kit label texts; isScreenshot from the bucket name. */
    fun forItem(
        faceCount: Int,
        labels: Set<String>,
        nsfwScore: Float,
        isScreenshot: Boolean,
        cameraBucket: Boolean
    ): Set<String> {
        val out = mutableSetOf<String>()
        if (faceCount > 0) out += PEOPLE
        if (nsfwScore >= 0.7f) out += ADULT
        if (labels.any { it in OUTDOOR_LABELS }) out += OUTDOOR
        if (labels.any { it in FOOD_LABELS }) out += FOOD
        if (labels.any { it in ANIMAL_LABELS }) out += ANIMALS
        if (labels.any { it in CAR_LABELS }) out += CARS
        if (labels.any { it in NIGHT_LABELS }) out += NIGHT
        if (labels.any { it in BEACH_LABELS }) out += BEACH
        val docHits = labels.count { it in DOCUMENT_LABELS }
        // A photo of a document: several text-ish labels and not a screenshot.
        if (docHits >= 2 || (docHits >= 1 && cameraBucket && "Text" in labels)) out += DOCUMENTS
        // Memes: screenshots with lots of text but no camera EXIF typical photo labels.
        if (isScreenshot && docHits >= 1) out += MEMES
        return out
    }
}
