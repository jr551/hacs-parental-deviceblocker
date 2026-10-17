package lol.rowe.blocker;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The submit call against a real HTTP server. The phone hands the challenge to
 * a parent and keeps the lock up, so only a 200 may show the wait, a 404 must
 * let the lock go, and a 409 must leave the child on the form with Home
 * Assistant's reason. The evidence upload shares the path, and its 404 is the
 * same "the challenge is over" answer.
 */
public class ChallengeSubmitTest {
    private StubServer server;

    @Before
    public void startServer() throws Exception {
        server = new StubServer();
    }

    @After
    public void stopServer() throws Exception {
        server.close();
    }

    private HomeAssistantClient client() {
        return new HomeAssistantClient(new AppConfig(
                "http://127.0.0.1:" + server.port(), "phone1", "device-key"));
    }

    @Test
    public void aTwoHundredHandsTheChallengeToAParentAndKeepsTheLock() throws Exception {
        server.answer(200, "{\"ok\": true, \"status\": \"submitted\"}");

        Challenge.Outcome outcome = client().submitChallenge("abc123");
        server.await();

        assertTrue(outcome.accepted);
        assertFalse(outcome.gone);
        assertEquals("Waiting for a parent to approve.", outcome.message);
        assertEquals("POST", server.method.get());
        assertEquals("/api/rowe_pc_blocker/phone1/challenge/submit", server.path.get());
        assertEquals("device-key", server.deviceKey.get());
        assertEquals("{\"id\":\"abc123\"}", server.body.get());
    }

    @Test
    public void aFourOhFourMeansTheChallengeIsOverAndTheLockGoes() throws Exception {
        server.answer(404, "{\"error\": \"That challenge is no longer active.\"}");

        Challenge.Outcome outcome = client().submitChallenge("abc123");
        server.await();

        assertFalse(outcome.accepted);
        assertTrue(outcome.gone);
        assertEquals("That challenge is no longer active.", outcome.message);
    }

    @Test
    public void aFourOhNineKeepsTheChildOnTheFormWithTheReason() throws Exception {
        server.answer(409, "{\"error\": \"Take the photo first.\"}");

        Challenge.Outcome outcome = client().submitChallenge("abc123");
        server.await();

        assertFalse(outcome.accepted);
        assertFalse(outcome.gone);
        assertEquals("Take the photo first.", outcome.message);
    }

    @Test
    public void anEvidenceUploadWithoutAChallengeAlsoLetsTheLockGo() throws Exception {
        server.answer(404, "{\"error\": \"That challenge is no longer active.\"}");

        Challenge.Outcome outcome = client().uploadChallengeEvidence(new byte[] {1, 2, 3});
        server.await();

        assertFalse(outcome.accepted);
        assertTrue(outcome.gone);
        assertEquals("/api/rowe_pc_blocker/phone1/challenge/evidence", server.path.get());
        assertEquals("image/jpeg", server.contentType.get());
    }

    @Test
    public void anAnswerWithNoBodyStillLeavesTheFormUp() {
        Challenge.Outcome outcome = HomeAssistantClient.readSubmitResponse(500, "");

        assertFalse(outcome.accepted);
        assertFalse(outcome.gone);
        assertEquals("Home Assistant refused the answer (500).", outcome.message);
    }

    @Test
    public void aFourOhFourWithNoBodyStillLetsTheLockGo() {
        Challenge.Outcome outcome = HomeAssistantClient.readSubmitResponse(404, "");

        assertFalse(outcome.accepted);
        assertTrue(outcome.gone);
        assertEquals("That challenge is no longer active.", outcome.message);
    }

    /**
     * A one-shot HTTP server: it records the next request and answers it with
     * one status and body. The unit tests compile against android.jar, which
     * has no {@code com.sun.net.httpserver}, so this is a plain socket.
     */
    private static final class StubServer implements Closeable {
        private final ServerSocket socket;
        private final AtomicReference<String> method = new AtomicReference<>("");
        private final AtomicReference<String> path = new AtomicReference<>("");
        private final AtomicReference<String> deviceKey = new AtomicReference<>("");
        private final AtomicReference<String> contentType = new AtomicReference<>("");
        private final AtomicReference<String> body = new AtomicReference<>("");
        private int status = 200;
        private String response = "";
        private Thread worker;

        StubServer() throws IOException {
            socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        }

        int port() {
            return socket.getLocalPort();
        }

        /** Answer the next request from a background thread. */
        void answer(int status, String response) {
            this.status = status;
            this.response = response;
            worker = new Thread(this::serve);
            worker.setDaemon(true);
            worker.start();
        }

        /** Wait for the request to be answered and recorded. */
        void await() throws InterruptedException {
            worker.join(5_000);
        }

        private void serve() {
            try (Socket connection = socket.accept()) {
                InputStream input = connection.getInputStream();
                ByteArrayOutputStream head = new ByteArrayOutputStream();
                while (!endsWithBlankLine(head)) {
                    int next = input.read();
                    if (next == -1) {
                        return;
                    }
                    head.write(next);
                }
                String[] lines = new String(head.toByteArray(), StandardCharsets.ISO_8859_1)
                        .split("\r\n");
                String[] requestLine = lines[0].split(" ");
                method.set(requestLine[0]);
                path.set(requestLine[1]);
                int length = 0;
                for (String line : lines) {
                    int colon = line.indexOf(':');
                    if (colon < 0) {
                        continue;
                    }
                    String name = line.substring(0, colon).trim();
                    String value = line.substring(colon + 1).trim();
                    if ("Content-Length".equalsIgnoreCase(name)) {
                        length = Integer.parseInt(value);
                    } else if ("X-Device-Blocker-Key".equalsIgnoreCase(name)) {
                        deviceKey.set(value);
                    } else if ("Content-Type".equalsIgnoreCase(name)) {
                        contentType.set(value);
                    }
                }
                byte[] payload = new byte[length];
                int read = 0;
                while (read < length) {
                    int got = input.read(payload, read, length - read);
                    if (got == -1) {
                        break;
                    }
                    read += got;
                }
                body.set(new String(payload, 0, read, StandardCharsets.UTF_8));
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                String out = "HTTP/1.1 " + status + " Status\r\n"
                        + "Content-Type: application/json\r\n"
                        + "Content-Length: " + bytes.length + "\r\n"
                        + "Connection: close\r\n\r\n";
                OutputStream output = connection.getOutputStream();
                output.write(out.getBytes(StandardCharsets.ISO_8859_1));
                output.write(bytes);
                output.flush();
            } catch (IOException ignored) {
                // The test's own failure is the assertion, not this socket.
            }
        }

        private static boolean endsWithBlankLine(ByteArrayOutputStream head) {
            byte[] bytes = head.toByteArray();
            int size = bytes.length;
            return size >= 4
                    && bytes[size - 4] == '\r' && bytes[size - 3] == '\n'
                    && bytes[size - 2] == '\r' && bytes[size - 1] == '\n';
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
