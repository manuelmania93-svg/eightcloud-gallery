package app.eightcloud.gallery.labels

import java.text.Normalizer

/** CLIP byte-level BPE tokenizer (vocab 49408), verified against the reference tokenizer. */
class ClipTokenizer(vocabLines: List<String>, mergeLines: List<String>) {

    private val vocab = HashMap<String, Int>(vocabLines.size * 2).also { m ->
        vocabLines.forEachIndexed { i, s -> m[s] = i }
    }
    private val ranks = HashMap<String, Int>(mergeLines.size * 2).also { m ->
        mergeLines.forEachIndexed { i, s -> m[s] = i }
    }
    private val byteToChar: Array<Char> = buildByteMap()
    private val cache = HashMap<String, List<Int>>()

    fun encode(text: String, maxLen: Int = 77): LongArray {
        val norm = Normalizer.normalize(text, Normalizer.Form.NFC)
            .replace(WHITESPACE, " ").lowercase()
        val ids = ArrayList<Int>()
        ids += START
        for (m in PATTERN.findAll(norm)) {
            val sb = StringBuilder()
            for (b in m.value.toByteArray(Charsets.UTF_8)) sb.append(byteToChar[b.toInt() and 0xFF])
            ids += cache.getOrPut(sb.toString()) { bpe(sb.toString()) }
        }
        val body = if (ids.size > maxLen - 1) ids.subList(0, maxLen - 1) else ids
        return (body + END).map { it.toLong() }.toLongArray()
    }

    private fun bpe(token: String): List<Int> {
        var w = token.map { it.toString() }.toMutableList()
        if (w.isEmpty()) return emptyList()
        w[w.size - 1] = w.last() + "</w>"
        while (w.size > 1) {
            var best = Int.MAX_VALUE
            var bi = -1
            for (i in 0 until w.size - 1) {
                val r = ranks[w[i] + " " + w[i + 1]] ?: continue
                if (r < best) { best = r; bi = i }
            }
            if (bi < 0) break
            w[bi] = w[bi] + w[bi + 1]
            w.removeAt(bi + 1)
        }
        return w.map { vocab[it] ?: UNK }
    }

    companion object {
        const val START = 49406
        const val END = 49407
        const val UNK = 49407
        private val WHITESPACE = Regex("\\s+")
        private val PATTERN = Regex(
            """<\|startoftext\|>|<\|endoftext\|>|'s|'t|'re|'ve|'m|'ll|'d|[\p{L}]+|[\p{N}]|[^\s\p{L}\p{N}]+"""
        )

        private fun buildByteMap(): Array<Char> {
            val bs = ArrayList<Int>()
            bs.addAll(33..126); bs.addAll(161..172); bs.addAll(174..255)
            val cs = ArrayList(bs)
            var n = 0
            for (b in 0..255) if (b !in bs) { bs.add(b); cs.add(256 + n); n++ }
            val out = Array(256) { ' ' }
            bs.forEachIndexed { i, b -> out[b] = cs[i].toChar() }
            return out
        }

        fun load(context: android.content.Context): ClipTokenizer {
            val v = context.assets.open("clip_vocab.txt").bufferedReader().use { it.readLines() }
            val m = context.assets.open("clip_merges.txt").bufferedReader().use { it.readLines() }
            return ClipTokenizer(v, m)
        }
    }
}
