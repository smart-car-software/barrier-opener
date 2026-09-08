/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.barrieropener.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.location.Location;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class LocationService extends Service {
    private static final String TAG = "LocationService";
    private static final String CHANNEL_ID = "BarrierOpenerServiceChannel";
    private static final String POPUP_CHANNEL_ID = "BarrierPopupChannel";
    private static final int NOTIFICATION_ID = 1;
    public static final int POPUP_NOTIFICATION_ID = 2;
    public static final String ACTION_STOP = "com.barrieropener.app.STOP";

    private static volatile boolean _isStarted = false;

    /** How often the timer-based closing trigger is re-evaluated without a fresh GPS fix. */
    private static final long CLOSE_TICK_MS = 15_000L;

    private LocationManager locationManager;
    private BarrierRepository repository;
    private TriggerStateStore triggerState;
    private PendingCloseStore pendingClose;
    private Location lastLocation;
    private final Handler closeTicker = new Handler(Looper.getMainLooper());
    private final Runnable closeTick = new Runnable() {
        @Override
        public void run() {
            checkPendingCloses(repository.getAll(), lastLocation);
            closeTicker.postDelayed(this, CLOSE_TICK_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        repository = new BarrierRepository(this);
        repository.registerInvalidator();
        triggerState = new TriggerStateStore(this);
        pendingClose = new PendingCloseStore(this);
        locationManager = new LocationManager(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Log.d(TAG, "Stop action received");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        Log.d(TAG, "Starting service...");
        _isStarted = true;

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        startLocationUpdates();
        closeTicker.removeCallbacks(closeTick);
        closeTicker.postDelayed(closeTick, CLOSE_TICK_MS);

        return START_STICKY;
    }

    public static boolean isStarted() {
        return _isStarted;
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);

        NotificationChannel serviceChannel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        manager.createNotificationChannel(serviceChannel);

        NotificationChannel popupChannel = new NotificationChannel(
                POPUP_CHANNEL_ID,
                getString(R.string.notification_popup_channel_name),
                NotificationManager.IMPORTANCE_HIGH
        );
        popupChannel.enableVibration(true);
        popupChannel.setBypassDnd(true);
        manager.createNotificationChannel(popupChannel);
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent contentPi = PendingIntent.getActivity(
                this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, LocationService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(contentPi)
                .addAction(0, getString(R.string.notification_action_stop), stopPi)
                .setOngoing(true)
                .build();
    }

    private void startLocationUpdates() {
        locationManager.startLocationUpdates(new LocationCallback() {
            @Override
            public void onLocationUpdated(Location location) {
                checkNearbyBarriers(location);
            }

            @Override
            public void onLocationError(String error) {
                Log.e(TAG, "Location error: " + error);
            }

            @Override
            public void onProviderStatusChanged(String provider, boolean enabled) {
                Log.d(TAG, "Provider " + provider + " " + (enabled ? "enabled" : "disabled"));
            }
        });
    }

    private void checkNearbyBarriers(Location location) {
        List<Barrier> barriers = repository.getAll();
        lastLocation = location;
        triggerState.releaseFarBarriers(location.getLatitude(), location.getLongitude(), barriers);
        checkPendingCloses(barriers, location);

        NearbyResolver.Resolution res = NearbyResolver.resolve(
                barriers,
                BarrierDetector.Fix.from(location),
                id -> triggerState.isTriggered(id));

        if (res.primary == null) {
            return; // Nothing new to suggest by direction.
        }

        boolean delivered = triggerBarrierPopup(res.primary, res.inZone);

        // Suppress every fresh directional match so popups don't stack — but only once we actually
        // reached the user. If neither the direct launch nor the notification can surface (e.g. all
        // notifications are blocked and background-activity launch is denied), leave the flags unset
        // so the next location update retries instead of silently muting the barrier. Each suppressed
        // match still appears in the popup's nearby list with its own call button.
        if (delivered) {
            for (Barrier b : res.freshMatches) {
                triggerState.markTriggered(b.getId());
            }
        }
    }

    /**
     * Offers to close every barrier whose closing trigger has fired. Runs both on location updates
     * and on the periodic tick, because the timer must still fire when the car is parked and no
     * fresh fixes arrive — a gate left open is exactly the case where the vehicle stopped moving.
     */
    private void checkPendingCloses(List<Barrier> barriers, Location location) {
        Map<Long, Long> pending = pendingClose.all();
        if (pending.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Barrier b : barriers) {
            Long openedAt = pending.get(b.getId());
            if (openedAt == null) {
                continue;
            }
            if (!b.isClosable()) {
                // Closing was switched off after the gate was opened; drop the stale entry.
                pendingClose.clear(b.getId());
                continue;
            }
            double distance = -1;
            if (location != null) {
                float[] d = new float[1];
                Location.distanceBetween(location.getLatitude(), location.getLongitude(),
                        b.getLatitude(), b.getLongitude(), d);
                distance = d[0];
            }
            CloseResolver.Reason reason = CloseResolver.evaluate(b, openedAt, now, distance);
            if (reason != null) {
                Log.d(TAG, "Offering to close " + b.getName() + " (" + reason + ")");
                triggerClosePopup(b);
                // Asked once; the popup clears the entry when the driver answers or it times out.
                pendingClose.clear(b.getId());
            }
        }
    }

    private void triggerClosePopup(Barrier barrier) {
        Intent popupIntent = new Intent(this, BarrierPopupActivity.class);
        popupIntent.putExtra("barrier_id", barrier.getId());
        popupIntent.putExtra("barrier_name", barrier.getName());
        popupIntent.putExtra(BarrierPopupActivity.EXTRA_MODE, BarrierPopupActivity.MODE_CLOSE);
        popupIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            startActivity(popupIntent);
        } catch (Exception e) {
            Log.w(TAG, "Could not start close popup directly", e);
        }
        showPopupFullScreenNotification(barrier, popupIntent);
    }

    private boolean triggerBarrierPopup(Barrier primary, List<Barrier> inZone) {
        Intent popupIntent = buildPopupIntent(primary, inZone);

        // Best effort: a direct launch works in the foreground / on lenient OEMs, but on Android
        // 10–12 a background-activity start from a service is silently dropped, so we don't rely on
        // it. The high-priority full-screen-intent notification is the reliable path that surfaces
        // over other apps and the lock screen, so its deliverability is what gates suppression.
        try {
            startActivity(popupIntent);
        } catch (Exception e) {
            Log.w(TAG, "Could not start popup activity directly", e);
        }
        return showPopupFullScreenNotification(primary, popupIntent);
    }

    private Intent buildPopupIntent(Barrier primary, List<Barrier> inZone) {
        // The nearby list is every in-zone barrier except the one already shown as the suggestion.
        List<Barrier> nearby = new ArrayList<>();
        for (Barrier b : inZone) {
            if (b.getId() != primary.getId()) {
                nearby.add(b);
            }
        }

        // Only ids travel in the intent — the popup re-reads the barriers from the database, so
        // whichever action (call or webhook) each one carries is always the current one.
        long[] ids = new long[nearby.size()];
        for (int i = 0; i < nearby.size(); i++) {
            ids[i] = nearby.get(i).getId();
        }

        Intent popupIntent = new Intent(this, BarrierPopupActivity.class);
        popupIntent.putExtra("barrier_id", primary.getId());
        popupIntent.putExtra("barrier_name", primary.getName());
        popupIntent.putExtra("nearby_ids", ids);
        popupIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return popupIntent;
    }

    /** Posts the heads-up / full-screen popup notification. Returns whether it can actually reach
     *  the user (notifications enabled and the popup channel not blocked). */
    private boolean showPopupFullScreenNotification(Barrier barrier, Intent popupIntent) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return false;
        }

        PendingIntent fullScreenPi = PendingIntent.getActivity(
                this, (int) barrier.getId(), popupIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification n = new NotificationCompat.Builder(this, POPUP_CHANNEL_ID)
                .setContentTitle(getString(R.string.popup_title_window, barrier.getName()))
                .setContentText(BarrierAction.describeTarget(barrier))
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(fullScreenPi, true)
                .setContentIntent(fullScreenPi)
                .setAutoCancel(true)
                .setTimeoutAfter(15_000L)
                .build();

        manager.notify(POPUP_NOTIFICATION_ID, n);
        return popupNotificationsDeliverable(manager);
    }

    private boolean popupNotificationsDeliverable(NotificationManager manager) {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            return false;
        }
        NotificationChannel channel = manager.getNotificationChannel(POPUP_CHANNEL_ID);
        return channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        _isStarted = false;
        closeTicker.removeCallbacks(closeTick);
        locationManager.stopLocationUpdates();
        repository.unregisterInvalidator();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
