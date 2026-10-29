// CaptionAligner.kt

package com.mochyy.mochymusic.lyrics

import com.music.innertube.CaptionCue
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.min

// finds where on a video each lyric line is sung by reading its captions, and how far the video runs from the lyrics
internal object CaptionAligner {

    // one lyric line found in the captions
    class Anchor(val lyricMs: Long, val videoMs: Long, val score: Float) {
        val deltaMs: Long get() = videoMs - lyricMs
    }

    class Alignment(val anchors: List<Anchor>, val map: SongVideoMap, val matchedLines: Int, val usableLines: Int)

    // null when too few lines could be found in the captions to trust any timing from them
    fun align(lines: List<LyricsEntry>, cues: List<CaptionCue>): Alignment? {
        val stream = CaptionStream.of(cues) ?: return null
        val gram = if (stream.isMostlyCjk) 2 else 3
        val index = stream.gramIndex(gram)

        val mainLines = lines.filter { !it.isBackground && it.text.isNotBlank() }
        var usable = 0
        val nodes = ArrayList<Node>()
        mainLines.forEachIndexed { lineIndex, line ->
            val text = normalize(line.text)
            if (text.length < gram + 2) return@forEachIndexed
            usable++
            candidates(text, gram, index).forEach { (position, score) ->
                nodes += Node(lineIndex, line.time, stream.timeAt(position), position, score)
            }
        }
        val path = bestPath(nodes)
        val anchors = dropOutliers(path.map { Anchor(it.lyricMs, it.videoMs, it.score) })
        if (anchors.size < MinAnchors || anchors.size < usable * MinMatchedShare) return null
        val runs = runsOf(anchors) ?: return null
        return Alignment(anchors, SongVideoMap(runs), anchors.size, usable)
    }

    // each lyric line's share of a translation track: a cue goes to the line it overlaps most on the video
    fun assignCues(lines: List<LyricsEntry>, map: SongVideoMap, cues: List<CaptionCue>): List<String?> {
        val windows = lines.indices.map { i ->
            val line = lines[i]
            if (line.isBackground || line.text.isBlank()) return@map null
            val nextTime = (i + 1 until lines.size).firstOrNull { !lines[it].isBackground }?.let { lines[it].time }
            val wordsEnd = line.words?.lastOrNull()?.endTime?.let { (it * 1000).toLong() }
            val end = nextTime ?: wordsEnd ?: (line.time + LastLineMs)
            map.videoMsAt(line.time) to map.videoMsAt(maxOf(end, line.time + 1))
        }
        val assigned = arrayOfNulls<StringBuilder>(lines.size)
        for (cue in cues) {
            var bestLine = -1
            var bestOverlap = 0L
            windows.forEachIndexed { i, window ->
                if (window == null) return@forEachIndexed
                val overlap = min(cue.endMs, window.second) - maxOf(cue.startMs, window.first)
                if (overlap > bestOverlap) {
                    bestOverlap = overlap
                    bestLine = i
                }
            }
            val cueLength = (cue.endMs - cue.startMs).coerceAtLeast(1)
            if (bestLine == -1 || (bestOverlap < MinCueOverlapMs && bestOverlap * 3 < cueLength)) continue
            val builder = assigned[bestLine]
            if (builder == null) assigned[bestLine] = StringBuilder(cue.text) else builder.append(' ').append(cue.text)
        }
        return assigned.map { it?.toString() }
    }

    // ---- reading text ----

    // lowercase letters and digits only, with katakana folded into hiragana so either spelling of a word matches
    fun normalize(text: String): String {
        val folded = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()
        val out = StringBuilder(folded.length)
        for (ch in folded) {
            if (!ch.isLetterOrDigit()) continue
            out.append(if (ch in 'ァ'..'ヶ') ch - 0x60 else ch)
        }
        return out.toString()
    }

    private fun isCjk(ch: Char): Boolean =
        ch in '぀'..'ヿ' || ch in '一'..'鿿' || ch in '가'..'힯' || ch in '㐀'..'䶿'

    // every caption letter in order, each with the moment it's on screen (a cue's letters spread over its time)
    private class CaptionStream(val text: String, val times: LongArray) {
        val isMostlyCjk: Boolean get() = text.count(::isCjk) * 10 >= text.length * 4

        fun timeAt(position: Int): Long = times[position.coerceIn(0, times.lastIndex)]

        // where each run of letters appears; the very common ones say nothing and are left out
        fun gramIndex(size: Int): Map<String, IntArray> {
            val positions = HashMap<String, MutableList<Int>>()
            for (start in 0..text.length - size) {
                positions.getOrPut(text.substring(start, start + size)) { ArrayList(2) } += start
            }
            return positions.filterValues { it.size <= MaxGramRepeats }.mapValues { it.value.toIntArray() }
        }

        companion object {
            fun of(cues: List<CaptionCue>): CaptionStream? {
                val text = StringBuilder()
                val times = ArrayList<Long>()
                for (cue in cues) {
                    val letters = normalize(cue.text)
                    if (letters.isEmpty()) continue
                    // capped, so a caption held on screen long after it's sung doesn't push its last letters late
                    val perLetter = min((cue.endMs - cue.startMs).coerceAtLeast(0) / letters.length, MaxMsPerLetter)
                    letters.forEachIndexed { k, letter ->
                        text.append(letter)
                        times += cue.startMs + perLetter * k
                    }
                }
                if (text.length < 20) return null
                return CaptionStream(text.toString(), times.toLongArray())
            }
        }
    }

    // ---- finding each line ----

    // where in the captions a line could start, with the share of its letter runs found there; a chorus finds every place it's sung
    private fun candidates(line: String, gram: Int, index: Map<String, IntArray>): List<Pair<Int, Float>> {
        val grams = line.length - gram + 1
        val bucket = maxOf(4, line.length / 4)
        val votes = HashMap<Int, Int>()
        val starts = HashMap<Int, MutableList<Int>>()
        val seen = HashSet<Int>()
        for (k in 0 until grams) {
            val positions = index[line.substring(k, k + gram)] ?: continue
            seen.clear()
            for (position in positions) {
                val start = position - k
                val center = Math.floorDiv(start, bucket)
                // counted in the neighbouring buckets too: captions and lyrics rarely spell a line the same length
                for (b in center - 1..center + 1) {
                    if (seen.add(b)) votes[b] = (votes[b] ?: 0) + 1
                }
                starts.getOrPut(center) { ArrayList() } += start
            }
        }
        val picked = ArrayList<Pair<Int, Float>>()
        val taken = ArrayList<Int>()
        for ((b, count) in votes.entries.sortedByDescending { it.value }) {
            val score = count.toFloat() / grams
            if (score < MinLineScore || picked.size >= MaxCandidates) break
            if (taken.any { abs(it - b) <= 2 }) continue
            val near = (b - 1..b + 1).flatMap { starts[it].orEmpty() }.sorted()
            if (near.isEmpty()) continue
            taken += b
            picked += near[near.size / 2] to score.coerceAtMost(1f)
        }
        return picked
    }

    private class Node(val line: Int, val lyricMs: Long, val videoMs: Long, val position: Int, val score: Float)

    // the best run of line placements that only ever moves forward, where a sudden change in shift costs about one line
    private fun bestPath(nodes: List<Node>): List<Node> {
        if (nodes.isEmpty()) return emptyList()
        val sorted = nodes.sortedWith(compareBy({ it.line }, { it.position }))
        val total = FloatArray(sorted.size)
        val previous = IntArray(sorted.size) { -1 }
        for (i in sorted.indices) {
            val node = sorted[i]
            total[i] = node.score
            for (j in 0 until i) {
                val before = sorted[j]
                if (before.line >= node.line || before.position >= node.position || before.videoMs > node.videoMs) continue
                val jump = abs((node.videoMs - node.lyricMs) - (before.videoMs - before.lyricMs))
                val penalty = min(jump / 1000f * JumpCostPerSecond, MaxJumpCost)
                val candidate = total[j] - penalty + node.score
                if (candidate > total[i]) {
                    total[i] = candidate
                    previous[i] = j
                }
            }
        }
        var at = total.indices.maxBy { total[it] }
        val path = ArrayList<Node>()
        while (at != -1) {
            path += sorted[at]
            at = previous[at]
        }
        return path.asReversed()
    }

    // a weak line whose shift disagrees with every neighbour was matched to the wrong place
    private fun dropOutliers(anchors: List<Anchor>): List<Anchor> = anchors.filterIndexed { i, anchor ->
        if (anchor.score >= 0.75f) return@filterIndexed true
        val neighbours = (maxOf(0, i - 2)..minOf(anchors.lastIndex, i + 2)).filter { it != i }.map { anchors[it].deltaMs }
        if (neighbours.size < 2) return@filterIndexed true
        abs(anchor.deltaMs - median(neighbours)) <= OutlierMs
    }

    // splits the song where the shift really changes (an intro, a skit or a cut in the video), each stretch on its own steady shift
    private fun runsOf(anchors: List<Anchor>): List<SongVideoMap.Run>? {
        val groups = ArrayList<MutableList<Anchor>>()
        for (anchor in anchors) {
            val current = groups.lastOrNull()
            if (current != null && abs(anchor.deltaMs - median(current.map { it.deltaMs })) <= RunToleranceMs) {
                current += anchor
            } else {
                groups += mutableListOf(anchor)
            }
        }
        val steady = groups.filter { it.size >= MinRunAnchors }
        if (steady.isEmpty()) return null
        return steady.map { group -> SongVideoMap.Run(group.first().lyricMs, group.last().lyricMs, median(group.map { it.deltaMs })) }
    }

    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private const val MaxGramRepeats = 60
    private const val MaxMsPerLetter = 250L
    private const val MinLineScore = 0.34f
    private const val MaxCandidates = 4
    private const val JumpCostPerSecond = 0.25f
    private const val MaxJumpCost = 0.8f
    private const val OutlierMs = 2_000L
    private const val RunToleranceMs = 700L
    private const val MinRunAnchors = 2
    private const val MinAnchors = 3
    private const val MinMatchedShare = 0.3f
    private const val MinCueOverlapMs = 400L
    private const val LastLineMs = 6_000L
}
