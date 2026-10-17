package lol.rowe.blocker;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class ChildDeviceTest {
    @Test
    public void deviceIdUsesTheChildName() {
        assertEquals("casey-phone", ChildDevice.deviceId("Casey"));
        assertEquals("alex-phone", ChildDevice.deviceId("  Alex "));
        assertEquals("maryann-phone", ChildDevice.deviceId("Mary Ann"));
        assertEquals("", ChildDevice.deviceId("!!!"));
    }

    @Test
    public void displayNamePrefersTheStoredName() {
        assertEquals("Casey", ChildDevice.displayName("Casey", "casey-phone"));
        assertEquals("Alex", ChildDevice.displayName("", "alex-phone"));
        assertEquals("", ChildDevice.displayName("", "desktop"));
    }
}
