package com.mbientlab.metawear.app.core

import com.mbientlab.metawear.persistence.SessionSnapshot

/**
 * Groups session history by board identity.
 *
 * Grouping key is the DIS **serial** (stable across renames and hosts),
 * falling back to the device identifier when the serial is empty — never the
 * name (stock boards all say "MetaWear"; legacy records have no name at all).
 * Titles prefer the freshest stamped name; when several boards share a name,
 * the identifier is appended to disambiguate.
 */
object SessionHistoryGrouping {

    /** One board's section in the history list. */
    data class BoardSection(
        /** Grouping key: DIS serial, or the device identifier when empty. */
        val id: String,
        val title: String,
        /** Sessions newest-first (store order preserved). */
        val sessions: List<SessionSnapshot>,
    )

    /**
     * Build sections from a newest-first snapshot list.
     *
     * @param macBySerial serial → MAC translation (from remembered devices);
     *   when known, titles show the MAC — the identity users see everywhere —
     *   while grouping stays serial-keyed.
     */
    fun sections(
        from: List<SessionSnapshot>,
        macBySerial: Map<String, String> = emptyMap(),
    ): List<BoardSection> {
        val groups = LinkedHashMap<String, MutableList<SessionSnapshot>>()
        for (snapshot in from) {
            val key = snapshot.deviceSerial.ifEmpty { snapshot.deviceID }
            groups.getOrPut(key) { mutableListOf() }.add(snapshot)
        }

        // A name claimed by more than one board can't stand alone as a title.
        val namesByKey = groups.mapValues { (_, sessions) ->
            sessions.firstNotNullOfOrNull { it.deviceName?.takeIf(String::isNotEmpty) }
        }
        val sharedNames = namesByKey.values.filterNotNull()
            .groupingBy { it }.eachCount().filterValues { it > 1 }.keys

        return groups.map { (key, sessions) ->
            // Display identity comes from the SERIAL (translated to a MAC
            // when known) — the device-id fallback is a grouping key only,
            // never something to show.
            val serial = sessions.first().deviceSerial
            val displayID = macBySerial[serial] ?: serial
            val name = namesByKey[key]
            val title = when {
                name == null -> displayID.ifEmpty { "Unknown board" }
                name in sharedNames && displayID.isNotEmpty() -> "$name · $displayID"
                else -> name
            }
            BoardSection(id = key, title = title, sessions = sessions)
        }.sortedByDescending { it.sessions.first().startDate }
    }
}
