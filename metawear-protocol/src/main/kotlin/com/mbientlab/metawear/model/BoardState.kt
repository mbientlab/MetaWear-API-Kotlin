package com.mbientlab.metawear.model

import com.mbientlab.metawear.protocol.Module
import kotlinx.datetime.Instant

// Port of MWBoardState.swift.
//
// Persisted snapshot of a MetaWear board's post-initialize state. Lets a client
// skip the full re-discovery handshake on reconnect when the firmware revision
// and hardware revision still match.
//
// This is a deliberately SDK-native shape (JSON) rather than the C++ SDK's
// binary blob. The C++ format is tied to internal struct layout and isn't a
// stable on-disk format. Callers who need C++ interop should keep the C++ SDK
// alongside; everyone else should prefer this.
//
// Wire format: JSON with sorted keys (deterministic output, matching the Swift
// encoder's `.sortedKeys`). Keys are stable. The `schemaVersion` integer is
// bumped on backwards-incompatible changes so callers can discard old caches.
// The module is dependency-light by design, so the fixed-schema JSON codec is
// implemented here rather than pulling in a serialization library.

class BoardState internal constructor(
    /** Schema version of this serialized payload. */
    val schemaVersion: Int,
    /** Device identity captured during the connection handshake. */
    val deviceInformation: DeviceInformation,
    /** Module discovery table captured during initialization. */
    val modules: List<ModuleInfo>,
    /**
     * Wall-clock reference: the [Instant] at which the board's logging tick was
     * 0. `null` when the logging module is not present or the tick reference
     * wasn't read.
     */
    val logReferenceDate: Instant?,
) {
    constructor(
        deviceInformation: DeviceInformation,
        modules: List<ModuleInfo>,
        logReferenceDate: Instant?,
    ) : this(CURRENT_SCHEMA_VERSION, deviceInformation, modules, logReferenceDate)

    // ---- Dictionary view ----

    /** Modules keyed by module opcode for O(1) lookup. */
    val modulesByOpcode: Map<Module, ModuleInfo>
        get() = modules.associateBy { it.module }

    // ---- Validity against a live board ----

    /**
     * Whether this state is safe to reuse with a board reporting [liveInfo]
     * without rerunning module discovery. Matches C++ `metawearboard`'s
     * firmware-match check.
     */
    fun isCompatible(liveInfo: DeviceInformation): Boolean =
        deviceInformation.firmwareRevision == liveInfo.firmwareRevision &&
            deviceInformation.hardwareRevision == liveInfo.hardwareRevision &&
            deviceInformation.modelNumber == liveInfo.modelNumber

    // ---- Serialization ----

    /** Encode to JSON (sorted keys — byte-for-byte deterministic). */
    fun encode(): String = buildString {
        append('{')
        append("\"deviceInformation\":{")
        append("\"firmwareRevision\":").append(jsonString(deviceInformation.firmwareRevision)).append(',')
        append("\"hardwareRevision\":").append(jsonString(deviceInformation.hardwareRevision)).append(',')
        append("\"manufacturer\":").append(jsonString(deviceInformation.manufacturer)).append(',')
        append("\"modelNumber\":").append(jsonString(deviceInformation.modelNumber)).append(',')
        append("\"serialNumber\":").append(jsonString(deviceInformation.serialNumber))
        append('}')
        logReferenceDate?.let {
            append(",\"logReferenceDate\":").append(jsonString(it.toString()))
        }
        append(",\"modules\":[")
        modules.forEachIndexed { i, m ->
            if (i > 0) append(',')
            append('{')
            append("\"extra\":[").append(m.extra.joinToString(",")).append("],")
            append("\"implementation\":").append(m.implementation).append(',')
            append("\"module\":").append(m.module.value).append(',')
            append("\"revision\":").append(m.revision)
            append('}')
        }
        append("],")
        append("\"schemaVersion\":").append(schemaVersion)
        append('}')
    }

    override fun equals(other: Any?): Boolean = other is BoardState &&
        schemaVersion == other.schemaVersion &&
        deviceInformation == other.deviceInformation &&
        modules == other.modules &&
        logReferenceDate == other.logReferenceDate

    override fun hashCode(): Int {
        var result = schemaVersion
        result = 31 * result + deviceInformation.hashCode()
        result = 31 * result + modules.hashCode()
        result = 31 * result + (logReferenceDate?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "BoardState(schemaVersion=$schemaVersion, deviceInformation=$deviceInformation, " +
            "modules=$modules, logReferenceDate=$logReferenceDate)"

    companion object {
        /** Bump on any breaking layout change. */
        const val CURRENT_SCHEMA_VERSION: Int = 1

        /**
         * Decode a previously-encoded state.
         *
         * @throws MetaWearException.OperationFailed if the schema is newer than
         *   this SDK supports or the JSON is malformed.
         */
        fun decode(json: String): BoardState {
            val root = try {
                parseBoardStateJson(json)
            } catch (e: Throwable) {
                throw MetaWearException.OperationFailed("Failed to decode board state: ${e.message}")
            }
            if (root.schemaVersion > CURRENT_SCHEMA_VERSION) {
                throw MetaWearException.OperationFailed(
                    "Board state schema ${root.schemaVersion} is newer than supported ($CURRENT_SCHEMA_VERSION)",
                )
            }
            return root
        }

        private fun parseBoardStateJson(json: String): BoardState {
            val root = JsonLite.parse(json) as? Map<*, *>
                ?: throw IllegalArgumentException("root is not a JSON object")
            val schemaVersion = (root["schemaVersion"] as? Long)?.toInt()
                ?: throw IllegalArgumentException("missing schemaVersion")
            val info = root["deviceInformation"] as? Map<*, *>
                ?: throw IllegalArgumentException("missing deviceInformation")
            val deviceInformation = DeviceInformation(
                manufacturer = info.requireString("manufacturer"),
                modelNumber = info.requireString("modelNumber"),
                serialNumber = info.requireString("serialNumber"),
                firmwareRevision = info.requireString("firmwareRevision"),
                hardwareRevision = info.requireString("hardwareRevision"),
            )
            val modules = (root["modules"] as? List<*> ?: emptyList<Any>()).map { entry ->
                val m = entry as? Map<*, *> ?: throw IllegalArgumentException("module entry is not an object")
                val opcode = (m["module"] as? Long)?.toInt()
                    ?: throw IllegalArgumentException("module entry missing opcode")
                ModuleInfo(
                    module = Module.from(opcode)
                        ?: throw IllegalArgumentException("unknown module opcode $opcode"),
                    implementation = (m["implementation"] as? Long)?.toInt() ?: 0xFF,
                    revision = (m["revision"] as? Long)?.toInt() ?: 0,
                    extra = (m["extra"] as? List<*> ?: emptyList<Any>()).map { (it as Long).toInt() },
                )
            }
            val logReferenceDate = (root["logReferenceDate"] as? String)?.let { Instant.parse(it) }
            return BoardState(schemaVersion, deviceInformation, modules, logReferenceDate)
        }

        private fun Map<*, *>.requireString(key: String): String =
            this[key] as? String ?: throw IllegalArgumentException("missing $key")

        private fun jsonString(s: String): String = buildString {
            append('"')
            for (c in s) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
                }
            }
            append('"')
        }
    }
}

/**
 * Minimal recursive-descent JSON parser for [BoardState.decode]. Produces
 * `Map<String, Any?>` / `List<Any?>` / `String` / `Long` / `Double` /
 * `Boolean` / `null`. Fixed-schema use only — not a general-purpose library.
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
            if (!isAtEnd && text[pos] == '}') { pos++; return map }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                map[key] = parseValue()
                skipWhitespace()
                when {
                    !isAtEnd && text[pos] == ',' -> pos++
                    !isAtEnd && text[pos] == '}' -> { pos++; return map }
                    else -> throw IllegalArgumentException("expected ',' or '}' at $pos")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val list = mutableListOf<Any?>()
            skipWhitespace()
            if (!isAtEnd && text[pos] == ']') { pos++; return list }
            while (true) {
                list.add(parseValue())
                skipWhitespace()
                when {
                    !isAtEnd && text[pos] == ',' -> pos++
                    !isAtEnd && text[pos] == ']' -> { pos++; return list }
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
