package lol.rowe.blocker;

/**
 * One-button setup: ask Home Assistant to start a provisioning request, then
 * wait for a parent to approve it there.
 *
 * <p>The polling state machine deliberately avoids Android and network types so
 * it can be unit-tested. {@link HomeAssistantClient} supplies the transport and
 * the activity supplies the clock.
 */
final class ProvisionFlow {
    /** How often the phone asks whether a parent has decided. */
    static final long POLL_INTERVAL_MILLIS = 5_000L;
    /** How long the phone waits before giving up on the request. */
    static final long TIMEOUT_MILLIS = 30L * 60L * 1000L;

    enum Status {
        /** No parent has decided yet; keep polling. */
        PENDING,
        /** Approved: the credentials below are ready to save. */
        APPROVED,
        /** A parent declined this setup. */
        REJECTED,
        /** Home Assistant no longer knows this request. */
        EXPIRED,
        /** The request could not be started, or an answer was unreadable. */
        ERROR
    }

    static final class Outcome {
        final Status status;
        final String requestId;
        final String deviceId;
        final String deviceKey;
        final String childName;
        final String message;

        private Outcome(
                Status status,
                String requestId,
                String deviceId,
                String deviceKey,
                String childName,
                String message) {
            this.status = status;
            this.requestId = requestId == null ? "" : requestId;
            this.deviceId = deviceId == null ? "" : deviceId;
            this.deviceKey = deviceKey == null ? "" : deviceKey;
            this.childName = childName == null ? "" : childName;
            this.message = message == null ? "" : message;
        }

        static Outcome pending(String requestId, String childName) {
            return new Outcome(Status.PENDING, requestId, "", "", childName, "");
        }

        static Outcome approved(String deviceId, String deviceKey, String childName) {
            return new Outcome(Status.APPROVED, "", deviceId, deviceKey, childName, "");
        }

        static Outcome rejected(String message) {
            return new Outcome(Status.REJECTED, "", "", "", "", message);
        }

        static Outcome expired(String message) {
            return new Outcome(Status.EXPIRED, "", "", "", "", message);
        }

        static Outcome error(String message) {
            return new Outcome(Status.ERROR, "", "", "", "", message);
        }
    }

    /** The two Home Assistant calls the flow needs. */
    interface Transport {
        Outcome start(String childName) throws Exception;

        Outcome poll(String requestId) throws Exception;
    }

    interface Clock {
        long now();

        /** Sleeps for the poll interval; false when the wait was interrupted. */
        boolean await(long millis);
    }

    /** Told once the request exists and the wait for a parent begins. */
    interface Listener {
        void onWaiting();
    }

    private final Transport transport;
    private final Clock clock;

    ProvisionFlow(Transport transport, Clock clock) {
        this.transport = transport;
        this.clock = clock;
    }

    /**
     * Runs the whole flow on the calling thread. Never throws: every failure
     * becomes an {@link Status#ERROR} outcome with a message for the screen.
     */
    Outcome run(String childName, Listener listener) {
        Outcome started;
        try {
            started = transport.start(childName);
        } catch (Exception exception) {
            return Outcome.error("Home Assistant could not be reached.");
        }
        if (started.status != Status.PENDING) {
            // Approved already (a parent approved this child earlier and the
            // credentials are still waiting), or a terminal error.
            return started;
        }
        if (listener != null) {
            listener.onWaiting();
        }
        long deadline = clock.now() + TIMEOUT_MILLIS;
        while (clock.now() < deadline) {
            if (!clock.await(POLL_INTERVAL_MILLIS)) {
                return Outcome.error("Setup was cancelled.");
            }
            Outcome polled;
            try {
                polled = transport.poll(started.requestId);
            } catch (Exception exception) {
                // A dropped poll must not end the wait for a parent.
                continue;
            }
            if (polled.status == Status.PENDING || polled.status == Status.ERROR) {
                continue;
            }
            return polled;
        }
        return Outcome.error(
                "No parent approved this setup within 30 minutes. Tap Set up this phone to try again.");
    }
}
