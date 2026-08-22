package com.example.inuit.data.gen

/**
 * A wide, curated taxonomy of human knowledge — Inuit's analog of a topic
 * vector database. Inspired by the Serendipity Engine's Wikipedia-category
 * knowledge base: the wider the space we can name, the further away from
 * the user's recent questions we can steer question generation.
 *
 * Each entry maps a broad realm to concrete subrealms, deliberately mixing
 * canonical fields (Physics, History) with delightfully obscure ones
 * (Vexillology, Mycology, Horology) so the engine can consistently touch
 * territory far from anything recently asked.
 */
object RealmTaxonomy {

    /** Top-level realm → subrealms. Order is roughly classic → obscure. */
    val REALMS: Map<String, List<String>> = linkedMapOf(
        "Science > Physics" to listOf("Optics", "Acoustics", "Thermodynamics", "Particle Physics", "Relativity", "Fluid Dynamics"),
        "Science > Chemistry" to listOf("Periodic Table", "Everyday Chemistry", "Materials & Polymers", "Electrochemistry", "Biochemistry"),
        "Science > Biology" to listOf("Evolution", "Genetics", "Microbiology", "Ecology", "Botany", "Zoology"),
        "Mathematics" to listOf("Number Theory", "Geometry", "Probability", "Statistics", "Topology", "Recreational Math", "Logic & Paradoxes"),
        "History" to listOf("Ancient Civilizations", "Middle Ages", "Renaissance", "Industrial Revolution", "World Wars", "Cold War", "Decolonization"),
        "Geography" to listOf("Rivers & Lakes", "Mountains & Volcanoes", "Deserts & Savannas", "Islands & Oceans", "Borders & Enclaves", "Extreme Climates"),
        "Literature" to listOf("Classics & Epics", "Shakespeare", "Poetry Forms", "Fairy Tales & Folklore", "Modern Novels", "Literary Devices"),
        "Music" to listOf("Music Theory", "Classical Composition", "Opera", "Jazz & Blues", "Folk Traditions", "Musical Instruments", "Ethnomusicology"),
        "Visual Arts" to listOf("Painting Movements", "Sculpture", "Photography", "Color Theory", "Calligraphy", "Printmaking"),
        "Philosophy" to listOf("Logic", "Ethics", "Epistemology", "Metaphysics", "Stoicism", "Eastern Philosophy", "Aesthetics"),
        "Religion & Mythology" to listOf("Greek & Roman Myth", "Norse Myth", "Egyptian Myth", "World Religions", "Mysticism & Sufism", "Folk Beliefs"),
        "Language & Etymology" to listOf("Word Origins", "Idioms & Phrases", "Writing Systems", "Linguistics", "Endangered Languages", "Alphabets"),
        "Technology & Computing" to listOf("Computing History", "Programming Concepts", "Cryptography", "Networks", "Artificial Intelligence", "Robotics"),
        "Engineering" to listOf("Bridges & Tunnels", "Dams & Canals", "Materials Science", "Electrical Grids", "Manufacturing", "Standards & Tolerances"),
        "Medicine & Human Body" to listOf("Anatomy", "Immunology", "Neuroscience", "Pharmacology", "Sleep & Dreams", "Nutrition", "History of Medicine"),
        "Psychology & Mind" to listOf("Memory & Learning", "Cognitive Biases", "Perception", "Decision Making", "Emotions", "Developmental Psychology"),
        "Economics & Finance" to listOf("Macroeconomics", "Money & Banking", "Markets", "Behavioral Economics", "Trade & Commerce", "Currencies"),
        "Law & Politics" to listOf("Constitutions", "International Law", "Elections & Systems", "Rights & Liberties", "Diplomacy", "Political History"),
        "Sports & Games" to listOf("Olympic History", "Board Games & Strategy", "Card Games", "Chess", "Martial Arts", "Record Breakers"),
        "Food & Cooking" to listOf("Food Science", "Fermentation", "Cheese & Dairy", "Spices & Trade Routes", "Tea & Coffee", "Wine & Viticulture", "Bread & Grains"),
        "Everyday Statistics" to listOf("Risk & Chance", "Human Scale", "Population", "Energy & Consumption", "Transport Numbers"),
        "Nature & Animals" to listOf("Birds & Ornithology", "Insects & Entomology", "Fungi & Mycology", "Marine Life", "Animal Behavior", "Trees & Forests"),
        "Space & Astronomy" to listOf("Solar System", "Stars & Galaxies", "Cosmology", "Space Exploration", "History of Astronomy", "Navigation by Stars"),
        "Culture & Folklore" to listOf("Rituals & Celebrations", "Dance Forms", "Costume & Fashion", "Folk Music", "Storytelling Traditions", "Superstitions"),
        "Measurement & Units" to listOf("SI Units", "History of Measurement", "Time & Calendars", "Metrology", "Unusual Units"),
        "Inventions & Discoveries" to listOf("Everyday Inventions", "Accidental Discoveries", "Materials & Alloys", "Medical Breakthroughs", "Patent Stories"),
        // ── deliberately obscure territory below ───────────────────────────
        "Anthropology" to listOf("Kinship Systems", "Ritual & Rite of Passage", "Gift Economies", "Indigenous Knowledge", "Taboo & Custom"),
        "Archaeology" to listOf("Lost Cities", "Artifact Decipherment", "Fossils & Deep Time", "Underwater Archaeology", "Ice & Bog Finds"),
        "Earth Sciences" to listOf("Plate Tectonics", "Minerals & Gems", "Weather & Meteorology", "Ocean Currents", "Caves & Karst", "Paleoclimatology"),
        "Cartography" to listOf("Map Projections", "History of Maps", "Nautical Charts", "GPS & Geodesy", "Imaginary Places"),
        "Navigation & Seamanship" to listOf("Sailing History", "Lighthouses", "Piracy & Trade Routes", "Knots & Rigging", "Polar Exploration"),
        "Transport" to listOf("Railways", "Aviation History", "Ships & Shipbuilding", "Bridges of the World", "Urban Transit", "Roads & Routes"),
        "Architecture" to listOf("Architectural Styles", "Famous Buildings", "Sacred Architecture", "Urban Design", "Vernacular Building", "Skylines"),
        "Design & Craft" to listOf("Typography", "Industrial Design", "Furniture History", "Ceramics & Pottery", "Glassmaking", "Textiles & Weaving"),
        "Film & Cinema" to listOf("Cinema History", "Filmmaking Craft", "Animation", "Film Genres", "Famous Scenes"),
        "Theatre & Performance" to listOf("Theatre History", "Opera & Stage", "Circus Arts", "Puppetry", "Street Performance"),
        "Heraldry & Signs" to listOf("Heraldry", "Vexillology", "Symbols & Logos", "Signage & Pictograms", "Runes & Glyphs"),
        "Collecting & Catalogues" to listOf("Numismatics", "Philately", "Rare Books", "Museums & Conservation", "Libraries & Archives"),
        "Writing & Records" to listOf("History of Writing", "Manuscripts & Paleography", "Printing & Presses", "Ink & Pigments", "Codes & Ciphers"),
        "Timekeeping" to listOf("Horology", "Clocks & Watchmaking", "Calendars of the World", "Sundials", "Standard Time"),
        "Matter & Light" to listOf("Glass & Optics", "Sound & Acoustics of Spaces", "Color Science", "Radio & Waves", "Electricity History"),
        "Agriculture" to listOf("Crop History", "Domestication", "Soil & Irrigation", "Farming Traditions", "Bees & Pollination"),
        "Craft of Drink" to listOf("Brewing", "Distillation", "Vineyards & Terroir", "Coffee Culture", "Tea Traditions"),
        "Mountains & Exploration" to listOf("Mountaineering History", "Maps of Exploration", "Desert Crossings", "Expedition Lore", "Rivers & Sources"),
        "Everyday Engineering" to listOf("Locks & Keys", "Fasteners & Joints", "Paper & Packaging", "Batteries", "Pumps & Pipes"),
        "Mind & Skill" to listOf("Expertise & Intuition", "Speed of Thought", "Attention & Focus", "Habit & Practice", "Learning Science"),
        "Society & Numbers" to listOf("Demography", "Urbanization", "Migration", "Literacy & Education", "Longevity & Health Span"),
        "Belief & Practice" to listOf("Pilgrimage", "Monasticism", "Festivals & Calendars", "Divination History", "Alchemy & Proto-Science"),
        "Rhetoric & Debate" to listOf("Fallacies", "Great Speeches", "Argument Forms", "Propaganda History", "Negotiation"),
        "Games of Chance" to listOf("Gambling Math", "Lotteries", "Card Probabilities", "Puzzles & Riddles", "Game Theory"),
        "Boundaries" to listOf("Time Zones", "Meridians & Poles", "International Date Line", "Borders & Walls", "Territorial Oddities")
    )

    /** All realm → subrealm paths, e.g. "Science > Physics > Optics". */
    val ALL_PATHS: List<String> by lazy {
        REALMS.flatMap { (realm, subs) -> subs.map { "$realm > $it" } }
    }

    /** All top-level realms (without subrealms). */
    val ALL_REALMS: List<String> = REALMS.keys.toList()

    /** Normalizes a path's segments for comparison ("science > physics"). */
    fun segments(path: String): List<String> =
        path.split(">").map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    /** Top-level realm of a path, or null when malformed. */
    fun topRealm(path: String): String? = segments(path).firstOrNull()
}
