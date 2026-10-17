package lol.rowe.blocker;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class ProvisionFlowTest {
    /** Answers from a scripted list of poll outcomes. */
    private static final class ScriptedTransport implements ProvisionFlow.Transport {
        private final ProvisionFlow.Outcome start;
        private final List<ProvisionFlow.Outcome> polls = new ArrayList<>();
        private final List<String> polledRequestIds = new ArrayList<>();
        private int pollIndex;

        ScriptedTransport(ProvisionFlow.Outcome start) {
            this.start = start;
        }

        ScriptedTransport poll(ProvisionFlow.Outcome outcome) {
            polls.add(outcome);
            return this;
        }

        @Override
        public ProvisionFlow.Outcome start(String childName) {
            return start;
        }

        @Override
        public ProvisionFlow.Outcome poll(String requestId) {
            polledRequestIds.add(requestId);
            if (pollIndex >= polls.size()) {
                return ProvisionFlow.Outcome.pending(requestId, "");
            }
            return polls.get(pollIndex++);
        }
    }

    /** Never really sleeps; advances by the requested interval. */
    private static final class FakeClock implements ProvisionFlow.Clock {
        private final List<Long> waits = new ArrayList<>();
        private long now;
        private boolean interrupted;

        @Override
        public long now() {
            return now;
        }

        @Override
        public boolean await(long millis) {
            waits.add(millis);
            if (interrupted) {
                return false;
            }
            now += millis;
            return true;
        }
    }

    private static final class WaitingListener implements ProvisionFlow.Listener {
        private int calls;

        @Override
        public void onWaiting() {
            calls++;
        }
    }

    private static ProvisionFlow.Outcome pending() {
        return ProvisionFlow.Outcome.pending("0123456789abcdef0123456789abcdef", "Casey");
    }

    @Test
    public void approvalAfterWaitingSavesTheCredentials() {
        ScriptedTransport transport = new ScriptedTransport(pending())
                .poll(ProvisionFlow.Outcome.pending("0123456789abcdef0123456789abcdef", "Casey"))
                .poll(ProvisionFlow.Outcome.pending("0123456789abcdef0123456789abcdef", "Casey"))
                .poll(ProvisionFlow.Outcome.approved("casey-phone", "secret-key", "Casey"));
        FakeClock clock = new FakeClock();
        WaitingListener listener = new WaitingListener();

        ProvisionFlow.Outcome outcome =
                new ProvisionFlow(transport, clock).run("Casey", listener);

        assertEquals(ProvisionFlow.Status.APPROVED, outcome.status);
        assertEquals("casey-phone", outcome.deviceId);
        assertEquals("secret-key", outcome.deviceKey);
        assertEquals("Casey", outcome.childName);
        assertEquals(3, transport.polledRequestIds.size());
        assertEquals(
                "0123456789abcdef0123456789abcdef",
                transport.polledRequestIds.get(0));
        assertEquals(1, listener.calls);
        for (Long wait : clock.waits) {
            assertEquals(ProvisionFlow.POLL_INTERVAL_MILLIS, wait.longValue());
        }
    }

    @Test
    public void rejectionEndsTheWaitWithTheServerMessage() {
        ScriptedTransport transport = new ScriptedTransport(pending())
                .poll(ProvisionFlow.Outcome.rejected("A parent declined this setup."));

        ProvisionFlow.Outcome outcome =
                new ProvisionFlow(transport, new FakeClock()).run("Casey", null);

        assertEquals(ProvisionFlow.Status.REJECTED, outcome.status);
        assertEquals("A parent declined this setup.", outcome.message);
        assertEquals(1, transport.polledRequestIds.size());
    }

    @Test
    public void expiryEndsTheWait() {
        ScriptedTransport transport = new ScriptedTransport(pending())
                .poll(ProvisionFlow.Outcome.expired("That setup request has expired."));

        ProvisionFlow.Outcome outcome =
                new ProvisionFlow(transport, new FakeClock()).run("Casey", null);

        assertEquals(ProvisionFlow.Status.EXPIRED, outcome.status);
        assertEquals("That setup request has expired.", outcome.message);
    }

    @Test
    public void transientPollFailuresKeepWaiting() {
        ScriptedTransport transport = new ScriptedTransport(pending())
                .poll(ProvisionFlow.Outcome.error("Home Assistant could not be reached."))
                .poll(ProvisionFlow.Outcome.error("Home Assistant could not be reached."))
                .poll(ProvisionFlow.Outcome.approved("casey-phone", "secret-key", "Casey"));

        ProvisionFlow.Outcome outcome =
                new ProvisionFlow(transport, new FakeClock()).run("Casey", null);

        assertEquals(ProvisionFlow.Status.APPROVED, outcome.status);
        assertEquals(3, transport.polledRequestIds.size());
    }

    @Test
    public void theWaitGivesUpAfterThirtyMinutes() {
        ScriptedTransport transport = new ScriptedTransport(pending());
        FakeClock clock = new FakeClock();

        ProvisionFlow.Outcome outcome =
                new ProvisionFlow(transport, clock).run("Casey", null);

        assertEquals(ProvisionFlow.Status.ERROR, outcome.status);
        assertTrue(outcome.message.contains("30 minutes"));
        assertEquals(
                ProvisionFlow.TIMEOUT_MILLIS / ProvisionFlow.POLL_INTERVAL_MILLIS,
                transport.polledRequestIds.size());
        assertEquals(ProvisionFlow.TIMEOUT_MILLIS, clock.now());
    }

    @Test
    public void anAlreadyApprovedRequestSkipsTheWait() {
        ScriptedTransport transport = new ScriptedTransport(
                ProvisionFlow.Outcome.approved("casey-phone", "secret-key", "Casey"));
        WaitingListener listener = new WaitingListener();

        ProvisionFlow.Outcome outcome =
                new ProvisionFlow(transport, new FakeClock()).run("Casey", listener);

        assertEquals(ProvisionFlow.Status.APPROVED, outcome.status);
        assertEquals("casey-phone", outcome.deviceId);
        assertEquals(0, transport.polledRequestIds.size());
        assertEquals(0, listener.calls);
    }

    @Test
    public void anUnknownChildFailsImmediately() {
        ScriptedTransport transport = new ScriptedTransport(
                ProvisionFlow.Outcome.error("That name is not a child in Home Assistant."));
        WaitingListener listener = new WaitingListener();

        ProvisionFlow.Outcome outcome =
                new ProvisionFlow(transport, new FakeClock()).run("Nobody", listener);

        assertEquals(ProvisionFlow.Status.ERROR, outcome.status);
        assertEquals("That name is not a child in Home Assistant.", outcome.message);
        assertEquals(0, transport.polledRequestIds.size());
        assertEquals(0, listener.calls);
    }

    @Test
    public void anInterruptedWaitStopsTheFlow() {
        ScriptedTransport transport = new ScriptedTransport(pending());
        FakeClock clock = new FakeClock();
        clock.interrupted = true;

        ProvisionFlow.Outcome outcome =
                new ProvisionFlow(transport, clock).run("Casey", null);

        assertEquals(ProvisionFlow.Status.ERROR, outcome.status);
        assertEquals("Setup was cancelled.", outcome.message);
        assertEquals(0, transport.polledRequestIds.size());
    }
}
