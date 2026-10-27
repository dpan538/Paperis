package com.inkreader.nativeink

internal object ReaderUpdates {
    const val RELEASES="https://github.com/dpan538/Paperis/releases"
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
