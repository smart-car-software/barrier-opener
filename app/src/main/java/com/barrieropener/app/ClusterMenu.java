/*
 * Copyright © 2026 smart-car-software contributors
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
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * The gates in the Monjaro cluster's own menu, on its phone tab (the user's choice: navigation is
 * route-suggest's): «Открыть» / «Закрыть» rows ({@link GateMenu}), the left joystick of the wheel
 * scrolls them and its press sends the command through {@link BarrierAction}.
 *
 * <p>How the unit lets an app in there is route-suggest's {@code ClusterMenu}, which worked in the
 * car on the navigation tab (learned from mNavi's APK; no ECarX SDK is public): ECarX's
 * {@code DimMenuInteraction}, reached by reflection, calls back {@code onTabChanged(tab)};
 * the joystick comes as the broadcasts {@link #KEY_UP} / {@link #KEY_DOWN} / {@link #KEY_OK};
 * the list is our overlay on the cluster display where the tab's panel is ({@link #X},
 * {@link #Y}). The cluster's tabs are fixed in its firmware — no API adds one — hence a stock tab.
 *
 * <p>Not seen in the car on the phone tab yet: whether the panel is in the same place there and
 * whether the joystick's broadcasts come (and do not also work the unit's contacts under our
 * window). Everything the unit says goes to the app's log ({@link DebugLog}) for «Send logs».
 * On a unit without ECarX the class is not there and nothing happens.
 */
final class ClusterMenu {
    static final String DIM_CLASS = "com.ecarx.xui.adaptapi.diminteraction.DimMenuInteraction";
    static final String DIM_CALLBACK =
            "com.ecarx.xui.adaptapi.diminteraction.IDimMenuInteraction$IDimMenuInteractionCallback";
    /** {@code IDimMenuInteraction.MENU_TAB_PHONE}. */
    static final int PHONE_TAB = 1;
    static final String KEY_UP = "ecarx.intent.action.ECARX_KEY_DIMSCROLLUP_EVENT";
    static final String KEY_DOWN = "ecarx.intent.action.ECARX_KEY_DIMSCROLLDOWN_EVENT";
    static final String KEY_OK = "ecarx.intent.action.ECARX_KEY_DIMCONFIRM_EVENT";

    /** The driver's display and the tab's panel on it, px (route-suggest's, from the car). */
    static final int DISPLAY = 2;
    static final int X = 740;
    static final int Y = 720;
    static final int W = 414;
    static final int H = 284;
    static final int TITLE_H = 44;
    static final int VISIBLE_ROWS = 5;
    /** The test from the settings shows the list for this long. */
    static final long TRIAL_MS = 30_000L;
    private static final long FLASH_MS = 600L;
    private static final String TAG = "ClusterMenu";

    private final Context c;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Object dim;
    private Object callback;
    private boolean keysRegistered;

    private WindowManager wm;
    private LinearLayout view;
    private LinearLayout list;
    private GateMenu.Rows rows = new GateMenu.Rows();
    private int selected;
    private int first;
    /** Bumped on each show and hide: a trial's timer for an old show does nothing. */
    private int generation;
    private long flashUntil;

    ClusterMenu(Context c) {
        this.c = c.getApplicationContext();
    }

    // ------------------------------------------------------------------ the unit's side

    void start() {
        main.post(() -> {
            if (dim != null) return;
            try {
                Class<?> cls = Class.forName(DIM_CLASS);
                Class<?> cb = Class.forName(DIM_CALLBACK);
                dim = cls.getConstructor(Context.class).newInstance(c);
                callback = Proxy.newProxyInstance(cb.getClassLoader(), new Class<?>[]{cb},
                        (proxy, m, args) -> {
                            // The unit keeps callbacks in lists: identity for these.
                            switch (m.getName()) {
                                case "equals":
                                    return args != null && args.length == 1 && proxy == args[0];
                                case "hashCode":
                                    return System.identityHashCode(proxy);
                                case "toString":
                                    return "barrier-opener DIM callback";
                                default:
                                    break;
                            }
                            onUnitCall(m, args);
                            return defaultValue(m.getReturnType());
                        });
                cls.getMethod("registerDimMenuInteractionCallback", cb).invoke(dim, callback);
                DebugLog.i(TAG, "Subscribed to the cluster menu");
            } catch (ClassNotFoundException e) {
                dim = null;
                DebugLog.i(TAG, "No ECarX DimMenuInteraction on this unit: no gates on the cluster");
                return;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                dim = null;
                DebugLog.w(TAG, "Could not subscribe to the cluster menu", e);
                return;
            }
            if (!keysRegistered) {
                IntentFilter f = new IntentFilter();
                f.addAction(KEY_UP);
                f.addAction(KEY_DOWN);
                f.addAction(KEY_OK);
                c.registerReceiver(keys, f);
                keysRegistered = true;
            }
        });
    }

    void stop() {
        main.post(() -> {
            hide();
            if (keysRegistered) {
                try {
                    c.unregisterReceiver(keys);
                } catch (IllegalArgumentException ignored) {
                    // Already gone.
                }
                keysRegistered = false;
            }
            if (dim != null && callback != null) {
                try {
                    Class<?> cb = Class.forName(DIM_CALLBACK);
                    dim.getClass().getMethod("unregisterDimMenuInteractionCallback", cb)
                            .invoke(dim, callback);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    DebugLog.w(TAG, "unregister", e);
                }
            }
            dim = null;
            callback = null;
        });
    }

    /** Any call from the unit, on whatever thread it comes. */
    private void onUnitCall(Method m, Object[] args) {
        String name = m.getName();
        if (m.getDeclaringClass() == Object.class) return;
        DebugLog.i(TAG, "Cluster: " + name + (args == null ? "()" : java.util.Arrays.toString(args)));
        if ("onTabChanged".equals(name) && args != null && args.length == 1
                && args[0] instanceof Integer) {
            int tab = (Integer) args[0];
            main.post(() -> {
                if (tab == PHONE_TAB) show(0);
                else hide();
            });
        }
    }

    private static Object defaultValue(Class<?> t) {
        if (t == boolean.class) return false;
        if (t == int.class || t == short.class || t == byte.class) return 0;
        if (t == long.class) return 0L;
        if (t == float.class) return 0f;
        if (t == double.class) return 0d;
        if (t == char.class) return '\0';
        return null;
    }

    private final BroadcastReceiver keys = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent i) {
            String a = i.getAction();
            // Route-suggest's menu on the navigation tab gets the same broadcasts: ours only
            // while our list is up.
            if (a == null || view == null) return;
            if (KEY_UP.equals(a)) move(-1);
            else if (KEY_DOWN.equals(a)) move(1);
            else if (KEY_OK.equals(a)) pick();
        }
    };

    // ------------------------------------------------------------------ the list

    /** From the settings: the list for {@link #TRIAL_MS}, as on the tab. */
    void trial() {
        main.post(() -> show(TRIAL_MS));
    }

    /** The barriers changed: new rows while the list is up. */
    void refresh() {
        main.post(() -> {
            if (view == null) return;
            String was = selected < rows.keys.size() ? rows.keys.get(selected) : null;
            rows = build();
            int at = was == null ? -1 : rows.keys.indexOf(was);
            selected = Math.max(0, at);
            first = Math.max(0, Math.min(first, selected));
            draw();
        });
    }

    /** On the main thread. {@code forMs} > 0: hide by itself after that long. */
    private void show(long forMs) {
        if (!Settings.canDrawOverlays(c)) {
            DebugLog.i(TAG, "No overlay permission: the gates are not shown on the cluster");
            return;
        }
        int gen = ++generation;
        rows = build();
        selected = 0;
        first = 0;
        if (!attach()) return;
        draw();
        DebugLog.i(TAG, "Gates shown on the cluster: " + rows.text.size() + " rows"
                + (forMs > 0 ? " (trial)" : ""));
        if (forMs > 0) main.postDelayed(() -> {
            if (gen == generation) hide();
        }, forMs);
    }

    /** The rows now: every barrier, the nearest to the last fix first. A small database read. */
    private GateMenu.Rows build() {
        DatabaseHelper db = new DatabaseHelper(c);
        List<Barrier> barriers;
        try {
            barriers = db.getAllBarriers();
        } finally {
            db.close();
        }
        Location at = LocationService.lastKnown;
        List<GateMenu.Entry> entries = new ArrayList<>();
        for (Barrier b : barriers) {
            double d = -1;
            if (at != null) {
                float[] m = new float[1];
                Location.distanceBetween(at.getLatitude(), at.getLongitude(),
                        b.getLatitude(), b.getLongitude(), m);
                d = m[0];
            }
            entries.add(new GateMenu.Entry(b.getId(), b.getName(), b.isRequestBased(), d));
        }
        return GateMenu.rows(entries, c.getString(R.string.menu_open), c.getString(R.string.menu_close));
    }

    private void hide() {
        generation++;
        if (view != null && wm != null) {
            try {
                wm.removeView(view);
            } catch (RuntimeException ignored) {
                // Gone with its display.
            }
        }
        view = null;
        list = null;
    }

    private void move(int by) {
        if (rows.text.isEmpty()) return;
        selected = Math.max(0, Math.min(rows.text.size() - 1, selected + by));
        if (selected < first) first = selected;
        if (selected >= first + VISIBLE_ROWS) first = selected - VISIBLE_ROWS + 1;
        draw();
    }

    /** The joystick's press: as the wheel's «Да» to the question about that barrier. */
    private void pick() {
        if (selected >= rows.keys.size()) return;
        GateMenu.Command cmd = GateMenu.parse(rows.keys.get(selected));
        if (cmd == null) return;
        // Re-read: the list may have been built before the barrier was edited.
        DatabaseHelper db = new DatabaseHelper(c);
        Barrier b = db.getBarrier(cmd.id);
        db.close();
        if (b == null) {
            DebugLog.i(TAG, "Barrier " + cmd.id + " is gone");
            refresh();
            return;
        }
        DebugLog.i(TAG, (cmd.close ? "Close " : "Open ") + b.getId() + " from the cluster menu");
        if (cmd.close) {
            BarrierAction.triggerClose(c, b);
            new PendingCloseStore(c).clear(b.getId());
        } else {
            BarrierAction.trigger(c, b);
            // Only a barrier that asked for closing triggers arms them.
            if (b.isClosable()) new PendingCloseStore(c).markOpened(b.getId());
        }
        // The list stays (gone, it leaves the tab's panel empty); the row flashes as the answer.
        flashUntil = SystemClock.elapsedRealtime() + FLASH_MS;
        draw();
        main.postDelayed(this::draw, FLASH_MS);
    }

    private boolean attach() {
        if (view != null) return true;
        DisplayManager dm = c.getSystemService(DisplayManager.class);
        Display d = dm == null ? null : dm.getDisplay(DISPLAY);
        boolean onCluster = d != null;
        if (d == null && dm != null) d = dm.getDisplay(Display.DEFAULT_DISPLAY); // a phone, a test
        if (d == null) return false;
        Context dc = c.createDisplayContext(d);
        wm = dc.getSystemService(WindowManager.class);
        view = new LinearLayout(dc);
        view.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(c.getColor(R.color.cluster_panel));
        bg.setCornerRadius(12);
        bg.setStroke(1, c.getColor(R.color.cluster_panel_rim));
        view.setBackground(bg);
        TextView title = text(dc, c.getString(R.string.menu_title), 22, false);
        title.setTextColor(c.getColor(R.color.cluster_text_dim));
        view.addView(title, new LinearLayout.LayoutParams(W, TITLE_H));
        list = new LinearLayout(dc);
        list.setOrientation(LinearLayout.VERTICAL);
        view.addView(list, new LinearLayout.LayoutParams(W, H - TITLE_H));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(W, H,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = onCluster ? X : 40;
        lp.y = onCluster ? Y : 40;
        try {
            wm.addView(view, lp);
        } catch (RuntimeException e) {
            DebugLog.w(TAG, "The cluster refused the window", e);
            view = null;
            list = null;
            return false;
        }
        return true;
    }

    private void draw() {
        if (list == null) return;
        list.removeAllViews();
        int rowH = (H - TITLE_H) / VISIBLE_ROWS;
        if (rows.text.isEmpty()) {
            list.addView(row(c.getString(R.string.menu_empty), false, false, rowH));
            return;
        }
        boolean flash = SystemClock.elapsedRealtime() < flashUntil;
        for (int n = first; n < Math.min(rows.text.size(), first + VISIBLE_ROWS); n++) {
            list.addView(row(rows.text.get(n), n == selected, n == selected && flash, rowH));
        }
    }

    private static TextView text(Context dc, String s, int px, boolean bold) {
        TextView t = new TextView(dc);
        t.setText(s);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        t.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(18, 0, 18, 0);
        return t;
    }

    private View row(String s, boolean selected, boolean flash, int h) {
        TextView t = text(list.getContext(), s, 24, selected);
        t.setTextColor(c.getColor(flash ? R.color.on_accent
                : selected ? R.color.cluster_text : R.color.cluster_text_dim));
        if (selected) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(c.getColor(flash ? R.color.cluster_accent : R.color.cluster_accent_fill));
            bg.setCornerRadius(8);
            t.setBackground(bg);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(W - 12, h);
        lp.leftMargin = 6;
        t.setLayoutParams(lp);
        return t;
    }
}
