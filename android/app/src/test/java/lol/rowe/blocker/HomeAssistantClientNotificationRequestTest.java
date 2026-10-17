package lol.rowe.blocker;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The on-demand notification request on the state payload, and the request id
 * the answer carries back.
 */
public class HomeAssistantClientNotificationRequestTest {
    @Test
    public void readsAPendingNotificationRequest() throws Exception {
        Policy.NotificationRequest request = HomeAssistantClient.readNotificationRequest(
                new JSONObject("{\"notification_request\":{\"id\":\" a1b2c3 \","
                        + "\"requested_at\":\"2026-10-17T13:00:00+00:00\"}}"));

        assertEquals("a1b2c3", request.id);
        assertEquals("2026-10-17T13:00:00+00:00", request.requestedAt);
    }

    @Test
    public void aRequestWithoutATimestampIsStillAnswered() throws Exception {
        Policy.NotificationRequest request = HomeAssistantClient.readNotificationRequest(
                new JSONObject("{\"notification_request\":{\"id\":\"a1b2c3\"}}"));

        assertEquals("a1b2c3", request.id);
        assertEquals("", request.requestedAt);
    }

    @Test
    public void aMissingRequestMeansNothingIsPending() throws Exception {
        assertNull(HomeAssistantClient.readNotificationRequest(new JSONObject("{}")));
        assertNull(HomeAssistantClient.readNotificationRequest(
                new JSONObject("{\"notification_request\":null}")));
    }

    @Test
    public void aMalformedRequestIsIgnoredRatherThanFailingThePolicy() throws Exception {
        // The wrong type must not crash the whole state read.
        assertNull(HomeAssistantClient.readNotificationRequest(
                new JSONObject("{\"notification_request\":\"a1b2c3\"}")));
        // An id is what the answer is addressed to; without one there is
        // nothing a reply could refer to.
        assertNull(HomeAssistantClient.readNotificationRequest(
                new JSONObject("{\"notification_request\":{}}")));
        assertNull(HomeAssistantClient.readNotificationRequest(
                new JSONObject("{\"notification_request\":{\"id\":\"   \"}}")));
    }

    @Test
    public void theUploadCarriesTheRequestIdOnlyWhenThereIsOne() throws Exception {
        String payload = "{\"date\":\"2026-10-17\",\"count\":1,"
                + "\"truncated\":false,\"items\":[]}";

        JSONObject tagged = new JSONObject(
                HomeAssistantClient.withRequestId(payload, " a1b2c3 "));
        assertEquals("a1b2c3", tagged.getString("request_id"));
        assertEquals("2026-10-17", tagged.getString("date"));
        assertEquals(1, tagged.getInt("count"));

        // The midnight flush answers no request, so its body is the buffered
        // payload untouched.
        assertEquals(payload, HomeAssistantClient.withRequestId(payload, null));
        assertEquals(payload, HomeAssistantClient.withRequestId(payload, "  "));
    }
}
