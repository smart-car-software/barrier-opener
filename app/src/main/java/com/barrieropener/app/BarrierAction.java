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

import android.content.Context;

/**
 * The single place that decides *how* a barrier is opened. Every trigger point (the popup, the
 * "also in range" rows, the list button) goes through here, so adding another action type later
 * means touching this class only.
 */
public final class BarrierAction {

    private BarrierAction() {}

    public static void trigger(Context context, Barrier barrier) {
        if (barrier == null) {
            return;
        }
        if (barrier.getActionType() == Barrier.ActionType.WEBHOOK) {
            WebhookHelper.sendRequest(context, barrier.getWebhookUrl(), barrier.getWebhookSecret());
        } else {
            CallHelper.placeCall(context, barrier.getPhoneNumber());
        }
    }

    /** Short one-line target shown in list rows and notifications: the number, or the URL. */
    public static String describeTarget(Barrier barrier) {
        if (barrier == null) {
            return "";
        }
        if (barrier.getActionType() == Barrier.ActionType.WEBHOOK) {
            return barrier.getWebhookUrl() != null ? barrier.getWebhookUrl() : "";
        }
        return barrier.getPhoneNumber() != null ? barrier.getPhoneNumber() : "";
    }
}
