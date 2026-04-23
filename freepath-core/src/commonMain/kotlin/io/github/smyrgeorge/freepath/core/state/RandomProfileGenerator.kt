package io.github.smyrgeorge.freepath.core.state

object RandomProfileGenerator {
    data class DefaultProfile(
        val name: String,
        val bio: String,
        val location: String
    )

    private val profiles = listOf(
        DefaultProfile("Agile Axolotl", "Moves through shadows unseen", "Ashenvale"),
        DefaultProfile("Brave Basilisk", "Faces the unknown boldly", "Brimswick"),
        DefaultProfile("Cosmic Capybara", "Drifts between stars peacefully", "Crystalmere"),
        DefaultProfile("Daring Dodo", "Leaps before looking always", "Dawnshard"),
        DefaultProfile("Elegant Echidna", "Precise in all things", "Emberglow"),
        DefaultProfile("Fierce Flamingo", "Stands tall, burns bright", "Frostspire"),
        DefaultProfile("Gallant Gazelle", "Swift across open plains", "Goldmere"),
        DefaultProfile("Heroic Heron", "Patient, then strikes fast", "Highcliff"),
        DefaultProfile("Intrepid Ibis", "Wanders far from home", "Ironveil"),
        DefaultProfile("Jolly Jaguar", "Laughs loud in the dark", "Jadewood"),
        DefaultProfile("Kind Kestrel", "Watchful from the heights", "Kaldera"),
        DefaultProfile("Lively Lynx", "Quick, quiet, always curious", "Lumindra"),
        DefaultProfile("Mystic Marmot", "Knows things left unsaid", "Mirewood"),
        DefaultProfile("Noble Narwhal", "Pierces the deepest waters", "Northveil"),
        DefaultProfile("Orbital Ocelot", "Always circling, never settling", "Oakhaven"),
        DefaultProfile("Playful Pangolin", "Rolls with every tide", "Pinecrest"),
        DefaultProfile("Quiet Quokka", "Smiles through every storm", "Quarrystone"),
        DefaultProfile("Radiant Raccoon", "Finds gold in rubble", "Ravenfield"),
        DefaultProfile("Swift Salamander", "Gone before you blink", "Silverdale"),
        DefaultProfile("Trusty Tapir", "Steady ground beneath you", "Thornbury"),
        DefaultProfile("Unique Uakari", "Unlike anything seen before", "Umbrafell"),
        DefaultProfile("Vivid Vicuña", "Leaves color in passing", "Verdania"),
        DefaultProfile("Wily Walrus", "Smarter than I look", "Westmarch"),
        DefaultProfile("Xenial Xenops", "Welcomes all who wander", "Xandria"),
        DefaultProfile("Youthful Yak", "Forever young at heart", "Yellowmoor"),
        DefaultProfile("Zealous Zorilla", "Burns with quiet purpose", "Zephyr Coast"),
        DefaultProfile("Amber Alpaca", "Warm wool, warmer heart", "Azureton"),
        DefaultProfile("Bold Binturong", "Holds ground, asks questions", "Crestfall"),
        DefaultProfile("Clever Condor", "Sees everything from above", "Duskholm"),
        DefaultProfile("Dreamy Dugong", "Floats through quiet waters", "Emberton"),
        DefaultProfile("Eerie Eland", "Haunts the twilight meadows", "Farwatch"),
        DefaultProfile("Fabled Fennec", "Hears what others miss", "Greywood"),
        DefaultProfile("Graceful Grebe", "Glides without leaving ripples", "Hollowfen"),
        DefaultProfile("Humble Hoopoe", "Digs deep, stays grounded", "Ironhurst"),
        DefaultProfile("Ivory Impala", "Leaps over every obstacle", "Jadewater"),
    )

    private fun indexFor(nodeId: String): Int {
        val hash = nodeId.fold(0L) { acc, c -> acc * 31 + c.code }.toInt()
        return ((hash % profiles.size) + profiles.size) % profiles.size
    }

    fun profileFor(nodeId: String): DefaultProfile = profiles[indexFor(nodeId)]
}
