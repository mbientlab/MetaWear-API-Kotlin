package com.mbientlab.metawear.firmware

// Parser for the MbientLab firmware-catalog
// JSON served at `https://mbientlab.com/releases/metawear/info2.json`.
//
// Catalog shape (4 levels of dictionary nesting, leaf values are short
// string-keyed dictionaries):
//
// ```json
// {
//   "<hardwareRev>": {                   e.g. "0.4"
//     "<modelNumber>": {                 e.g. "5"
//       "<buildFlavor>": {               e.g. "vanilla", "bootloader"
//         "<firmwareRev>": {             e.g. "1.7.3"
//           "filename": "firmware.zip",
//           "required-bootloader": "0.5",
//           "min-ios-version": "3.2.0"
//         },
//         ...
//       }
//     }
//   }
// }
// ```
//
// The parser is split out so unit tests can feed canned JSON without going
// through HTTP.
//
// JSON parsing choice: android.org.json is not on the JVM unit-test classpath
// (the android.jar test stubs throw), so — following the precedent set by
// :metawear-protocol's BoardState codec — the module hand-rolls a minimal
// recursive-descent parser (JsonLite below) instead of adding a dependency.
// The catalog schema is fixed and tiny; a general JSON library buys nothing.

/**
 * Typed alias for the parsed catalog. The inner-leaf values are
 * `Map<String, String>` — `filename`, `required-bootloader`,
 * `min-ios-version`. Kept stringly-typed because that's how the server emits
 * them.
 */
internal typealias FirmwareCatalogJson =
    Map<String, Map<String, Map<String, Map<String, Map<String, String>>>>>

internal object FirmwareCatalog {

    /**
     * Parse the raw text returned by the catalog server into the typed JSON
     * map. Throws [FirmwareException.InvalidServerResponse] if the text
     * doesn't deserialize or doesn't match the expected shape (validated
     * explicitly, level by level).
     */
    fun parse(text: String): FirmwareCatalogJson {
        val obj: Any? = try {
            JsonLite.parse(text)
        } catch (e: IllegalArgumentException) {
            throw FirmwareException.InvalidServerResponse(
                "JSON deserialization failed: ${e.message}",
            )
        }
        return asCatalogShape(obj) ?: throw FirmwareException.InvalidServerResponse(
            "JSON shape mismatch — expected [hwRev: [model: [flavor: [version: {…}]]]].",
        )
    }

    /** Parse the raw bytes returned by the catalog server (UTF-8). */
    fun parse(data: ByteArray): FirmwareCatalogJson = parse(data.toString(Charsets.UTF_8))

    /**
     * Extract every build that matches the requested (hardwareRev,
     * modelNumber, buildFlavor) tuple AND whose `min-ios-version` is ≤ the
     * current SDK version, sorted ascending by firmware revision.
     *
     * The server's catalog key is literally named `min-ios-version` — it
     * gates the minimum supported client SDK version, and the floor applies
     * to any SDK consuming the catalog.
     *
     * @param json        The parsed catalog (from [parse]).
     * @param hardwareRev Hardware revision string from the connected device.
     * @param modelNumber Model number string from the connected device.
     * @param buildFlavor `"vanilla"` for end-user firmware, `"bootloader"`
     *                    for bootloader-only builds.
     * @param sdkVersion  This SDK's own version string, used to filter out
     *                    firmware that requires a newer SDK than the caller.
     * @return Builds sorted ascending by `firmwareRev`. Empty list if nothing
     *   matches (the orchestrator promotes that to a thrown
     *   [FirmwareException.NoAvailableFirmware]).
     */
    fun matchingBuilds(
        json: FirmwareCatalogJson,
        hardwareRev: String,
        modelNumber: String,
        buildFlavor: String,
        sdkVersion: String,
    ): List<FirmwareBuild> {
        val candidates = json[hardwareRev]?.get(modelNumber)?.get(buildFlavor) ?: return emptyList()
        return candidates.entries
            // Filter by SDK floor — a missing `min-ios-version` key is
            // treated as "any SDK supports this" for forward compatibility.
            .filter { (_, attrs) ->
                val required = attrs["min-ios-version"] ?: return@filter true
                sdkVersion.isMetaWearVersionGreaterThanOrEqualTo(required)
            }
            // Stable sort by firmware version ascending.
            .sortedWith { lhs, rhs -> lhs.key.metaWearVersionCompare(rhs.key) }
            // Map to FirmwareBuild. `filename` is required; missing means the
            // catalog entry is malformed and we just skip it (rather than
            // fail the whole parse for one bad row).
            .mapNotNull { (firmwareRev, attrs) ->
                val filename = attrs["filename"] ?: return@mapNotNull null
                FirmwareBuild(
                    hardwareRev = hardwareRev,
                    modelNumber = modelNumber,
                    buildFlavor = buildFlavor,
                    firmwareRev = firmwareRev,
                    filename = filename,
                    requiredBootloader = attrs["required-bootloader"],
                )
            }
    }

    /**
     * Validate + narrow the untyped parse tree to [FirmwareCatalogJson].
     * Returns null on any shape violation (non-map level, non-string leaf).
     */
    private fun asCatalogShape(obj: Any?): FirmwareCatalogJson? {
        val root = obj as? Map<*, *> ?: return null
        val catalog = mutableMapOf<String, Map<String, Map<String, Map<String, Map<String, String>>>>>()
        for ((hwRev, models) in root) {
            if (hwRev !is String || models !is Map<*, *>) return null
            val modelMap = mutableMapOf<String, Map<String, Map<String, Map<String, String>>>>()
            for ((model, flavors) in models) {
                if (model !is String || flavors !is Map<*, *>) return null
                val flavorMap = mutableMapOf<String, Map<String, Map<String, String>>>()
                for ((flavor, versions) in flavors) {
                    if (flavor !is String || versions !is Map<*, *>) return null
                    val versionMap = mutableMapOf<String, Map<String, String>>()
                    for ((version, attrs) in versions) {
                        if (version !is String || attrs !is Map<*, *>) return null
                        val attrMap = mutableMapOf<String, String>()
                        for ((key, value) in attrs) {
                            if (key !is String || value !is String) return null
                            attrMap[key] = value
                        }
                        versionMap[version] = attrMap
                    }
                    flavorMap[flavor] = versionMap
                }
                modelMap[model] = flavorMap
            }
            catalog[hwRev] = modelMap
        }
        return catalog
    }
}

/**
 * Minimal recursive-descent JSON parser for [FirmwareCatalog.parse]. Produces
 * `Map<String, Any?>` / `List<Any?>` / `String` / `Long` / `Double` /
 * `Boolean` / `null`. Fixed-schema use only — not a general-purpose library.
 * Mirrors the JsonLite codec private to :metawear-protocol's BoardState.
 */
private object JsonLite {

    fun parse(text: String): Any? {
        val p = Parser(text)
        val value = p.parseValue()
        p.skipWhitespace()
        if (!p.isAtEnd) throw IllegalArgumentException("trailing characters at ${p.pos}")
        return value
    }

    private class Parser(private val text: String) {
        var pos = 0
        val isAtEnd: Boolean get() = pos >= text.length

        fun parseValue(): Any? {
            skipWhitespace()
            if (isAtEnd) throw IllegalArgumentException("unexpected end of input")
            return when (val c = text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                else ->
                    if (c == '-' || c.isDigit()) parseNumber()
                    else throw IllegalArgumentException("unexpected character '$c' at $pos")
            }
        }

        fun skipWhitespace() {
            while (!isAtEnd && text[pos].isWhitespace()) pos++
        }

        private fun expect(c: Char) {
            if (isAtEnd || text[pos] != c) throw IllegalArgumentException("expected '$c' at $pos")
            pos++
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val map = mutableMapOf<String, Any?>()
            skipWhitespace()
            if (!isAtEnd && text[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                map[key] = parseValue()
                skipWhitespace()
                when {
                    !isAtEnd && text[pos] == ',' -> pos++
                    !isAtEnd && text[pos] == '}' -> {
                        pos++
                        return map
                    }
                    else -> throw IllegalArgumentException("expected ',' or '}' at $pos")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val list = mutableListOf<Any?>()
            skipWhitespace()
            if (!isAtEnd && text[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                list.add(parseValue())
                skipWhitespace()
                when {
                    !isAtEnd && text[pos] == ',' -> pos++
                    !isAtEnd && text[pos] == ']' -> {
                        pos++
                        return list
                    }
                    else -> throw IllegalArgumentException("expected ',' or ']' at $pos")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (isAtEnd) throw IllegalArgumentException("unterminated string")
                when (val c = text[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (isAtEnd) throw IllegalArgumentException("unterminated escape")
                        when (val e = text[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) throw IllegalArgumentException("bad \\u escape")
                                sb.append(text.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): Any {
            val start = pos
            if (!isAtEnd && text[pos] == '-') pos++
            while (!isAtEnd && (text[pos].isDigit() || text[pos] in ".eE+-")) pos++
            val raw = text.substring(start, pos)
            return raw.toLongOrNull() ?: raw.toDoubleOrNull()
                ?: throw IllegalArgumentException("bad number '$raw'")
        }

        private fun <T> parseLiteral(literal: String, value: T): T {
            if (!text.startsWith(literal, pos)) {
                throw IllegalArgumentException("unexpected token at $pos")
            }
            pos += literal.length
            return value
        }
    }
}
