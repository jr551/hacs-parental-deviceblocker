package lol.rowe.blocker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AppConfigTest {
    @Test
    public void trailingSlashesAreRemovedWithoutARegularExpression() {
        AppConfig config = new AppConfig(
                "https://homeassistant.example////", "child-phone", "device-key");

        assertEquals("https://homeassistant.example", config.homeAssistantUrl);
    }

    @Test
    public void veryLongSlashSuffixIsHandledInLinearTime() {
        StringBuilder input = new StringBuilder("https://homeassistant.example");
        for (int i = 0; i < 100_000; i++) {
            input.append('/');
        }

        AppConfig config = new AppConfig(input.toString(), "child-phone", "device-key");

        assertEquals("https://homeassistant.example", config.homeAssistantUrl);
    }

    @Test
    public void httpsAndLocalHttpAreAccepted() {
        assertTrue(AppConfig.acceptsHomeAssistantUrl("https://homeassistant.example"));
        assertTrue(AppConfig.acceptsHomeAssistantUrl("http://192.168.1.20:8123"));
        assertTrue(AppConfig.acceptsHomeAssistantUrl("http://10.1.2.3:8123"));
        assertTrue(AppConfig.acceptsHomeAssistantUrl("http://172.16.0.4:8123"));
        assertTrue(AppConfig.acceptsHomeAssistantUrl("http://127.0.0.1:8123"));
        assertTrue(AppConfig.acceptsHomeAssistantUrl("http://homeassistant.local:8123"));
        assertTrue(AppConfig.acceptsHomeAssistantUrl("http://homeassistant:8123"));
        assertTrue(AppConfig.acceptsHomeAssistantUrl("http://[::1]:8123"));
    }

    @Test
    public void publicHttpAndEdgeAddressesAreRejected() {
        assertFalse(AppConfig.acceptsHomeAssistantUrl("http://homeassistant.example"));
        assertFalse(AppConfig.acceptsHomeAssistantUrl("http://8.8.8.8"));
        assertFalse(AppConfig.acceptsHomeAssistantUrl("http://172.15.0.4"));
        assertFalse(AppConfig.acceptsHomeAssistantUrl("http://172.32.0.4"));
        assertFalse(AppConfig.acceptsHomeAssistantUrl("http://[2001:db8::1]/"));
        assertFalse(AppConfig.acceptsHomeAssistantUrl("ftp://localhost"));
        assertFalse(AppConfig.acceptsHomeAssistantUrl("not a url"));
    }
}
