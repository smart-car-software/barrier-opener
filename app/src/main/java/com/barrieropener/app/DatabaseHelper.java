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

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

public class DatabaseHelper extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "barrieropener.db";
    private static final int DATABASE_VERSION = 11; // Incremented version for the closing radius

    // Table name
    private static final String TABLE_BARRIERS = "barriers";

    // Column names
    private static final String KEY_ID = "id";
    private static final String KEY_NAME = "name";
    private static final String KEY_PHONE = "phone";
    private static final String KEY_LATITUDE = "latitude";
    private static final String KEY_LONGITUDE = "longitude";
    private static final String KEY_HEADING = "heading";
    private static final String KEY_DETECTION_RADIUS = "detection_radius";
    private static final String KEY_ZOOM_LEVEL = "zoom_level";
    private static final String KEY_BARRIER_TYPE = "barrier_type";
    private static final String KEY_ACTION_TYPE = "action_type";
    private static final String KEY_WEBHOOK_URL = "webhook_url";
    private static final String KEY_WEBHOOK_SECRET = "webhook_secret";
    private static final String KEY_CLOSE_ON_DEPARTURE = "close_on_departure";
    private static final String KEY_CLOSE_ON_TIMER = "close_on_timer";
    private static final String KEY_CLOSE_ON_PARK = "close_on_park";
    private static final String KEY_CLOSE_DELAY_MINUTES = "close_delay_minutes";
    private static final String KEY_CLOSE_URL = "close_url";
    private static final String KEY_CLOSE_SECRET = "close_secret";
    private static final String KEY_STARTUP_WAIT_BELT = "startup_wait_belt";
    private static final String KEY_STARTUP_WAIT_WARMUP = "startup_wait_warmup";
    private static final String KEY_API_BASE_URL = "api_base_url";
    private static final String KEY_API_TOKEN = "api_token";
    private static final String KEY_CLOSE_RADIUS = "close_radius";

    // Create table SQL
    private static final String CREATE_TABLE_BARRIERS = "CREATE TABLE " + TABLE_BARRIERS +
            "(" + KEY_ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
            KEY_NAME + " TEXT," +
            KEY_PHONE + " TEXT," +
            KEY_LATITUDE + " REAL," +
            KEY_LONGITUDE + " REAL," +
            KEY_HEADING + " REAL," +
            KEY_DETECTION_RADIUS + " REAL," +
            KEY_ZOOM_LEVEL + " REAL," +
            KEY_BARRIER_TYPE + " INTEGER," +
            KEY_ACTION_TYPE + " INTEGER DEFAULT 0," +
            KEY_WEBHOOK_URL + " TEXT," +
            KEY_WEBHOOK_SECRET + " TEXT," +
            KEY_CLOSE_ON_DEPARTURE + " INTEGER DEFAULT 0," +
            KEY_CLOSE_ON_TIMER + " INTEGER DEFAULT 0," +
            KEY_CLOSE_DELAY_MINUTES + " INTEGER DEFAULT " + Barrier.DEFAULT_CLOSE_DELAY_MINUTES + "," +
            KEY_CLOSE_URL + " TEXT," +
            KEY_CLOSE_SECRET + " TEXT," +
            KEY_CLOSE_ON_PARK + " INTEGER DEFAULT 0," +
            KEY_STARTUP_WAIT_BELT + " INTEGER DEFAULT 0," +
            KEY_STARTUP_WAIT_WARMUP + " INTEGER DEFAULT 0," +
            KEY_API_BASE_URL + " TEXT," +
            KEY_API_TOKEN + " TEXT," +
            KEY_CLOSE_RADIUS + " REAL" +
            ")";

    /** Column order used by every read below; keeps cursor indices and the projection in sync. */
    private static final String[] COLUMNS = {
            KEY_ID, KEY_NAME, KEY_PHONE, KEY_LATITUDE, KEY_LONGITUDE, KEY_HEADING,
            KEY_DETECTION_RADIUS, KEY_ZOOM_LEVEL, KEY_BARRIER_TYPE, KEY_ACTION_TYPE,
            KEY_WEBHOOK_URL, KEY_WEBHOOK_SECRET, KEY_CLOSE_ON_DEPARTURE, KEY_CLOSE_ON_TIMER,
            KEY_CLOSE_DELAY_MINUTES, KEY_CLOSE_URL, KEY_CLOSE_SECRET, KEY_CLOSE_ON_PARK,
            KEY_STARTUP_WAIT_BELT, KEY_STARTUP_WAIT_WARMUP, KEY_API_BASE_URL, KEY_API_TOKEN,
            KEY_CLOSE_RADIUS
    };

    public DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_TABLE_BARRIERS);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 4) {
            // Migration from version 3 to 4 - add barrier_type column
            db.execSQL("ALTER TABLE " + TABLE_BARRIERS + " ADD COLUMN " +
                    KEY_BARRIER_TYPE + " INTEGER DEFAULT 0"); // 0 = BIDIRECTIONAL
        }
        if (oldVersion < 5) {
            // Migration to 5 - how the barrier is opened: phone call or HTTP webhook.
            // Existing rows keep dialling their phone number (0 = CALL).
            db.execSQL("ALTER TABLE " + TABLE_BARRIERS + " ADD COLUMN " +
                    KEY_ACTION_TYPE + " INTEGER DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE_BARRIERS + " ADD COLUMN " +
                    KEY_WEBHOOK_URL + " TEXT");
        }
        if (oldVersion < 6) {
            // Migration to 6 - optional shared secret posted as the webhook's JSON body.
            // Existing webhooks keep sending an empty body (NULL secret).
            db.execSQL("ALTER TABLE " + TABLE_BARRIERS + " ADD COLUMN " +
                    KEY_WEBHOOK_SECRET + " TEXT");
        }
        if (oldVersion < 7) {
            // Migration to 7 - closing settings. Both triggers default to off, so existing
            // barriers keep their open-only behaviour until the user turns closing on.
            db.execSQL("ALTER TABLE " + TABLE_BARRIERS + " ADD COLUMN " +
                    KEY_CLOSE_ON_DEPARTURE + " INTEGER DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE_BARRIERS + " ADD COLUMN " +
                    KEY_CLOSE_ON_TIMER + " INTEGER DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE_BARRIERS + " ADD COLUMN " +
                    KEY_CLOSE_DELAY_MINUTES + " INTEGER DEFAULT "
                    + Barrier.DEFAULT_CLOSE_DELAY_MINUTES);
            db.execSQL("ALTER TABLE " + TABLE_BARRIERS + " ADD COLUMN " + KEY_CLOSE_URL + " TEXT");
            db.execSQL("ALTER TABLE " + TABLE_BARRIERS + " ADD COLUMN " + KEY_CLOSE_SECRET + " TEXT");
        }
        if (oldVersion < 8) {
            // Migration to 8 - the Park closing trigger. Off by default: a head unit that can't
            // read the transmission would never fire it anyway, and the ones that can shouldn't
            // start closing gates the user didn't ask them to.
            db.execSQL("ALTER TABLE " + TABLE_BARRIERS + " ADD COLUMN " +
                    KEY_CLOSE_ON_PARK + " INTEGER DEFAULT 0");
        }
        if (oldVersion < 9) {
            // Migration to 9 - what the startup question waits for. Both off by default, so an
            // existing barrier is offered at startup exactly as soon as it was before.
            db.execSQL("ALTER TABLE " + TABLE_BARRIERS + " ADD COLUMN " +
                    KEY_STARTUP_WAIT_BELT + " INTEGER DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE_BARRIERS + " ADD COLUMN " +
                    KEY_STARTUP_WAIT_WARMUP + " INTEGER DEFAULT 0");
        }
        if (oldVersion < 10) {
            // Migration to 10 - the gate-api barrier: where the service lives and the token it
            // expects. NULL on every existing row; call and webhook barriers never read them.
            db.execSQL("ALTER TABLE " + TABLE_BARRIERS + " ADD COLUMN " + KEY_API_BASE_URL + " TEXT");
            db.execSQL("ALTER TABLE " + TABLE_BARRIERS + " ADD COLUMN " + KEY_API_TOKEN + " TEXT");
        }
        if (oldVersion < 11) {
            // Migration to 11 - the departure trigger's own radius. NULL reads as "use the
            // detection radius", so existing barriers close exactly where they did before.
            db.execSQL("ALTER TABLE " + TABLE_BARRIERS + " ADD COLUMN " + KEY_CLOSE_RADIUS + " REAL");
        }
    }

    /** Fills the shared column set; used by both insert and update. */
    private static ContentValues toValues(Barrier barrier) {
        ContentValues values = new ContentValues();
        values.put(KEY_NAME, barrier.getName());
        values.put(KEY_PHONE, barrier.getPhoneNumber());
        values.put(KEY_LATITUDE, barrier.getLatitude());
        values.put(KEY_LONGITUDE, barrier.getLongitude());
        values.put(KEY_HEADING, barrier.getHeading());
        values.put(KEY_DETECTION_RADIUS, barrier.getDetectionRadius());
        values.put(KEY_ZOOM_LEVEL, barrier.getZoomLevel());
        values.put(KEY_BARRIER_TYPE, barrier.getBarrierType().getValue());
        values.put(KEY_ACTION_TYPE, barrier.getActionType().getValue());
        values.put(KEY_WEBHOOK_URL, barrier.getWebhookUrl());
        values.put(KEY_WEBHOOK_SECRET, barrier.getWebhookSecret());
        values.put(KEY_CLOSE_ON_DEPARTURE, barrier.isCloseOnDeparture() ? 1 : 0);
        values.put(KEY_CLOSE_ON_TIMER, barrier.isCloseOnTimer() ? 1 : 0);
        values.put(KEY_CLOSE_ON_PARK, barrier.isCloseOnPark() ? 1 : 0);
        values.put(KEY_CLOSE_DELAY_MINUTES, barrier.getCloseDelayMinutes());
        values.put(KEY_CLOSE_URL, barrier.getCloseUrl());
        values.put(KEY_CLOSE_SECRET, barrier.getCloseSecret());
        values.put(KEY_STARTUP_WAIT_BELT, barrier.isStartupWaitForBelt() ? 1 : 0);
        values.put(KEY_STARTUP_WAIT_WARMUP, barrier.isStartupWaitForWarmUp() ? 1 : 0);
        values.put(KEY_API_BASE_URL, barrier.getApiBaseUrl());
        values.put(KEY_API_TOKEN, barrier.getApiToken());
        values.put(KEY_CLOSE_RADIUS, barrier.getCloseRadius() > 0 ? barrier.getCloseRadius() : null);
        return values;
    }

    /** Reads a barrier from a cursor positioned on a row selected with {@link #COLUMNS}. */
    private static Barrier fromCursor(Cursor cursor) {
        Barrier barrier = new Barrier();
        barrier.setId(cursor.getLong(0));
        barrier.setName(cursor.getString(1));
        barrier.setPhoneNumber(cursor.getString(2));
        barrier.setLatitude(cursor.getDouble(3));
        barrier.setLongitude(cursor.getDouble(4));
        barrier.setHeading(cursor.getDouble(5));
        barrier.setDetectionRadius(cursor.getDouble(6));
        if (!cursor.isNull(7)) {
            barrier.setZoomLevel(cursor.getFloat(7));
        }
        barrier.setBarrierType(Barrier.BarrierType.fromValue(cursor.getInt(8)));
        barrier.setActionType(Barrier.ActionType.fromValue(cursor.getInt(9)));
        barrier.setWebhookUrl(cursor.getString(10));
        barrier.setWebhookSecret(cursor.getString(11));
        barrier.setCloseOnDeparture(cursor.getInt(12) != 0);
        barrier.setCloseOnTimer(cursor.getInt(13) != 0);
        if (!cursor.isNull(14)) {
            barrier.setCloseDelayMinutes(cursor.getInt(14));
        }
        barrier.setCloseUrl(cursor.getString(15));
        barrier.setCloseSecret(cursor.getString(16));
        barrier.setCloseOnPark(cursor.getInt(17) != 0);
        barrier.setStartupWaitForBelt(cursor.getInt(18) != 0);
        barrier.setStartupWaitForWarmUp(cursor.getInt(19) != 0);
        barrier.setApiBaseUrl(cursor.getString(20));
        barrier.setApiToken(cursor.getString(21));
        if (!cursor.isNull(22)) {
            barrier.setCloseRadius(cursor.getDouble(22));
        }
        return barrier;
    }

    // Add a new barrier
    public long addBarrier(Barrier barrier) {
        SQLiteDatabase db = this.getWritableDatabase();
        return db.insert(TABLE_BARRIERS, null, toValues(barrier));
    }

    // Get a single barrier
    public Barrier getBarrier(long id) {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.query(TABLE_BARRIERS, COLUMNS, KEY_ID + "=?",
                new String[]{String.valueOf(id)}, null, null, null, null);

        if (cursor == null) {
            return null;
        }
        try {
            return cursor.moveToFirst() ? fromCursor(cursor) : null;
        } finally {
            cursor.close();
        }
    }

    // Update a barrier
    public int updateBarrier(Barrier barrier) {
        SQLiteDatabase db = this.getWritableDatabase();
        return db.update(TABLE_BARRIERS, toValues(barrier), KEY_ID + " = ?",
                new String[]{String.valueOf(barrier.getId())});
    }

    // Get all barriers
    public List<Barrier> getAllBarriers() {
        List<Barrier> barriers = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.query(TABLE_BARRIERS, COLUMNS, null, null, null, null, null);

        try {
            if (cursor.moveToFirst()) {
                do {
                    barriers.add(fromCursor(cursor));
                } while (cursor.moveToNext());
            }
        } finally {
            cursor.close();
        }
        return barriers;
    }

    // Delete a barrier
    public void deleteBarrier(long id) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(TABLE_BARRIERS, KEY_ID + " = ?",
                new String[]{String.valueOf(id)});
    }
}