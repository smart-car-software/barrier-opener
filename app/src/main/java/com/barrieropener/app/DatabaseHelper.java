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
    private static final int DATABASE_VERSION = 5; // Incremented version for action type / webhook

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
            KEY_WEBHOOK_URL + " TEXT" +
            ")";

    /** Column order used by every read below; keeps cursor indices and the projection in sync. */
    private static final String[] COLUMNS = {
            KEY_ID, KEY_NAME, KEY_PHONE, KEY_LATITUDE, KEY_LONGITUDE, KEY_HEADING,
            KEY_DETECTION_RADIUS, KEY_ZOOM_LEVEL, KEY_BARRIER_TYPE, KEY_ACTION_TYPE,
            KEY_WEBHOOK_URL
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