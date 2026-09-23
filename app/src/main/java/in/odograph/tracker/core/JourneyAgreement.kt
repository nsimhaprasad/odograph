package `in`.odograph.tracker.core

/**
 * Whether the app's idea of where a drive begins and ends matches the car's.
 *
 * Every trip boundary in this app is inferred: the car has been stationary for long enough, so
 * the drive must be over. Every threshold in that inference is a guess about traffic, parking and
 * drop-offs, and the guesses have been expensive — drives ended at red lights because the CAN bus
 * went quiet in P, one evening shredded into three hundred fragments by a restarting recorder,
 * journeys split by a selector moved to park while waiting for someone.
 *
 * The car does not infer. It numbers its journeys, and that number is carried on every frame. So
 * the question "did we get the boundary right" stopped being a matter of opinion the day those
 * identifiers started being recorded.
 *
 * This deliberately only *reports*. Nothing acts on it yet, and nothing should until there is
 * enough history to know how the car behaves — whether it numbers a journey per ignition cycle,
 * per drive, or something else entirely, and how it treats a long halt. Replacing a working
 * inference with an unstudied signal is how the `locked` and `canBusActive` mistakes happened,
 * twice, and both looked just as obviously correct beforehand.
 */
object JourneyAgreement {

    /** What the car's journey numbering says about one drive the app recorded. */
    enum class Verdict {
        /** One journey across the whole drive: the app and the car drew the same boundaries. */
        AGREED,

        /**
         * The car started a new journey partway through. The app ran two of the car's journeys
         * together — a stop it treated as traffic that the car treated as an arrival.
         */
        CAR_SPLIT_IT,

        /** The car never said. Most drives, while the telematics link is down. */
        UNKNOWN
    }

    /**
     * Reads the verdict from the journey identifiers seen during one drive.
     *
     * Two identifiers is not evidence of an error either way on its own — it is evidence the two
     * disagreed, which is the thing worth counting.
     */
    fun verdict(journeyIds: List<Int>): Verdict = verdictOf(journeyIds.distinct().size)

    /**
     * The same rule, from a count the database worked out rather than a list read into memory.
     *
     * Asking the whole history "how many journeys did each drive carry" is one grouped query;
     * asking it one drive at a time is one query per drive, and this history is years long. The
     * rule itself lives here either way, so the two routes cannot drift apart.
     */
    fun verdictOf(distinctJourneys: Int): Verdict = when {
        distinctJourneys <= 0 -> Verdict.UNKNOWN
        distinctJourneys == 1 -> Verdict.AGREED
        else -> Verdict.CAR_SPLIT_IT
    }

    /** How often the two agreed, across drives that had anything to say. */
    data class Tally(val agreed: Int, val carSplit: Int, val unknown: Int) {
        val answered: Int get() = agreed + carSplit

        /** Share of answerable drives where the boundaries matched, or null with nothing to go on. */
        val agreementPercent: Double?
            get() = if (answered == 0) null else agreed * 100.0 / answered
    }

    /**
     * The tally over a whole history, from per-drive journey counts.
     *
     * [countsPerDrive] holds one entry for each drive the car did say something about.
     * [silentDrives] is the rest — drives that produced no identifier at all — and they have to be
     * passed in separately because a grouped query cannot return a row for a drive that has none.
     * Leaving them out would quietly turn a fading telematics link into rising agreement.
     */
    fun tallyOfCounts(countsPerDrive: List<Int>, silentDrives: Int): Tally {
        var agreed = 0
        var split = 0
        var unknown = silentDrives.coerceAtLeast(0)
        countsPerDrive.forEach {
            when (verdictOf(it)) {
                Verdict.AGREED -> agreed++
                Verdict.CAR_SPLIT_IT -> split++
                Verdict.UNKNOWN -> unknown++
            }
        }
        return Tally(agreed, split, unknown)
    }

    fun tally(perDrive: List<List<Int>>): Tally {
        var agreed = 0
        var split = 0
        var unknown = 0
        perDrive.forEach {
            when (verdict(it)) {
                Verdict.AGREED -> agreed++
                Verdict.CAR_SPLIT_IT -> split++
                Verdict.UNKNOWN -> unknown++
            }
        }
        return Tally(agreed, split, unknown)
    }
}
