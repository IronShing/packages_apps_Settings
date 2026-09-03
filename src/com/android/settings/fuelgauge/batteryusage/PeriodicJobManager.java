/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.fuelgauge.batteryusage;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemProperties;
import android.util.Log;

import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import com.android.settings.fuelgauge.BatteryUsageHistoricalLogEntry.Action;
import com.android.settings.fuelgauge.batteryusage.bugreport.BatteryUsageLogUtils;
import com.android.settings.overlay.FeatureFactory;
import com.android.settingslib.utils.ThreadUtils;

import java.time.Duration;

/** Manages the periodic job to schedule or cancel the next job. */
public final class PeriodicJobManager {
    private static final String TAG = "PeriodicJobManager";
    private static final int ALARM_MANAGER_REQUEST_CODE = TAG.hashCode();

    private static PeriodicJobManager sSingleton;

    private final Context mContext;
    private final AlarmManager mAlarmManager;

    @VisibleForTesting static long sBroadcastDelayFromBoot = Duration.ofMinutes(40).toMillis();

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    void reset() {
        sSingleton = null; // for testing only
    }

    /** Gets or creates the new {@link PeriodicJobManager} instance. */
    public static synchronized PeriodicJobManager getInstance(Context context) {
        if (sSingleton == null || sSingleton.mAlarmManager == null) {
            sSingleton = new PeriodicJobManager(context);
        }
        return sSingleton;
    }

    private PeriodicJobManager(Context context) {
        this.mContext = context.getApplicationContext();
        this.mAlarmManager = context.getSystemService(AlarmManager.class);
    }

    /** Schedules the next alarm job if it is available. */
    public void refreshJob(final boolean fromBoot) {
        if (mAlarmManager == null) {
            BatteryUsageLogUtils.writeLog(
                    mContext,
                    Action.SCHEDULE_JOB,
                    "cannot schedule next alarm job due to AlarmManager is null");
            Log.e(TAG, "cannot schedule next alarm job");
            return;
        }
        // Cancels the previous alert job and schedules the next one.
        ThreadUtils.getBackgroundExecutor().execute(
                () -> {
                    final PendingIntent pendingIntent = getPendingIntent();
                    cancelJob(pendingIntent);
                    scheduleNextJobAlarm(fromBoot, pendingIntent);
                }
        );
    }

    @WorkerThread
    private void scheduleNextJobAlarm(
            final boolean fromBoot, final PendingIntent pendingIntent) {
        // NX809J: this alarm is the single largest idle waker on a stock build. It is
        // scheduled with setExactAndAllowWhileIdle(), an alarm class Doze is NOT permitted to
        // defer, so no device_idle_constants tuning can touch it -- measured on a user dump as
        // 166 of 178 wakeups and 97% of total wake time over 22h30m, purely to refresh the
        // battery usage chart. persist.sys.rm.batteryjob_hours: 1 = stock hourly (default),
        // N = every N hours, 0 = never schedule. Exposed as "Battery stats refresh" in
        // RedMagic Control; Settings may read it because persist.sys.rm. maps to rm_ctrl_prop
        // and set_prop(system_app, rm_ctrl_prop) already exists.
        // This alarm is the largest idle waker on the device (166 of 178 wake-ups on a real dump)
        // and it is scheduled setExactAndAllowWhileIdle, so Doze is not allowed to defer it.
        //
        // It is only stretched for users who have opted into "Sleep harder when offline" -- that
        // switch is what reveals the control in RedMagic Control, so nobody gets a coarser battery
        // chart without having seen the setting that caused it. Stock hourly otherwise.
        final boolean dozeOptIn = SystemProperties.getBoolean("persist.sys.rm.doze_offline", false);
        final int everyHours = dozeOptIn
                ? SystemProperties.getInt("persist.sys.rm.batteryjob_hours", 6)
                : 1;
        if (everyHours <= 0) {
            BatteryUsageLogUtils.writeLog(
                    mContext, Action.SCHEDULE_JOB,
                    "next alarm job disabled by persist.sys.rm.batteryjob_hours=0");
            Log.d(TAG, "next alarm job disabled by persist.sys.rm.batteryjob_hours");
            return;
        }
        final long currentTimeMillis = System.currentTimeMillis();
        // Uses the timestamp of next full hour in local timezone.
        long triggerAtMillis = getTriggerAtMillis(currentTimeMillis, fromBoot);
        // Push out to the next Nth hour boundary, keeping the sharp-hour alignment.
        for (int i = 1; i < everyHours; i++) {
            triggerAtMillis = TimestampUtils.getNextHourTimestamp(triggerAtMillis);
        }
        mAlarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
        final String timeForLogging = ConvertUtils.utcToLocalTimeForLogging(triggerAtMillis);
        BatteryUsageLogUtils.writeLog(
                mContext,
                Action.SCHEDULE_JOB,
                String.format("triggerTime=%s, fromBoot=%b", timeForLogging, fromBoot));
        Log.d(TAG, "schedule next alarm job at " + timeForLogging);
    }

    @WorkerThread
    private void cancelJob(PendingIntent pendingIntent) {
        if (mAlarmManager != null) {
            mAlarmManager.cancel(pendingIntent);
        } else {
            Log.e(TAG, "cannot cancel the alarm job");
        }
    }

    /** Gets the next alarm trigger time in milliseconds. */
    @VisibleForTesting
    static long getTriggerAtMillis(final long currentTimeMillis, final boolean fromBoot) {
        final boolean delayHourlyJobWhenBooting =
                FeatureFactory.getFeatureFactory()
                        .getPowerUsageFeatureProvider()
                        .delayHourlyJobWhenBooting();
        long targetTimeMillis = TimestampUtils.getNextHourTimestamp(currentTimeMillis);
        if (delayHourlyJobWhenBooting
                && fromBoot
                && (targetTimeMillis - currentTimeMillis) <= sBroadcastDelayFromBoot) {
            // Skips this time broadcast, schedule in the next alarm trigger.
            targetTimeMillis = TimestampUtils.getNextHourTimestamp(targetTimeMillis);
        }
        return targetTimeMillis;
    }

    private PendingIntent getPendingIntent() {
        final Intent broadcastIntent =
                new Intent(mContext, PeriodicJobReceiver.class)
                        .setAction(PeriodicJobReceiver.ACTION_PERIODIC_JOB_UPDATE);
        return PendingIntent.getBroadcast(
                mContext.getApplicationContext(),
                ALARM_MANAGER_REQUEST_CODE,
                broadcastIntent,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
