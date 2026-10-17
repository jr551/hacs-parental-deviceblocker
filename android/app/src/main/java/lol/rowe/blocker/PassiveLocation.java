package lol.rowe.blocker;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.List;

/**
 * Deliberately power-frugal location reporting.
 *
 * <p>Cached fixes are read on every heartbeat and cost nothing. When the newest
 * cached fix is older than {@link #FRESH_FIX_MILLIS}, one update is requested
 * from the cheapest enabled provider (network before GPS) so a phone that
 * nothing else locates still reports a position. That request is throttled to
 * at most one per {@link #REQUEST_INTERVAL_MILLIS}, is bounded by a short
 * timeout, and never blocks the heartbeat: it is fire-and-forget, and the next
 * heartbeat picks the fix up from the provider's cache.
 *
 * <p>Positions ride along on the existing hourly report rather than triggering
 * their own network wakeups.
 */
final class PassiveLocation {
    /** Older than this and it is not worth sending. */
    private static final long MAX_AGE_MILLIS = 6L * 60L * 60L * 1000L;
    /** A cached fix older than this triggers one throttled fresh-fix request. */
    static final long FRESH_FIX_MILLIS = 15L * 60L * 1000L;
    /** At most one fresh-fix request in this window. */
    static final long REQUEST_INTERVAL_MILLIS = 15L * 60L * 1000L;
    /** How long a requested fix may take before it is abandoned. */
    private static final long REQUEST_TIMEOUT_MILLIS = 20_000L;

    /** Elapsed time of the last fresh-fix request; 0 means none yet. */
    private static volatile long lastRequestElapsedMs;

    private PassiveLocation() {
    }

    static boolean hasPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * True when a fresh fix is worth asking for: the cache is stale and no
     * request has been made recently.
     */
    static boolean shouldRequestFreshFix(long newestFixAgeMillis, long millisSinceLastRequest) {
        return newestFixAgeMillis > FRESH_FIX_MILLIS
                && millisSinceLastRequest >= REQUEST_INTERVAL_MILLIS;
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
        Location best = newestCached(manager);
        long age = best == null
                ? Long.MAX_VALUE
                : System.currentTimeMillis() - best.getTime();
        if (shouldRequestFreshFix(age, SystemClock.elapsedRealtime() - lastRequestElapsedMs)) {
            requestFreshFix(context, manager);
        }
        return age > MAX_AGE_MILLIS ? null : best;
    }

    private static Location newestCached(LocationManager manager) {
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
        return best;
    }

    /**
     * Ask the cheapest enabled provider for one fix. The result is not read
     * here: it lands in the provider's cache for the next heartbeat.
     */
    private static void requestFreshFix(Context context, LocationManager manager) {
        String provider = preferredProvider(manager);
        if (provider == null) {
            return;
        }
        lastRequestElapsedMs = SystemClock.elapsedRealtime();
        Handler main = new Handler(Looper.getMainLooper());
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.os.CancellationSignal cancellation =
                        new android.os.CancellationSignal();
                manager.getCurrentLocation(
                        provider, cancellation, context.getMainExecutor(), location -> { });
                main.postDelayed(cancellation::cancel, REQUEST_TIMEOUT_MILLIS);
            } else {
                manager.requestSingleUpdate(provider, NO_OP_LISTENER, Looper.getMainLooper());
                main.postDelayed(
                        () -> {
                            try {
                                manager.removeUpdates(NO_OP_LISTENER);
                            } catch (SecurityException ignored) {
                                // Permission was revoked; nothing left to remove.
                            }
                        },
                        REQUEST_TIMEOUT_MILLIS);
            }
        } catch (SecurityException | IllegalArgumentException exception) {
            // Permission was revoked or the provider disappeared; the cached
            // read still stands.
        }
    }

    /** Network location first: it is cheaper than a GPS fix. */
    private static String preferredProvider(LocationManager manager) {
        for (String provider : new String[] {
                LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER}) {
            try {
                if (manager.isProviderEnabled(provider)) {
                    return provider;
                }
            } catch (IllegalArgumentException ignored) {
                // The provider does not exist on this device.
            }
        }
        return null;
    }

    /**
     * Explicit rather than a lambda: the interface's default methods only exist
     * from API 30, and this listener is also used on API 29.
     */
    private static final LocationListener NO_OP_LISTENER = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };
}
