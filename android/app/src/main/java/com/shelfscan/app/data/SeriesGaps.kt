package com.shelfscan.app.data

import com.shelfscan.app.data.remote.SeriesGapDto
import com.shelfscan.app.data.remote.SeriesVolumeDto

/** Numbers you own, in order. */
val SeriesGapDto.ownedNumbers: List<Int> get() = volumes.filter { it.owned }.map { it.number }

/** Missing volumes that can be shown (the server attaches a catalog record to each). */
val SeriesGapDto.missingVolumes: List<SeriesVolumeDto> get() = volumes.filter { !it.owned && it.book != null }

/** A hole in the set: missing, and before the last volume you own. */
fun SeriesGapDto.isGap(volume: SeriesVolumeDto): Boolean =
    !volume.owned && volume.number < (ownedNumbers.maxOrNull() ?: 0)

/**
 * The series as your library stands now: a missing volume you've since added counts as owned. Null when
 * nothing is missing any more, so a completed set drops off the list without waiting for the server.
 */
fun SeriesGapDto.againstLibrary(ownedKeys: Set<String>): SeriesGapDto? {
    val updated = copy(volumes = volumes.map { v ->
        val key = v.book?.let { matchKeyOf(it.title.trim(), it.author.trim()) }
        if (!v.owned && key != null && key in ownedKeys) v.copy(owned = true) else v
    })
    return updated.takeIf { it.missingVolumes.isNotEmpty() }
}

/** "You own 1, 2 and 4. #3 is missing. #5 is out too." */
fun SeriesGapDto.summary(): String {
    val missing = missingVolumes
    val gaps = missing.filter { isGap(it) }.map { it.number }
    val later = missing.filterNot { isGap(it) }.map { it.number }
    return buildList {
        add("You own ${numberList(ownedNumbers)}.")
        if (gaps.isNotEmpty()) add("${numberList(gaps, hash = true)} ${isAre(gaps)} missing.")
        if (later.isNotEmpty()) add("${numberList(later, hash = true)} ${isAre(later)} out${if (gaps.isNotEmpty()) " too" else ""}.")
    }.joinToString(" ")
}

/** The quiet line under a missing volume in the list. */
fun SeriesGapDto.volumeNote(volume: SeriesVolumeDto): String {
    val firstLater = missingVolumes.firstOrNull { !isGap(it) }
    val what = when {
        isGap(volume) -> "missing from your set"
        volume == firstLater -> "next in the series"
        else -> "also out"
    }
    return "#${volume.number} · $what"
}

/** Why it's on Want to read, kept with the saved book. */
fun SeriesGapDto.wantToReadReason(volume: SeriesVolumeDto): String = "#${volume.number} in $name"

/** "1, 2 and 4". Runs of three or more collapse: "1–3, 5 and 6". With hash: "#3 and #5". */
fun numberList(numbers: List<Int>, hash: Boolean = false): String {
    val sorted = numbers.distinct().sorted()
    val p = if (hash) "#" else ""
    val parts = mutableListOf<String>()
    var i = 0
    while (i < sorted.size) {
        var j = i
        while (j + 1 < sorted.size && sorted[j + 1] == sorted[j] + 1) j++
        if (j - i >= 2) {
            parts += "$p${sorted[i]}–${sorted[j]}"
            i = j + 1
        } else {
            parts += "$p${sorted[i]}"
            i++
        }
    }
    return when (parts.size) {
        0 -> ""
        1 -> parts[0]
        else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    }
}

private fun isAre(numbers: List<Int>) = if (numbers.distinct().size == 1) "is" else "are"
