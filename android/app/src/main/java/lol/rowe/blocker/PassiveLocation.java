package lol.rowe.blocker;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;

import java.util.List;

/**
 * Deliberately power-frugal location reporting.
 *
 * <p>This never asks the GPS chip to produce a fix. It only reads positions that
 * some other app has already caused the system to calculate — Android's
 * {@code PASSIVE_PROVIDER} plus each provider's last known fix — so the battery
 * cost is effectively zero. The trade-off is honest and intentional: on a phone
 * that nothing else is locating, the position can be stale or absent, and that
 * is preferable to draining a child's battery to track them.
 *
 * <p>Positions ride along on the existing 60-second heartbeat rather than
 * triggering their own network wakeups.
 */
final class PassiveLocation {
    /** Older than this and it is not worth sending. */
    private static final long MAX_AGE_MILLIS = 6L * 60L * 60L * 1000L;

    private PassiveLocation() {
    }

    static boolean hasPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * The freshest cached fix, or null when there is nothing recent enough.
     * Reading cached fixes cannot fail loudly, so every provider is tried and
     * exceptions are swallowed per provider rather than losing the whole read.
     */
    static Location latest(Context context) {
        if (!hasPermission(context)) {
            return null;
        }
        LocationManager manager =
                (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            return null;
        }
        Location best = null;
        List<String> providers = manager.getAllProviders();
        if (providers == null) {
            return null;
        }
        for (String provider : providers) {
            Location candidate;
            try {
                candidate = manager.getLastKnownLocation(provider);
            } catch (SecurityException | IllegalArgumentException exception) {
                continue;
            }
            if (candidate == null) {
                continue;
            }
            if (best == null || candidate.getTime() > best.getTime()) {
                best = candidate;
            }
        }
        if (best == null) {
            return null;
        }
        long age = System.currentTimeMillis() - best.getTime();
        return age > MAX_AGE_MILLIS ? null : best;
    }
}
