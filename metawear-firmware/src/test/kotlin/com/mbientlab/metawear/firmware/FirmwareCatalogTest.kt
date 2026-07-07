package com.mbientlab.metawear.firmware

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Ported from MWFirmwareCatalogTests.swift — coverage for the JSON catalog
// parser and the build-selection logic on top of it. Uses inline JSON
// fixtures mirroring the real `info2.json` shape so a future server-side
// change is easy to spot.

/** Port of the "MWFirmwareCatalog" suite. */
class FirmwareCatalogTest {

    companion object {
        /**
         * A small but realistic catalog covering two hardware revs, two
         * models, two build flavors, and three firmware versions with varying
         * `min-ios-version` floors.
         */
        val catalogJSON = """
        {
          "0.4": {
            "5": {
              "vanilla": {
                "1.5.0": {
                  "filename": "firmware.zip",
                  "required-bootloader": "0.4",
                  "min-ios-version": "3.0.0"
                },
                "1.7.3": {
                  "filename": "firmware.zip",
                  "required-bootloader": "0.5",
                  "min-ios-version": "3.2.0"
                },
                "2.0.0-beta": {
                  "filename": "firmware.zip",
                  "required-bootloader": "0.5",
                  "min-ios-version": "4.0.0"
                }
              },
              "bootloader": {
                "0.5": {
                  "filename": "bootloader.zip",
                  "required-bootloader": "0.4",
                  "min-ios-version": "3.0.0"
                }
              }
            },
            "6": {
              "vanilla": {
                "2.0.0": {
                  "filename": "firmware.zip",
                  "required-bootloader": "0.7",
                  "min-ios-version": "3.2.0"
                }
              }
            }
          },
          "0.3": {
            "5": {
              "vanilla": {
                "1.0.0": {
                  "filename": "firmware.bin",
                  "required-bootloader": "",
                  "min-ios-version": "1.0.0"
                }
              }
            }
          }
        }
        """.trimIndent()
    }

    // ---- parse ----

    @Test
    fun `parse acceptsValidJSON`() {
        val json = FirmwareCatalog.parse(catalogJSON)
        assertEquals(3, json["0.4"]?.get("5")?.get("vanilla")?.size)
        assertEquals(1, json["0.4"]?.get("6")?.get("vanilla")?.size)
        assertEquals(1, json["0.3"]?.get("5")?.get("vanilla")?.size)
    }

    @Test
    fun `parse rejectsMalformedJSON`() {
        assertThrows(FirmwareException::class.java) {
            FirmwareCatalog.parse("this is not json")
        }
    }

    @Test
    fun `parse rejectsWrongJSONShape`() {
        // Top-level array, not dictionary.
        assertThrows(FirmwareException::class.java) {
            FirmwareCatalog.parse("[1, 2, 3]")
        }
    }

    // ---- matchingBuilds ----

    @Test
    fun `matchingBuilds returnsOnlyMatchingTuple`() {
        val json = FirmwareCatalog.parse(catalogJSON)
        val builds = FirmwareCatalog.matchingBuilds(
            json = json,
            hardwareRev = "0.4",
            modelNumber = "5",
            buildFlavor = "vanilla",
            sdkVersion = "5.0.0",
        )
        assertEquals(3, builds.size)
        assertTrue(builds.all { it.hardwareRev == "0.4" })
        assertTrue(builds.all { it.modelNumber == "5" })
        assertTrue(builds.all { it.buildFlavor == "vanilla" })
    }

    @Test
    fun `matchingBuilds emptyForUnknownHardware`() {
        val json = FirmwareCatalog.parse(catalogJSON)
        val builds = FirmwareCatalog.matchingBuilds(
            json = json,
            hardwareRev = "9.9",
            modelNumber = "5",
            buildFlavor = "vanilla",
            sdkVersion = "5.0.0",
        )
        assertTrue(builds.isEmpty())
    }

    @Test
    fun `matchingBuilds emptyForUnknownModel`() {
        val json = FirmwareCatalog.parse(catalogJSON)
        val builds = FirmwareCatalog.matchingBuilds(
            json = json,
            hardwareRev = "0.4",
            modelNumber = "999",
            buildFlavor = "vanilla",
            sdkVersion = "5.0.0",
        )
        assertTrue(builds.isEmpty())
    }

    @Test
    fun `matchingBuilds emptyForUnknownFlavor`() {
        val json = FirmwareCatalog.parse(catalogJSON)
        val builds = FirmwareCatalog.matchingBuilds(
            json = json,
            hardwareRev = "0.4",
            modelNumber = "5",
            buildFlavor = "experimental",
            sdkVersion = "5.0.0",
        )
        assertTrue(builds.isEmpty())
    }

    // ---- SDK floor filtering ----

    @Test
    fun `matchingBuilds filtersByMinIosVersion`() {
        val json = FirmwareCatalog.parse(catalogJSON)

        // SDK 3.0 only accepts 1.5.0 (which requires SDK 3.0). 1.7.3 needs
        // 3.2.0, and 2.0.0-beta needs 4.0.0 — both filtered out.
        val sdk3 = FirmwareCatalog.matchingBuilds(
            json = json,
            hardwareRev = "0.4",
            modelNumber = "5",
            buildFlavor = "vanilla",
            sdkVersion = "3.0.0",
        )
        assertEquals(listOf("1.5.0"), sdk3.map { it.firmwareRev })

        // SDK 3.2 accepts 1.5.0 + 1.7.3, not the 4.0-gated beta.
        val sdk32 = FirmwareCatalog.matchingBuilds(
            json = json,
            hardwareRev = "0.4",
            modelNumber = "5",
            buildFlavor = "vanilla",
            sdkVersion = "3.2.0",
        )
        assertEquals(listOf("1.5.0", "1.7.3"), sdk32.map { it.firmwareRev })

        // SDK 4.0 accepts everything.
        val sdk4 = FirmwareCatalog.matchingBuilds(
            json = json,
            hardwareRev = "0.4",
            modelNumber = "5",
            buildFlavor = "vanilla",
            sdkVersion = "4.0.0",
        )
        assertEquals(3, sdk4.size)
    }

    // ---- Sort order ----

    @Test
    fun `matchingBuilds returnedAscendingByVersion`() {
        val json = FirmwareCatalog.parse(catalogJSON)
        val builds = FirmwareCatalog.matchingBuilds(
            json = json,
            hardwareRev = "0.4",
            modelNumber = "5",
            buildFlavor = "vanilla",
            sdkVersion = "5.0.0",
        )
        // Pairwise: each build sorts strictly before the next.
        for ((a, b) in builds.zip(builds.drop(1))) {
            assertTrue(
                a.firmwareRev.isMetaWearVersionLessThan(b.firmwareRev),
                "Expected ${a.firmwareRev} < ${b.firmwareRev} but builds are out of order",
            )
        }
    }

    // ---- Field mapping ----

    @Test
    fun `matchingBuilds mapsAttributesToBuildFields`() {
        val json = FirmwareCatalog.parse(catalogJSON)
        val builds = FirmwareCatalog.matchingBuilds(
            json = json,
            hardwareRev = "0.4",
            modelNumber = "5",
            buildFlavor = "vanilla",
            sdkVersion = "5.0.0",
        )
        val v17 = builds.firstOrNull { it.firmwareRev == "1.7.3" }
        assertNotNull(v17, "Expected 1.7.3 in matched builds")
        assertEquals("firmware.zip", v17!!.filename)
        assertEquals("0.5", v17.requiredBootloader)
        assertTrue(v17.firmwareUrl.toString().endsWith("/0.4/5/vanilla/1.7.3/firmware.zip"))
    }

    @Test
    fun `matchingBuilds handlesEmptyRequiredBootloader`() {
        // The 0.3/5 entry has `"required-bootloader": ""`. We pass it through
        // verbatim — the orchestrator interprets empty as "any".
        val json = FirmwareCatalog.parse(catalogJSON)
        val builds = FirmwareCatalog.matchingBuilds(
            json = json,
            hardwareRev = "0.3",
            modelNumber = "5",
            buildFlavor = "vanilla",
            sdkVersion = "5.0.0",
        )
        assertEquals(1, builds.size)
        assertEquals("", builds.first().requiredBootloader)
    }

    @Test
    fun `matchingBuilds skipsEntriesMissingFilename`() {
        // Synthetic catalog with one well-formed entry and one missing
        // `filename` key. The malformed entry is silently dropped rather than
        // crashing the parse.
        val badJSON = """
        {
          "0.4": { "5": { "vanilla": {
            "1.0.0": { "filename": "firmware.zip", "required-bootloader": "", "min-ios-version": "1.0" },
            "1.1.0": { "required-bootloader": "", "min-ios-version": "1.0" }
          }}}
        }
        """.trimIndent()
        val json = FirmwareCatalog.parse(badJSON)
        val builds = FirmwareCatalog.matchingBuilds(
            json = json,
            hardwareRev = "0.4",
            modelNumber = "5",
            buildFlavor = "vanilla",
            sdkVersion = "5.0.0",
        )
        assertEquals(listOf("1.0.0"), builds.map { it.firmwareRev })
    }

    @Test
    fun `matchingBuilds acceptsMissingMinIosVersion`() {
        // Catalog entries without a `min-ios-version` key are accepted by any
        // SDK — no floor means "no requirement."
        val unfilteredJSON = """
        {
          "0.4": { "5": { "vanilla": {
            "1.0.0": { "filename": "firmware.zip", "required-bootloader": "" }
          }}}
        }
        """.trimIndent()
        val json = FirmwareCatalog.parse(unfilteredJSON)
        val builds = FirmwareCatalog.matchingBuilds(
            json = json,
            hardwareRev = "0.4",
            modelNumber = "5",
            buildFlavor = "vanilla",
            sdkVersion = "1.0.0",
        )
        assertEquals(1, builds.size)
    }
}
