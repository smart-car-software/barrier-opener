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

/**
 * Counts unfinished calls into one part of the ECarX vendor SDK, which on some firmwares kills the
 * process natively — no exception, nothing to catch. Arm before the call, disarm after it: if the
 * process dies in between, the attempt stays counted, and after {@link #MAX_CRASHES} of them that
 * part of the SDK is left alone for good. The same scheme {@link GearMonitor} uses for the sensor
 * API, kept per feature so a crashing key service does not also cost the Park trigger.
 *
 * <p>{@code commit()}, not {@code apply()}: an asynchronous write would not survive the crash it
 * exists to count.
 */
final class VendorCanary {
    private static final String PREFS = "vendor_canary";
    static final int MAX_CRASHES = 3;

    private final SharedPreferences prefs;
    private final String feature;

    VendorCanary(Context context, String feature) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.feature = feature;
    }

    /** @return false when this feature has crashed us often enough to stay off. */
    boolean arm() {
        int attempts = prefs.getInt(feature + "_attempts", 0);
        boolean survived = prefs.getBoolean(feature + "_survived", false);
        if (!survived && attempts >= MAX_CRASHES) {
            return false;
        }
        prefs.edit().putInt(feature + "_attempts", attempts + 1).commit();
        return true;
    }

    void disarm() {
        prefs.edit().putInt(feature + "_attempts", 0)
                .putBoolean(feature + "_survived", true).commit();
    }

    String describe() {
        return feature + ": attempts=" + prefs.getInt(feature + "_attempts", 0)
                + " survived=" + prefs.getBoolean(feature + "_survived", false);
    }
}
