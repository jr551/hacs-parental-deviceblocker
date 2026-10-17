package lol.rowe.blocker;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * On-demand location: nothing touches the provider until a parent asks for a
 * position, and then exactly one fresh fix is fetched and posted.
 *
 * <p>The phone used to read a cached fix on every heartbeat and ask for a fresh
 * one when that cache was stale. That is gone: a parent presses the button in
 * Home Assistant, the request rides the state poll, and this class answers it
 * once. A request that cannot be answered inside {@link #TIMEOUT_MILLIS} is left
 * unanswered rather than filled with a stale position.
 */
final class OnDemandLocation {
    /** How long a requested fix may take before the request is left unanswered. */
    static final long TIMEOUT_MILLIS = 20_000L;
    /** A fix older than this is not sent as if it were current. */
    static final long MAX_FIX_AGE_SECONDS = 120L;
    private static final String PREFS = "rowe_blocker";
    private static final String ANSWERED_ID = "location_request_answered";

    private OnDemandLocation() {
    }

    /** True when this request id has not been answered or given up on yet. */
    static boolean shouldFetch(String requestId, String lastRequestId) {
        String id = requestId == null ? "" : requestId.trim();
        return !id.isEmpty() && !id.equals(lastRequestId == null ? "" : lastRequestId.trim());
    }

    /** True when a fix is real, recent and inside the coordinate ranges. */
    static boolean isSendable(Double latitude, Double longitude, long fixAgeSeconds) {
        return latitude != null
                && longitude != null
                && latitude >= -90
                && latitude <= 90
                && longitude >= -180
                && longitude <= 180
                && fixAgeSeconds >= 0
                && fixAgeSeconds <= MAX_FIX_AGE_SECONDS;
    }

    static boolean hasPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** The request id this phone last answered or gave up on, or "". */
    static String lastRequestId(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(ANSWERED_ID, "");
    }

    static void rememberRequestId(Context context, String requestId) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(ANSWERED_ID, requestId == null ? "" : requestId)
                .apply();
    }

    /**
     * One fresh fix from the cheapest enabled provider, or null when none
     * arrives inside {@code timeoutMillis}. Blocks the calling thread, so it
     * must not run on the poll thread.
     */
    static Location fetch(Context context, long timeoutMillis) {
        // The check is written out here rather than calling hasPermission so
        // lint can see it: it cannot follow the helper.
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED
                && context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        LocationManager manager = context.getSystemService(LocationManager.class);
        if (manager == null) {
            return null;
        }
        String provider = bestProvider(manager);
        if (provider == null) {
            return null;
        }
        ArrayBlockingQueue<Location> fixes = new ArrayBlockingQueue<>(1);
        LocationListener listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                fixes.offer(location);
            }

            @Override
            public void onProviderEnabled(String name) {
            }

            @Override
            public void onProviderDisabled(String name) {
            }

            @Override
            public void onStatusChanged(String name, int status, Bundle extras) {
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                manager.getCurrentLocation(provider, null, context.getMainExecutor(), fixes::offer);
            } else {
                manager.requestSingleUpdate(provider, listener, Looper.getMainLooper());
            }
            return fixes.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RuntimeException exception) {
            // A provider that refuses the request is the same as no fix.
            return null;
        } finally {
            if (Build.VERSION.SDK_INT < 30) {
                try {
                    manager.removeUpdates(listener);
                } catch (RuntimeException ignored) {
                    // The provider already dropped the listener.
                }
            }
        }
    }

    /** The fused provider when the phone has it, else network, else GPS. */
    private static String bestProvider(LocationManager manager) {
        for (String name : new String[] {
                LocationManager.FUSED_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.GPS_PROVIDER}) {
            try {
                if (manager.isProviderEnabled(name)) {
                    return name;
                }
            } catch (RuntimeException ignored) {
                // An unknown provider name is simply not available.
            }
        }
        return null;
    }
}
