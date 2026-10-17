package lol.rowe.blocker;

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
     * True when the child may ask for another app. A parent who is already
     * looking at as many requests as they will takes no more until they answer;
     * an older Home Assistant sends no counts, and the child stays free to ask.
     */
    static boolean canRequest(Policy policy) {
        return policy != null && !policy.requestBudget.exhausted();
    }
}
