package lol.rowe.blocker;

import static org.junit.Assert.assertEquals;

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
}
