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
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

/**
 * Remembers which barriers the driver has opened and therefore may be asked to close.
 *
 * An entry is the moment of opening; it is created only when the driver confirms the popup, and
 * removed as soon as the closing question has been put to them — answered either way, or missed.
 * Asking once and then forgetting is deliberate: a prompt that keeps coming back while the driver
 * is doing something else is worse than a gate they chose to leave open.
 *
 * Persisted in SharedPreferences so a service restart doesn't lose a gate standing open.
 */
public class PendingCloseStore {
    private static final String PREFS = "pending_close";
    /** Safety net: an entry nobody ever acted on stops being interesting after a day. */
    private static final long TTL_MS = 24L * 60 * 60 * 1000;

    private final SharedPreferences prefs;

    public PendingCloseStore(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Records that the barrier was just opened, arming its closing triggers. */
    public void markOpened(long barrierId) {
        prefs.edit().putLong(key(barrierId), System.currentTimeMillis()).apply();
    }

    public void clear(long barrierId) {
        prefs.edit().remove(key(barrierId)).apply();
    }

    /** 0 when the barrier is not awaiting a close. */
    public long openedAt(long barrierId) {
        long ts = prefs.getLong(key(barrierId), 0L);
        if (ts != 0L && System.currentTimeMillis() - ts > TTL_MS) {
            clear(barrierId);
            return 0L;
        }
        return ts;
    }

    /** Snapshot of barrier id → opened-at, with expired entries filtered out. */
    public Map<Long, Long> all() {
        Map<Long, Long> result = new HashMap<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (!e.getKey().startsWith("b_") || !(e.getValue() instanceof Long)) continue;
            long ts = (Long) e.getValue();
            if (now - ts > TTL_MS) continue;
            try {
                result.put(Long.parseLong(e.getKey().substring(2)), ts);
            } catch (NumberFormatException ignored) {
                // Not one of ours; skip.
            }
        }
        return result;
    }

    private static String key(long id) {
        return "b_" + id;
    }
}
