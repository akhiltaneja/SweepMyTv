package com.sweepmytv.launcher;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class DevicesTest {
    @Test public void recognizesTheNamesAnnouncedByConsolesAndStreamers() {
        assertEquals("PlayStation 5", Devices.identify("PS5", -1, -1).name);
        assertEquals("Nintendo Switch 2", Devices.identify("NintendoSwitch2", -1, -1).name);
        assertEquals("Xbox Series X", Devices.identify("Xbox Series X", -1, -1).name);
        assertEquals("Fire TV", Devices.identify("AFTMM", -1, -1).name);
        assertEquals("Apple TV", Devices.identify("", 0x0010FA, Devices.PLAYBACK).name);
        assertNotEquals(Devices.PLAIN, Devices.identify("PS5", -1, -1).color);
    }

    @Test public void usesDeviceTypeAndPreservesUnrecognizedDeviceNames() {
        assertEquals("Sound system", Devices.identify(null, -1, Devices.AUDIO).name);
        assertEquals("Set-top box", Devices.identify("", -1, Devices.TUNER).name);
        assertEquals("Living room streamer", Devices.identify("Living room streamer", -1, -1).name);
        assertEquals(Devices.PLAIN, Devices.identify(null, -1, -1).color);
    }
}
