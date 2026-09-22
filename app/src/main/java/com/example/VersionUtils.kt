package com.example

import java.util.Locale

/**
 * Clean semantic version model and parser conforming to SemVer 2.0.0.
 *
 * Handles version tags commonly found in GitHub releases:
 * - "v1.0.2" vs "v1.1.0"
 * - "1.0.2" vs "1.0.2"
 * - "v1.0.1-beta" vs "1.0.1"
 * - "release-1.3.0"
 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String? = null,
    val buildMetadata: String? = null,
    val raw: String = ""
) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        if (this.major != other.major) return this.major.compareTo(other.major)
        if (this.minor != other.minor) return this.minor.compareTo(other.minor)
        if (this.patch != other.patch) return this.patch.compareTo(other.patch)

        // SemVer 2.0.0 rule:
        // A normal version with no pre-release has higher precedence than a pre-release version.
        // E.g.: 1.0.0-alpha < 1.0.0, 1.0.0-rc.1 < 1.0.0
        return when {
            this.preRelease == null && other.preRelease == null -> 0
            this.preRelease == null && other.preRelease != null -> 1 // this is stable, other is pre-release
            this.preRelease != null && other.preRelease == null -> -1 // this is pre-release, other is stable
            else -> comparePreReleaseTokens(this.preRelease!!, other.preRelease!!)
        }
    }

    private fun comparePreReleaseTokens(preA: String, preB: String): Int {
        val partsA = preA.split(".")
        val partsB = preB.split(".")
        val maxLen = maxOf(partsA.size, partsB.size)
        for (i in 0 until maxLen) {
            val a = partsA.getOrNull(i) ?: return -1
            val b = partsB.getOrNull(i) ?: return 1
            val numA = a.toIntOrNull()
            val numB = b.toIntOrNull()
            val cmp = if (numA != null && numB != null) {
                numA.compareTo(numB)
            } else {
                a.compareTo(b, ignoreCase = true)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }
}

object VersionUtils {

    /**
     * Sanitizes and parses a version string into a structured [SemVer].
     * Strips leading prefixes like "v", "V", "release-", or "ver-".
     *
     * Examples:
     * - "v1.0.2" -> SemVer(1, 0, 2)
     * - "1.1.0" -> SemVer(1, 1, 0)
     * - "v2.0.0-rc.1+build.42" -> SemVer(2, 0, 0, preRelease = "rc.1", buildMetadata = "build.42")
     */
    fun parseSemVer(versionString: String): SemVer? {
        val trimmed = versionString.trim()
        if (trimmed.isEmpty()) return null

        val clean = trimmed
            .removePrefix("v")
            .removePrefix("V")
            .removePrefix("release-")
            .removePrefix("ver-")
            .trim()

        if (clean.isEmpty()) return null

        // 1. Separate build metadata (after '+')
        val beforeBuild = clean.substringBefore('+')
        val buildMetadata = if (clean.contains('+')) clean.substringAfter('+').trim().ifEmpty { null } else null

        // 2. Separate pre-release tag (after '-')
        val coreVersion = beforeBuild.substringBefore('-')
        val preRelease = if (beforeBuild.contains('-')) beforeBuild.substringAfter('-').trim().ifEmpty { null } else null

        // 3. Extract major, minor, patch
        val parts = coreVersion.split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0

        return SemVer(
            major = major,
            minor = minor,
            patch = patch,
            preRelease = preRelease,
            buildMetadata = buildMetadata,
            raw = trimmed
        )
    }

    /**
     * Determines whether an update is available by comparing [currentVersion] and [latestVersion].
     *
     * Returns true ONLY if [latestVersion] is strictly greater than [currentVersion].
     *
     * Handles:
     * - Semantic Versioning comparison (e.g. "1.0.2" vs "1.1.0" -> true)
     * - Prefixes like "v" (e.g. "1.0.2" vs "v1.0.2" -> false, "v1.0.0" vs "v1.0.1" -> true)
     * - Pre-release tags (e.g. "1.0.0-beta" vs "1.0.0" -> true, "1.0.0" vs "1.0.0-rc1" -> false)
     * - Fallback to numeric segment comparison for non-standard version tags
     */
    fun isUpdateAvailable(currentVersion: String, latestVersion: String): Boolean {
        val currentSemVer = parseSemVer(currentVersion)
        val latestSemVer = parseSemVer(latestVersion)

        if (currentSemVer != null && latestSemVer != null) {
            return latestSemVer > currentSemVer
        }

        // Fallback for custom or multi-segment tags (e.g. "build-12-1" vs "build-14")
        val currentNumbers = extractNumericSegments(currentVersion)
        val latestNumbers = extractNumericSegments(latestVersion)
        if (currentNumbers.isNotEmpty() && latestNumbers.isNotEmpty()) {
            val maxLen = maxOf(currentNumbers.size, latestNumbers.size)
            for (i in 0 until maxLen) {
                val c = currentNumbers.getOrElse(i) { 0 }
                val l = latestNumbers.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
            return false
        }

        return false
    }

    private fun extractNumericSegments(version: String): List<Int> {
        return Regex("""\d+""").findAll(version).mapNotNull { it.value.toIntOrNull() }.toList()
    }
}

/**
 * Top-level convenience function for checking if an update is available.
 */
fun isUpdateAvailable(currentVersion: String, latestVersion: String): Boolean {
    return VersionUtils.isUpdateAvailable(currentVersion, latestVersion)
}
