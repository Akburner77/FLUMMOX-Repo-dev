// ── StreamScrapers entry point ──

suspend fun showBoxExtractRaw(q: StreamQuery): List<ScrapedMirror> {
    val hits = sbSearch(q.title)
    if (hits.isEmpty()) return emptyList()

    val expectedType = if (q.type == "series") 2 else 1
    var best: SBItem? = null
    var bestScore = 0
    for (item in hits) {
        if (!titleMatches(q.title, item.title)) continue
        var score = 1
        if (item.boxType == expectedType) score += 3
        if (q.year.isNotBlank() && item.title.contains(q.year)) score += 2
        if (score > bestScore) { bestScore = score; best = item }
    }
    val subject = best ?: return emptyList()
    BCLog.d("ShowBox matched '${subject.title}' (score=$bestScore)")

    val shareKey = sbExternalShareKey(subject.id, subject.boxType) ?: return emptyList()

    var fileList = sbFileList(shareKey) ?: return emptyList()

    if (q.type == "series" && q.season > 0) {
        var seasonFid: Long? = null
        for (i in 0 until fileList.length()) {
            val f = fileList.optJSONObject(i) ?: continue
            if (f.optString("file_name").equals("season ${q.season}", true)) {
                seasonFid = f.optLong("fid"); break
            }
        }
        if (seasonFid != null) {
            fileList = sbFileList(shareKey, seasonFid) ?: return emptyList()
        }
    }

    val out = mutableListOf<ScrapedMirror>()
    for (i in 0 until fileList.length()) {
        val f = fileList.optJSONObject(i) ?: continue
        val name = f.optString("file_name")
        if (q.type == "series" && q.season > 0 && q.episode > 0) {
            val pat = Regex("""s0*${q.season}\s*e0*${q.episode}""", RegexOption.IGNORE_CASE)
            if (!pat.containsMatchIn(name)) continue
        }
        val path = f.optString("path").takeIf { it.isNotBlank() } ?: continue
        val quality = f.optString("quality").ifBlank { "Auto" }
        val size = f.optString("size").ifBlank { null }
        val label = "ShowBox ${quality}${if (size != null) " [$size]" else ""}"
        out.add(ScrapedMirror(quality, label, path.replace("\\/", "/"), "SHOWBOX"))
    }
    BCLog.d("ShowBox: ${out.size} mirrors")
    return out
}
