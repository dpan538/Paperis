package com.inkreader.nativeink

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URI

/** Only user-initiated requests to the project's public release feed. */
internal object ReaderUpdates {
    const val RELEASES = "https://github.com/dpan538/Paperis/releases"
    const val FEEDBACK = "https://github.com/dpan538/Paperis/issues/new"
    data class Result(val state: Int, val version: String = "", val url: String = "")

    fun fetch(): Result {
        val connection = URI("https://api.github.com/repos/dpan538/Paperis/releases?per_page=20").toURL().openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 8000; connection.readTimeout = 8000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "Paperis/${BuildConfig.VERSION_NAME}")
            check(connection.responseCode == 200) { "Release feed unavailable" }
            val body = connection.inputStream.use { it.readNBytes(512 * 1024 + 1) }
            check(body.size <= 512 * 1024)
            val releases = JSONArray(body.toString(Charsets.UTF_8))
            val candidates = (0 until releases.length()).mapNotNull { i ->
                val release = releases.getJSONObject(i)
                if (release.optBoolean("draft")) return@mapNotNull null
                val version = release.optString("tag_name").removePrefix("v")
                val key = ReleaseVersion.parse(version) ?: return@mapNotNull null
                val assets = release.optJSONArray("assets") ?: return@mapNotNull null
                val asset = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.optString("name") == "Paperis-$version.apk" } ?: return@mapNotNull null
                val url = asset.optString("browser_download_url")
                if (!url.startsWith("$RELEASES/download/")) return@mapNotNull null
                Triple(key, version, url)
            }
            val latest = candidates.maxByOrNull { it.first } ?: return Result(5)
            val current = checkNotNull(ReleaseVersion.parse(BuildConfig.VERSION_NAME))
            Result(if (latest.first > current) 3 else 2, latest.second, latest.third)
        } finally { connection.disconnect() }
    }
}

/** Numeric ordering includes preview releases; stable sorts after previews of the same version. */
internal data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int, val preview: Int) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion) = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }, { it.preview })
    companion object {
        fun parse(value: String): ReleaseVersion? {
            val m = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-preview\\.(\\d+))?$").matchEntire(value.removePrefix("v")) ?: return null
            val values = (1..3).map { m.groupValues[it].toIntOrNull() ?: return null }
            val preview = if (m.groupValues[4].isEmpty()) Int.MAX_VALUE else m.groupValues[4].toIntOrNull() ?: return null
            return ReleaseVersion(values[0], values[1], values[2], preview)
        }
    }
}
