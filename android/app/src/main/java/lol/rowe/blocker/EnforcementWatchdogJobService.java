package lol.rowe.blocker;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * Watchdog check when the foreground service has been killed. Periodic jobs
 * cannot run faster than every 15 minutes.
 */
public final class EnforcementWatchdogJobService extends JobService {
    static final int JOB_ID = 0x504457;

    static void schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) {
            return;
        }
        JobInfo job = new JobInfo.Builder(
                JOB_ID, new ComponentName(context, EnforcementWatchdogJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setPeriodic(ReportSchedule.HOURLY_REPORT_MS)
                .build();
        scheduler.schedule(job);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try {
                EnforcementReporter.checkIn(this);
            } catch (Throwable ignored) {
                // A failed check-in must not skip jobFinished, or the periodic
                // job would never be rescheduled.
            }
            AppUpdater.check(this);
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
