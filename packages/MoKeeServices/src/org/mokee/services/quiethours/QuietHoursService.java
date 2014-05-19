/*
 * Copyright (C) 2014 The MoKee OpenSource Project
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

package org.mokee.services.quiethours;

import java.util.Calendar;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.util.cm.QuietHoursUtils;
import org.mokee.services.R;

public class QuietHoursService extends Service  {

    protected static final int QUIETHOURS_NOTIFICATION_ID = 717;

    private static final String TAG = "QuietHoursService";
    private BroadcastReceiver mQuietHoursReceiver;
    private Context mContext;
    private ContentResolver mContentResolver;
    private NotificationManager nm;
    private Handler handler = new Handler();
    private Calendar cal;
    private boolean quietHoursEnabled;
    private boolean quietHoursForced;
    private boolean quietHoursWaited;
    private int quietHoursStart;
    private int quietHoursEnd;

    @Override
    public void onDestroy() {
        handler.removeCallbacks(startRunnable);
        handler.removeCallbacks(stopRunnable);
        handler.removeCallbacks(waitRunnable);
        handler.post(stopRunnable);
        unregisterReceiver();
    }

    @Override
    public void onStart(Intent intent, int startid) {
        // Log.d(TAG, "onStart");
        mContext = getApplicationContext();
        mContentResolver = mContext.getContentResolver();
        nm = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        registerBroadcastReceiver();

        // init notification
        quietHoursEnabled = Settings.System.getIntForUser(mContentResolver,
                Settings.System.QUIET_HOURS_ENABLED, 0, UserHandle.USER_CURRENT_OR_SELF) != 0;
        quietHoursForced = Settings.System.getIntForUser(mContentResolver,
                Settings.System.QUIET_HOURS_FORCED, 0, UserHandle.USER_CURRENT_OR_SELF) != 0;
        quietHoursWaited = Settings.System.getIntForUser(mContentResolver,
                Settings.System.QUIET_HOURS_WAITED, 0, UserHandle.USER_CURRENT_OR_SELF) != 0;

        // reset value
        if (quietHoursForced) {
            Settings.System.putIntForUser(mContentResolver,
                    Settings.System.QUIET_HOURS_FORCED, 0, UserHandle.USER_CURRENT_OR_SELF);
        }
        if (quietHoursWaited) {
            Settings.System.putIntForUser(mContentResolver,
                    Settings.System.QUIET_HOURS_WAITED, 0, UserHandle.USER_CURRENT_OR_SELF);
        }

        updateNotification();
    }

    private void updateNotification() {
        quietHoursEnabled = Settings.System.getIntForUser(mContentResolver,
                Settings.System.QUIET_HOURS_ENABLED, 0, UserHandle.USER_CURRENT_OR_SELF) != 0;
        quietHoursForced = Settings.System.getIntForUser(mContentResolver,
                Settings.System.QUIET_HOURS_FORCED, 0, UserHandle.USER_CURRENT_OR_SELF) != 0;
        quietHoursWaited = Settings.System.getIntForUser(mContentResolver,
                Settings.System.QUIET_HOURS_WAITED, 0, UserHandle.USER_CURRENT_OR_SELF) != 0;
        quietHoursStart = Settings.System.getIntForUser(mContentResolver,
                Settings.System.QUIET_HOURS_START, 0, UserHandle.USER_CURRENT_OR_SELF);
        quietHoursEnd = Settings.System.getIntForUser(mContentResolver,
                Settings.System.QUIET_HOURS_END, 0, UserHandle.USER_CURRENT_OR_SELF);
        if (quietHoursWaited) {
            return;
        } else if (!quietHoursEnabled && !quietHoursForced) {
            handler.removeCallbacks(stopRunnable);
            handler.post(stopRunnable);
            return;
        } else if (quietHoursEnabled && !QuietHoursUtils.inQuietHours(quietHoursStart, quietHoursEnd) && !quietHoursForced) {
            handler.removeCallbacks(stopRunnable);
            handler.post(stopRunnable);
        } else {
            addNotification();
        }

        cal = Calendar.getInstance();
        int minutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        int second = cal.get(Calendar.SECOND);
        boolean inQuietHours = false;
        if (quietHoursEnd < quietHoursStart) {
            // Starts at night, ends in the morning.
            inQuietHours =  (minutes > quietHoursStart) || (minutes < quietHoursEnd);
        } else {
            // Starts in the morning, ends at night.
            inQuietHours =  (minutes > quietHoursStart) && (minutes < quietHoursEnd);
        }

        if (inQuietHours) {
            int leftTime = quietHoursEnd > minutes ? quietHoursEnd - minutes : 1440 - minutes + quietHoursEnd;
            handler.removeCallbacks(stopRunnable);
            handler.postDelayed(stopRunnable, leftTime * 60 * 1000 - second * 1000);
        } else {
            int leftTime = quietHoursStart > minutes ? quietHoursStart - minutes : 1440 - minutes + quietHoursStart;
            handler.removeCallbacks(startRunnable);;
            handler.postDelayed(startRunnable, leftTime * 60 * 1000 - second * 1000);
        }
    }

    private void addNotification() {
        Intent mIntent = new Intent();
        mIntent.setClassName("com.android.settings",
                "com.android.settings.Settings$QuietHoursSettingsActivity");
        PendingIntent contentIntent = PendingIntent.getActivity(mContext,
                0, mIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent waitIntent = PendingIntent.getBroadcast(mContext, 0,
                new Intent("android.intent.action.QUITE_HOURS_SERVICE_WAITED"), PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder notification = new Notification.Builder(mContext)
                .setContentIntent(contentIntent)
                .setTicker(mContext.getString(R.string.quiet_hours_notification_ticker))
                .setContentTitle(mContext.getString(R.string.quiet_hours_notification_title))
                .setSmallIcon(R.drawable.ic_notification_quiethours)
                .setWhen(System.currentTimeMillis())
                .setOngoing(true)
                .setContentText(mContext.getString(R.string.quiet_hours_notification_text))
                .addAction(R.drawable.ic_wait_quiethours, mContext.getString(R.string.quiet_hours_notification_wait), waitIntent);
        nm.notify(QUIETHOURS_NOTIFICATION_ID, notification.build());
    }

    private Runnable startRunnable = new Runnable() {
        @Override
        public void run() {
            addNotification();
            updateNotification();
        }
    };

    private Runnable stopRunnable = new Runnable() {
        @Override
        public void run() {
            nm.cancel(QUIETHOURS_NOTIFICATION_ID);
        }
    };

    private Runnable waitRunnable = new Runnable() {
        @Override
        public void run() {
            PowerManager pm = (PowerManager)mContext.getSystemService(Context.POWER_SERVICE);
            if (!pm.isScreenOn()) {
                Settings.System.putIntForUser(mContentResolver,
                        Settings.System.QUIET_HOURS_WAITED, 0, UserHandle.USER_CURRENT_OR_SELF);
				updateNotification();
            } else {
                handler.postDelayed(waitRunnable, 1000 * 60 * 10);
            }
        }
    };

    private void registerBroadcastReceiver() {
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction("android.intent.action.QUITE_HOURS_SERVICE_UPDATE");
        filter.addAction("android.intent.action.QUITE_HOURS_SERVICE_WAITED");

        mQuietHoursReceiver = new BroadcastReceiver() {

            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action.equals(Intent.ACTION_TIME_CHANGED)
                        || action.equals(Intent.ACTION_TIMEZONE_CHANGED)
                        || action.equals("android.intent.action.QUITE_HOURS_SERVICE_UPDATE")) {
                    quietHoursWaited = Settings.System.getIntForUser(mContentResolver,
                            Settings.System.QUIET_HOURS_WAITED, 0, UserHandle.USER_CURRENT_OR_SELF) != 0;
                    if (quietHoursWaited) {
                        Settings.System.putIntForUser(mContentResolver,
                                Settings.System.QUIET_HOURS_WAITED, 0, UserHandle.USER_CURRENT_OR_SELF);
                    }
                    updateNotification();
                } else if (action.equals("android.intent.action.QUITE_HOURS_SERVICE_WAITED")) {
                    Settings.System.putIntForUser(mContentResolver,
                            Settings.System.QUIET_HOURS_WAITED, 1, UserHandle.USER_CURRENT_OR_SELF);
                    quietHoursForced = Settings.System.getIntForUser(mContentResolver,
                            Settings.System.QUIET_HOURS_FORCED, 0, UserHandle.USER_CURRENT_OR_SELF) != 0;
                    if (quietHoursForced) {
                        Settings.System.putIntForUser(mContentResolver,
                                Settings.System.QUIET_HOURS_FORCED, 0, UserHandle.USER_CURRENT_OR_SELF);
                        Settings.System.putIntForUser(mContentResolver,
                                Settings.System.QUIET_HOURS_WAITED, 1, UserHandle.USER_CURRENT_OR_SELF);
                    }
                    handler.removeCallbacks(stopRunnable);
                    handler.post(stopRunnable);
                    handler.removeCallbacks(waitRunnable);
                    handler.postDelayed(waitRunnable, 1000 * 60 * 10);
                } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                    handler.removeCallbacks(stopRunnable);
                    handler.removeCallbacks(startRunnable);
                } else if (action.equals(Intent.ACTION_SCREEN_ON)) {
                    updateNotification();
                }
            }
        };
        mContext.registerReceiver(mQuietHoursReceiver, filter);
    }

    private void unregisterReceiver() {
        try {
            // Log.d(TAG, "unregisterReceiver");
            mContext.unregisterReceiver(mQuietHoursReceiver);
        }
        catch (IllegalArgumentException e) {
            mQuietHoursReceiver = null;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
