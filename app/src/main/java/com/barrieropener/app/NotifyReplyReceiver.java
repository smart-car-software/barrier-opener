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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Car Notification Service's answer to a question put by {@link BarrierQuestion}. Not exported:
 * it is reached only through our own PendingIntent, which the service fires on our behalf, with
 * the barrier id and mode we put in it and the answer the service added.
 */
public class NotifyReplyReceiver extends BroadcastReceiver {
    private static final String TAG = "NotifyReply";
    static final String EXTRA_BARRIER_ID = "barrier_id";
    static final String EXTRA_CLOSING = "closing";

    @Override
    public void onReceive(Context context, Intent intent) {
        long barrierId = intent.getLongExtra(EXTRA_BARRIER_ID, -1);
        boolean closing = intent.getBooleanExtra(EXTRA_CLOSING, false);
        String result = intent.getStringExtra("result");
        int index = intent.getIntExtra("index", -1);
        DebugLog.i(TAG, (closing ? "close " : "open ") + barrierId + ": " + result
                + (index >= 0 ? " " + index : "")
                + (intent.hasExtra("error") ? " (" + intent.getStringExtra("error") + ")" : ""));

        if (closing) {
            // The closing question has been put to the driver — answered or not, we don't nag.
            // LocationService already spent the entry when it handed the question over; this is
            // the popup's rule, kept for a reply that somehow arrives before that.
            new PendingCloseStore(context).clear(barrierId);
        }

        NotifyReply.Outcome outcome = NotifyReply.decide(result, index);
        if (outcome == NotifyReply.Outcome.NOTHING) {
            return;
        }
        // Re-read: the question may have waited in the queue while the barrier was edited.
        DatabaseHelper db = new DatabaseHelper(context);
        Barrier barrier = db.getBarrier(barrierId);
        db.close();
        if (barrier == null) {
            DebugLog.i(TAG, "Barrier " + barrierId + " is gone; nothing to do");
            return;
        }
        if (outcome == NotifyReply.Outcome.ASK_OURSELVES) {
            // No nearby list: the popup recomputes it from its own fixes anyway.
            BarrierQuestion.showPopup(context, barrier, closing, null);
            return;
        }
        if (closing) {
            BarrierAction.triggerClose(context, barrier);
        } else {
            BarrierAction.trigger(context, barrier);
            // As the popup's "Yes": only a barrier that asked for closing triggers arms them.
            if (barrier.isClosable()) {
                new PendingCloseStore(context).markOpened(barrier.getId());
            }
        }
    }
}
