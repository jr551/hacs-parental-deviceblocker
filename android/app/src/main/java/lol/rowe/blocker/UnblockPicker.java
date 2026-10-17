package lol.rowe.blocker;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The "request another app" picker on the block screen: which of the phone's
 * apps it offers, and whether the child may ask at all. The requests
 * themselves go through {@link UnblockRequest} and the same endpoint the
 * per-app button uses, so there is only one way to ask a parent.
 */
final class UnblockPicker {
    private UnblockPicker() {
    }

    /**
     * The apps the picker offers: the phone's launchable apps, without this
     * app itself, sorted by label so the list is stable between openings.
     */
    static List<InstalledApps.App> choices(List<InstalledApps.App> installed, String ownPackage) {
        List<InstalledApps.App> choices = new ArrayList<>();
        if (installed == null) {
            return choices;
        }
        String own = ownPackage == null ? "" : ownPackage;
        for (InstalledApps.App app : installed) {
            if (app == null || app.packageName.isEmpty() || app.packageName.equals(own)) {
                continue;
            }
            choices.add(app);
        }
        Collections.sort(choices, (left, right) -> {
            int byLabel = left.label.compareToIgnoreCase(right.label);
            return byLabel != 0 ? byLabel : left.packageName.compareTo(right.packageName);
        });
        return choices;
    }

    /**
     * True when the child may ask for another app now: a parent must have room
     * for the request, and this phone must not have asked once today already.
     *
     * @param lastRequestAt when this phone last asked about any app, used only
     *     when an older Home Assistant sends no daily flag
     */
    static boolean canRequest(Policy policy, long lastRequestAt, long now) {
        return policy != null
                && !policy.requestBudget.exhausted()
                && allowedToday(policy, lastRequestAt, now);
    }

    /**
     * True when Home Assistant will take another unblock request today. The
     * payload is authoritative; the phone's own calendar check is a fallback
     * for an older Home Assistant that does not send the flag, so a phone
     * whose clock is wrong cannot talk itself past the server's rule.
     */
    static boolean allowedToday(Policy policy, long lastRequestAt, long now) {
        Boolean fromServer = policy == null ? null : policy.unblockRequestAllowedToday;
        return fromServer != null ? fromServer : UnblockRequest.mayRequest(lastRequestAt, now);
    }

    /**
     * Why the picker is closed, or "" while it is open. A disabled button with
     * no reason reads as a fault rather than a rule, so each refusal says what
     * it is waiting for and when the child can ask again.
     */
    static String closedReason(Policy policy, long lastRequestAt, long now) {
        if (policy == null) {
            return "Requests are unavailable right now.";
        }
        if (policy.requestBudget.exhausted()) {
            return "A parent is already looking at your requests.";
        }
        if (allowedToday(policy, lastRequestAt, now)) {
            return "";
        }
        return "A parent has already been asked today. You can ask again "
                + nextRequestText(policy, now) + ".";
    }

    /**
     * When the next request opens, as words: "tomorrow", "after 00:00" or
     * "on 3 November". Home Assistant's own timestamp wins; without one, the
     * local rule only ever allows a request on the next day.
     */
    static String nextRequestText(Policy policy, long now) {
        Instant next = policy == null ? null : policy.unblockRequestNextAt;
        if (next == null || !next.isAfter(Instant.ofEpochMilli(now))) {
            return "tomorrow";
        }
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime at = next.atZone(zone);
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        if (at.toLocalDate().equals(today)) {
            return "after " + at.format(DateTimeFormatter.ofPattern("HH:mm"));
        }
        if (at.toLocalDate().equals(today.plusDays(1))) {
            return "tomorrow";
        }
        return "on " + at.format(DateTimeFormatter.ofPattern("d MMMM"));
    }
}
