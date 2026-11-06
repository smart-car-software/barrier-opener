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

import android.app.Application;
import android.util.Log;

import com.yandex.mapkit.MapKitFactory;

public class BarrierOpenerApp extends Application {
    private static final String TAG = "BarrierOpenerApp";

    @Override
    public void onCreate() {
        super.onCreate();
        DebugLog.init(this);
        String apiKey = BuildConfig.YANDEX_MAPKIT_API_KEY;
        if (apiKey == null || apiKey.isEmpty()) {
            Log.e(TAG, "YANDEX_MAPKIT_API_KEY is not configured. " +
                    "Set it via env var or local.properties before building.");
            return;
        }
        MapKitFactory.setApiKey(apiKey);
        MapKitFactory.initialize(this);
    }
}
