import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.cdimascio.dotenv.Dotenv
import net.sourceforge.argparse4j.ArgumentParsers
import java.io.File
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.math.absoluteValue

data class Args(val root: File, val expectedWorkload: Duration, val startDate: LocalDate, val endDate: LocalDate)

fun main(arguments: Array<String>) {
    val zoneId = ZoneId.systemDefault()

    // Parse arguments
    val args: Args
    if (File(".env").exists()) {
        val env = Dotenv.load()
        args = Args(
            root = File(env.get("TIMESHEET_LOCATION_HISTORY_FILE")),
            expectedWorkload = Duration.of(env.get("TIMESHEET_WORKLOAD").toLong(), ChronoUnit.HOURS),
            startDate = LocalDate.parse(env.get("TIMESHEET_START_DATE")),
            endDate = LocalDate.parse(env.get("TIMESHEET_END_DATE")),
        )
    } else {
        val parser = ArgumentParsers
            .newFor("Google Maps Timesheet")
            .build()
            .defaultHelp(true)
            .description("Calculate your work timesheet based on your Maps Location History, extracted from Google Takeout.")
        parser.addArgument("--file")
            .help("The absolute path of the 'Semantic Location History' folder, located in your Takeout data.")
            .required(true)
            .type(File::class.java)
        parser.addArgument("--workload")
            .help("The amount of time you should be working daily, in hours.")
            .required(true)
            .type(Int::class.java)
        parser.addArgument("--range")
            .nargs(2)
            .help("The start and end dates to analyze, in 'YYYY-MM-DD' format.")
            .required(true)

        val namespace = parser.parseArgs(arguments)
        val file = namespace.get<File>("file")
        val workload = Duration.of(namespace.getInt("workload").toLong(), ChronoUnit.HOURS)
        val (startDate, endDate) = namespace.getList<String>("range").map(LocalDate::parse)
        args = Args(root = file, expectedWorkload = workload, startDate = startDate, endDate = endDate)
    }

    // Group file contents by month
    val gson = Gson()
    val monthlyData: MutableList<Map.Entry<YearMonth, List<Map<*, *>>>> = mutableListOf()
    for (yearDir in args.root.listFiles().orEmpty()) {
        val year = yearDir.name.toInt()
        if (year > args.startDate.year && args.endDate.year < year) continue
        val yearlyData = yearDir.listFiles()
            .orEmpty()
            .filterNotNull()
            .associateBy { file ->
                YearMonth.parse(
                    file.nameWithoutExtension
                        .split("_")
                        .joinToString("_") { token -> token.lowercase().replaceFirstChar(Char::uppercase) },
                    DateTimeFormatter.ofPattern("yyyy_MMMM", Locale.US)
                )
            }
            .filterKeys { month ->
                (args.startDate.year == year && args.startDate.month <= month.month) ||
                        (args.endDate.year == year && month.month <= args.endDate.month)
            }
            .mapValues { (_, file) ->
                val contents = file.inputStream().readAllBytes().decodeToString()
                val data: Map<String, Any?> = gson.fromJson(contents, object : TypeToken<Map<String, Any?>>() {})
                (data["timelineObjects"] as List<*>)
                    .map { childData -> (childData as Map<*, *>).entries.single() }
                    .filter { entry -> entry.key == "placeVisit" }
                    .map { entry -> entry.value as Map<*, *> }
            }
            .entries
            .sortedBy { entry -> entry.key }

        monthlyData.addAll(yearlyData)
    }

    // Find places labeled as 'Work' on Google Maps
    val workLocations = mutableSetOf<Location>()
    for ((_, placeVisitDatum) in monthlyData) {
        for (placeVisitData in placeVisitDatum) {
            val locationData = placeVisitData["location"] as Map<*, *>

            val placeId = (locationData["placeId"] as String?) ?: continue
            val name = (locationData["name"] as String?) ?: continue
            val location = Location(placeId, name)

            val isWork = (locationData["semanticType"] as String?) == "TYPE_WORK"
            if (isWork) workLocations.add(location)
        }
    }

    val workLocation = workLocations.last()
    // Calculate how many hours the user should be compensating or be compensated
    val totalPendingHours = monthlyData
        .fold(Duration.ZERO) { acc, (month, placeVisitDatum) ->
            val visits = placeVisitDatum.mapNotNull { placeVisitData ->
                val durationData = placeVisitData["duration"] as Map<*, *>
                val startTime = LocalDateTime.ofInstant(Instant.parse(durationData["startTimestamp"] as String), zoneId)
                val endTime = LocalDateTime.ofInstant(Instant.parse(durationData["endTimestamp"] as String), zoneId)

                val locationData = placeVisitData["location"] as Map<*, *>
                val placeId = (locationData["placeId"] as String?) ?: return@mapNotNull null
                val name = (locationData["name"] as String?) ?: return@mapNotNull null
                Visit(Location(placeId, name), startTime, endTime)
            }

            var pendingHours: Duration = Duration.ZERO
            for ((day, dailyVisits) in visits.groupBy { visit -> visit.end.dayOfMonth }) {
                val isWeekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY).contains(month.atDay(day).dayOfWeek)
                if (isWeekend) continue

                val didVisitWork = dailyVisits.any { visit -> visit.location == workLocation }
                // TODO Consider days where user should be visiting work, but it didn't
                //      This should add `expectedWorkload` to `pendingHours` (i.e. set `actualWorkload` to zero)
                //      When implementing, holidays and justified absences should be considered
                if (!didVisitWork) continue

                val firstVisit = dailyVisits.first { visit -> visit.location == workLocation }
                val lastVisit = dailyVisits.last { visit -> visit.location == workLocation }
                // TODO Consider interspersed visits (enter work, exit work, go elsewhere, enter work, exit work)
                //      This should reduce `actualWorkload` in these cases by "go elsewhere" amount of time
                val actualWorkload = Duration.between(firstVisit.start, lastVisit.end)
                pendingHours += args.expectedWorkload - actualWorkload
            }
            acc + pendingHours
        }

    val factor = totalPendingHours.toMillis() / args.expectedWorkload.toMillis()
    if (totalPendingHours.isNegative) {
        println("${factor.absoluteValue} days bonus")
    } else {
        println("$factor days pending")
    }
}

/**
 * Represents a visit to a [location], entering at [start] and exiting at [end].
 */
data class Visit(val location: Location, val start: LocalDateTime, val end: LocalDateTime)

/**
 * Represents a Google Maps location.
 */
data class Location(val placeId: String, val name: String) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Location
        return placeId == other.placeId
    }

    override fun hashCode(): Int {
        return placeId.hashCode()
    }

    override fun toString(): String {
        return "Location('$name')"
    }
}
