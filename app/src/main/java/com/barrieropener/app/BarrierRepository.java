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
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;

import java.util.Collections;
import java.util.List;

/**
 * Caches the barrier list in memory so the location service doesn't hit the database every second.
 * The cache is invalidated through {@link #ACTION_BARRIERS_CHANGED} broadcasts emitted by
 * Add/Edit/Delete flows.
 */
public class BarrierRepository {
    public static final String ACTION_BARRIERS_CHANGED = "com.barrieropener.app.BARRIERS_CHANGED";

    private final Context appContext;
    private final DatabaseHelper db;
    private volatile List<Barrier> cache;
    private BroadcastReceiver invalidator;

    public BarrierRepository(Context context) {
        this.appContext = context.getApplicationContext();
        this.db = new DatabaseHelper(appContext);
    }

    /** Subscribe to invalidation broadcasts. Call once when the consumer starts. */
    public void registerInvalidator() {
        if (invalidator != null) return;
        invalidator = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                cache = null;
            }
        };
        ContextCompatRegister.register(appContext, invalidator,
                new IntentFilter(ACTION_BARRIERS_CHANGED));
    }

    public void unregisterInvalidator() {
        if (invalidator == null) return;
        try {
            appContext.unregisterReceiver(invalidator);
        } catch (IllegalArgumentException ignored) {
            // Already unregistered.
        }
        invalidator = null;
    }

    public List<Barrier> getAll() {
        List<Barrier> snapshot = cache;
        if (snapshot == null) {
            snapshot = db.getAllBarriers();
            cache = snapshot;
        }
        return Collections.unmodifiableList(snapshot);
    }

    public void invalidate() {
        cache = null;
    }

    public DatabaseHelper db() {
        return db;
    }

    /**
     * Notifies any in-process listeners (the running service) that the barrier list has changed.
     */
    public static void notifyChanged(Context context) {
        Intent intent = new Intent(ACTION_BARRIERS_CHANGED).setPackage(context.getPackageName());
        context.sendBroadcast(intent);
    }
}
