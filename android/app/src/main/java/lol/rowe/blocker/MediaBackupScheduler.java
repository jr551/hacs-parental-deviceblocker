package lol.rowe.blocker;

import android.Manifest;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.os.BatteryManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;

final class MediaBackupScheduler {
    static final int JOB_ID = 0x504442;
    private static final String PREFS = "media_backup_state";
    private static final String INITIAL_COMPLETE = "initial_complete";
    private static final String CONFIGURATION_ID = "configuration_id";

    private MediaBackupScheduler() {}

    static void schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) {
            return;
        }
        boolean initialComplete = isInitialComplete(context);
        JobInfo.Builder job = new JobInfo.Builder(
                JOB_ID, new ComponentName(context, MediaBackupJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setRequiresCharging(true)
                .setPersisted(true);
        if (initialComplete) {
            job.setPeriodic(15 * 60 * 1000L);
        } else {
            job.setMinimumLatency(1_000L)
                    .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL);
        }
        scheduler.schedule(job.build());
    }

    static void cancel(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler != null) {
            scheduler.cancel(JOB_ID);
        }
    }

    static boolean hasMediaPermission(Context context) {
        if (Build.VERSION.SDK_INT >= 33) {
            return context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
                            == PackageManager.PERMISSION_GRANTED
                    && context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO)
                            == PackageManager.PERMISSION_GRANTED;
        }
        return context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    static Network wifiNetwork(Context context) {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        if (manager == null) {
            return null;
        }
        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            if (capabilities != null
                    && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return network;
            }
        }
        return null;
    }

    static boolean isExternallyPowered(Context context) {
        Intent battery = context.registerReceiver(
                null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return battery != null
                && battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }

    static boolean isInitialComplete(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(INITIAL_COMPLETE, false);
    }

    static void setInitialComplete(Context context, boolean complete) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(INITIAL_COMPLETE, complete)
                .apply();
    }

    static boolean destinationChanged(Context context, String configurationId) {
        String previous = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(CONFIGURATION_ID, "");
        if (configurationId.equals(previous)) {
            return false;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(CONFIGURATION_ID, configurationId)
                .putBoolean(INITIAL_COMPLETE, false)
                .apply();
        return true;
    }
}
