package com.mbientlab.metawear.firmware

// Numeric version-string comparison for MetaWear firmware revisions.
//
// MbientLab firmware versions follow loose dotted-numeric form ("1.5.0",
// "1.5", "1.7.3"). Components are compared numerically, with the shorter
// string right-padded with zeros so "1.5" == "1.5.0".
// The helpers are internal because the only caller is the firmware update
// pipeline.

/**
 * Compare two MetaWear-style dotted version strings ("1.5.0", "1.5", "1.7.3").
 * Components are compared numerically; if one string has fewer components the
 * shorter is right-padded with zeros so "1.5" sorts equal to "1.5.0".
 *
 * Returns a negative value / zero / positive value like [Comparable.compareTo].
 */
internal fun String.metaWearVersionCompare(targetVersion: String): Int {
    val delimiter = "."
    var versionComponents = split(delimiter)
    var targetComponents = targetVersion.split(delimiter)
    val spareCount = versionComponents.size - targetComponents.size

    if (spareCount > 0) {
        targetComponents = targetComponents + List(spareCount) { "0" }
    } else if (spareCount < 0) {
        versionComponents = versionComponents + List(-spareCount) { "0" }
    }
    return numericCompare(
        versionComponents.joinToString(delimiter),
        targetComponents.joinToString(delimiter),
    )
}

internal fun String.isMetaWearVersionEqualTo(other: String): Boolean =
    metaWearVersionCompare(other) == 0

internal fun String.isMetaWearVersionGreaterThan(other: String): Boolean =
    metaWearVersionCompare(other) > 0

internal fun String.isMetaWearVersionGreaterThanOrEqualTo(other: String): Boolean =
    metaWearVersionCompare(other) >= 0

internal fun String.isMetaWearVersionLessThan(other: String): Boolean =
    metaWearVersionCompare(other) < 0

internal fun String.isMetaWearVersionLessThanOrEqualTo(other: String): Boolean =
    metaWearVersionCompare(other) <= 0

/**
 * Numeric-aware string comparison: runs of
 * digits compare as integers ("9" < "10"), all other characters compare
 * literally, and when one string is a proper prefix of the other the longer
 * string sorts after ("2.0.0" < "2.0.0-beta").
 */
private fun numericCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            val startA = i
            val startB = j
            while (i < a.length && a[i].isDigit()) i++
            while (j < b.length && b[j].isDigit()) j++
            // Strip leading zeros; compare magnitude by length, then lexically.
            val da = a.substring(startA, i).trimStart('0')
            val db = b.substring(startB, j).trimStart('0')
            val cmp = if (da.length != db.length) da.length - db.length else da.compareTo(db)
            if (cmp != 0) return if (cmp < 0) -1 else 1
        } else {
            if (ca != cb) return if (ca < cb) -1 else 1
            i++
            j++
        }
    }
    return when {
        i < a.length -> 1
        j < b.length -> -1
        else -> 0
    }
}
