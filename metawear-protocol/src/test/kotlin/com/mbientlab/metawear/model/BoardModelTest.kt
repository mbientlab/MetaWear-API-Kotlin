package com.mbientlab.metawear.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Model-number decode + hardware-revision rules. */
class BoardModelTest {

    @Test fun model5_isMotionRL() {
        val m = BoardModel.fromModelNumber("5")
        assertEquals(BoardModel.MotionRL, m)
        assertEquals("MetaMotion R / RL", m.displayName)
        assertFalse(m.hasMMS)
    }

    @Test fun model8_isMotionS() {
        val m = BoardModel.fromModelNumber("8")
        assertEquals(BoardModel.MotionS, m)
        assertTrue(m.hasMMS)
        assertEquals(listOf("r0.1"), m.supportedHardwareRevisions)
    }

    @Test fun unknownModel() {
        val m = BoardModel.fromModelNumber("99")
        assertEquals(BoardModel.Unknown("99"), m)
        assertTrue(m.supportedHardwareRevisions.isEmpty())
        assertEquals("Unknown (99)", m.displayName)
    }

    @Test fun trimsWhitespace() {
        assertEquals(BoardModel.MotionS, BoardModel.fromModelNumber(" 8 "))
    }

    @Test fun hardwareRevisionNormalization() {
        val m = BoardModel.MotionRL
        assertTrue(m.isHardwareRevisionSupported("r0.4"))
        assertTrue(m.isHardwareRevisionSupported("0.4"))   // bare form
        assertTrue(m.isHardwareRevisionSupported("R0.4"))  // case-insensitive
        assertFalse(m.isHardwareRevisionSupported("r9.9"))
    }

    @Test fun deviceInformationDerivesModel() {
        val info = DeviceInformation(
            manufacturer = "MbientLab Inc",
            modelNumber = "8",
            serialNumber = "ABC123",
            firmwareRevision = "1.7.3",
            hardwareRevision = "r0.1",
        )
        assertEquals(BoardModel.MotionS, info.model)
        assertTrue(info.isHardwareRevisionSupported)
    }
}
