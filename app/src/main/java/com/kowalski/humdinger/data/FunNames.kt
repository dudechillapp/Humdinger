package com.kowalski.humdinger.data

/**
 * Default names for guests: a friendly adjective + an animal or a funny object,
 * e.g. "Springy Toast" or "Lazy Frog". Add to either list whenever you like —
 * just keep words short (the full name must stay within 24 characters) and kind.
 */
object FunNames {
    private val adjectives = listOf(
        "Springy", "Lazy", "Sleepy", "Bouncy", "Fuzzy", "Wobbly", "Sneaky", "Jolly",
        "Soggy", "Crispy", "Cosmic", "Giddy", "Snazzy", "Breezy", "Cheeky", "Dizzy",
        "Fluffy", "Funky", "Jazzy", "Zesty", "Toasty", "Squishy", "Sparkly", "Merry",
        "Peppy", "Plucky", "Quirky", "Shiny", "Sunny", "Tiny", "Mighty", "Wiggly",
        "Zippy", "Chirpy", "Dapper", "Groovy", "Nifty", "Humble", "Brave", "Clumsy",
        "Curious", "Dreamy", "Fancy", "Gentle", "Happy", "Lucky", "Noisy", "Rusty",
        "Bubbly", "Chunky", "Cuddly", "Dainty", "Fizzy", "Frosty", "Gooey", "Grumpy",
        "Jumpy", "Loopy", "Mellow", "Perky", "Plump", "Rowdy", "Sassy", "Silly",
        "Snappy", "Spiffy", "Chipper", "Twinkly", "Wacky", "Whimsy", "Zany", "Crunchy",
    )

    private val nouns = listOf(
        // animals
        "Frog", "Otter", "Llama", "Penguin", "Walrus", "Badger", "Hedgehog", "Goose",
        "Puffin", "Sloth", "Panda", "Wombat", "Flamingo", "Octopus", "Lobster", "Meerkat",
        "Alpaca", "Moose", "Beaver", "Squid", "Hamster", "Narwhal", "Koala", "Pigeon",
        // funny objects
        "Toast", "Pickle", "Noodle", "Teapot", "Crumpet", "Waffle", "Muffin", "Kettle",
        "Sprout", "Potato", "Pancake", "Biscuit", "Parsnip", "Trombone", "Kazoo", "Banjo",
        "Tuba", "Cabbage", "Nugget", "Dumpling", "Teacup", "Sock", "Welly", "Jellybean",
        // more animals
        "Toucan", "Gecko", "Lemur", "Ferret", "Axolotl", "Capybara", "Duckling", "Hippo",
        "Kiwi", "Owl", "Seal", "Yak",
        // more funny objects
        "Bagel", "Crouton", "Scone", "Pretzel", "Turnip", "Ukulele", "Maraca", "Bongo",
        "Slipper", "Mitten", "Doughnut", "Pudding",
    )

    /**
     * A random name. There are about 5,000 word pairs; once a few tries have clashed with
     * names already in use, a number is added ("Lazy Frog 427"), giving millions more.
     * Names are unique: the app checks, and the database refuses a name that's taken.
     */
    fun random(attempt: Int = 0): String {
        val base = "${adjectives.random()} ${nouns.random()}"
        return if (attempt < 4) base else "$base ${(2..999).random()}"
    }

    /** The unique key for a name: "Lazy Frog 42" → "lazy_frog_42". */
    fun key(name: String): String = name.lowercase().replace(' ', '_')
}
