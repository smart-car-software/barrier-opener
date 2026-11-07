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
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/**
 * The single place a question about a barrier is put to the driver. With Car Notification Service
 * installed it goes there — the service owns the cluster, the wheel and the queue, and answers back
 * through {@link NotifyReplyReceiver}. Without it, {@link BarrierPopupActivity} on the head unit,
 * as before the service existed.
 */
final class BarrierQuestion {
    private static final String TAG = "BarrierQuestion";

    enum Kind {
        /** Approaching the gate: worth asking only before the car gets there. */
        DRIVE_BY_OPEN(60_000L),
        /** Parked inside the zone with the engine just started: the driver is in no hurry. */
        STARTUP_OPEN(3 * 60_000L),
        /** The gate was left open; the question is still worth it after a while in the queue. */
        CLOSE(5 * 60_000L);

        /**
         * How long the question may wait in the service's queue behind another popup. Counted
         * from when the service receives it; once shown, the service's own timeout applies.
         */
        final long expireMs;

        Kind(long expireMs) {
            this.expireMs = expireMs;
        }

        boolean closing() {
            return this == CLOSE;
        }
    }

    private BarrierQuestion() {
    }

    /**
     * @param nearbyIds the other barriers in range, for the head-unit popup's list; the service's
     *                  question is about the one barrier only
     * @return whether the question reached the driver — handed to the service, or a popup whose
     *         notification can be delivered. Suppression flags hang on this, so a false means the
     *         next fix asks again.
     */
    static boolean ask(Context c, Barrier barrier, Kind kind, long[] nearbyIds) {
        String title = c.getString(kind.closing()
                ? R.string.popup_close_title_window : R.string.popup_title_window, barrier.getName());
        if (NotifyClient.installed(c)) {
            String id = c.getPackageName() + ":" + (kind.closing() ? "close" : "open") + ":"
                    + barrier.getId();
            DebugLog.i(TAG, "Asking via Car Notification Service: " + id);
            if (NotifyClient.question(c, id, title,
                    new String[]{c.getString(R.string.btn_yes_window),
                            c.getString(R.string.btn_no_window)},
                    kind.expireMs, true, reply(c, barrier.getId(), kind.closing()))) {
                return true;
            }
            // A broadcast that could not even be sent: the popup is the better answer than none.
        }
        return showPopup(c, barrier, kind.closing(), nearbyIds);
    }

    /**
     * Ids and mode only, as between the service and the popup: the receiver re-reads the barrier,
     * so an edit made while the question waited in the queue is honoured. The data URI keeps each
     * barrier-and-mode's PendingIntent apart — extras alone don't, and FLAG_UPDATE_CURRENT would
     * have one barrier's question answer for another.
     */
    private static PendingIntent reply(Context c, long barrierId, boolean closing) {
        Intent i = new Intent(c, NotifyReplyReceiver.class)
                .setData(Uri.parse("barrieropener://reply/" + (closing ? "close" : "open") + "/"
                        + barrierId))
                .putExtra(NotifyReplyReceiver.EXTRA_BARRIER_ID, barrierId)
                .putExtra(NotifyReplyReceiver.EXTRA_CLOSING, closing);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE;
        return PendingIntent.getBroadcast(c, (int) (barrierId * 2 + (closing ? 1 : 0)), i, flags);
    }

    /**
     * The head-unit popup, launched twice over: a direct start (works in the foreground and on
     * lenient OEMs; from a background service on Android 10–12 it is silently dropped) and a
     * full-screen-intent notification, the path that actually surfaces over other apps and the lock
     * screen — so it is the notification's deliverability that is returned.
     */
    static boolean showPopup(Context c, Barrier barrier, boolean closing, long[] nearbyIds) {
        DebugLog.i(TAG, "Asking on the head unit: " + (closing ? "close " : "open ")
                + barrier.getName());
        Intent popup = new Intent(c, BarrierPopupActivity.class);
        popup.putExtra("barrier_id", barrier.getId());
        popup.putExtra("barrier_name", barrier.getName());
        if (closing) {
            popup.putExtra(BarrierPopupActivity.EXTRA_MODE, BarrierPopupActivity.MODE_CLOSE);
        } else {
            popup.putExtra("nearby_ids", nearbyIds != null ? nearbyIds : new long[0]);
        }
        popup.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            c.startActivity(popup);
        } catch (Exception e) {
            DebugLog.w(TAG, "Could not start popup directly", e);
        }
        return showFullScreenNotification(c, barrier, closing, popup);
    }

    private static boolean showFullScreenNotification(Context c, Barrier barrier, boolean closing,
                                                      Intent popup) {
        NotificationManager manager = c.getSystemService(NotificationManager.class);
        if (manager == null) {
            return false;
        }
        PendingIntent fullScreen = PendingIntent.getActivity(c, (int) barrier.getId(), popup,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new NotificationCompat.Builder(c, LocationService.POPUP_CHANNEL_ID)
                .setContentTitle(c.getString(closing ? R.string.popup_close_title_window
                        : R.string.popup_title_window, barrier.getName()))
                .setContentText(BarrierAction.describeTarget(barrier))
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(fullScreen, true)
                .setContentIntent(fullScreen)
                .setAutoCancel(true)
                // Outlives the popup's own countdown, so the notification is never the thing that
                // takes the question away while it is still on screen.
                .setTimeoutAfter(35_000L)
                .build();
        manager.notify(LocationService.POPUP_NOTIFICATION_ID, n);
        return deliverable(c, manager);
    }

    /** Notifications enabled and the popup channel not blocked. */
    private static boolean deliverable(Context c, NotificationManager manager) {
        if (!NotificationManagerCompat.from(c).areNotificationsEnabled()) {
            return false;
        }
        NotificationChannel channel = manager.getNotificationChannel(LocationService.POPUP_CHANNEL_ID);
        return channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }
}
