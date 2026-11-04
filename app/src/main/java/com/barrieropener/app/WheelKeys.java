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
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Steering-wheel buttons through the ECarX AdaptAPI ({@code com.ecarx.xui.adaptapi.input.Input}).
 * On a Geely head unit the wheel does not reach a foreground app as ordinary {@code KeyEvent}s —
 * a vendor input service owns it and hands a key to an app only when asked through
 * {@code requestKeysInterception}. Reached through reflection, like {@link GearMonitor}: the SDK
 * sits in the system classpath of those units only, and a direct reference would fail to link on
 * every other one.
 *
 * <p>Interception takes the key away from everyone else (media, the cluster menu) for as long as
 * it lasts, so it is held only while a question is on screen and released with it.
 */
final class WheelKeys {
    private static final String TAG = "WheelKeys";
    private static final String INPUT_CLASS = "com.ecarx.xui.adaptapi.input.Input";
    private static final String CALLBACK_CLASS = "com.ecarx.xui.adaptapi.input.IKeyCallback";
    private static final String KEYCODE_CLASS = "com.ecarx.xui.adaptapi.input.KeyCode";

    interface Listener {
        /** Called on the main thread. {@code pressed} is false for the release. */
        void onWheelKey(int keyCode, boolean pressed);
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final VendorCanary canary;
    private Object input;
    private Object callback;
    private final StringBuilder report = new StringBuilder();

    WheelKeys(Context context) {
        this.context = context.getApplicationContext();
        this.canary = new VendorCanary(context, "ecarx_input");
    }

    static boolean isPresent() {
        try {
            Class.forName(INPUT_CLASS);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Asks the vendor service for {@code keyCodes}. Safe to call on any unit: without the SDK, or
     * once it has crashed us {@link VendorCanary#MAX_CRASHES} times, it does nothing.
     *
     * @return whether the interception was registered
     */
    boolean start(int[] keyCodes, Listener listener) {
        stop();
        report.setLength(0);
        if (!isPresent()) {
            report.append("ecarx input: not present\n");
            return false;
        }
        if (!canary.arm()) {
            report.append("ecarx input: disabled after crashes\n");
            return false;
        }
        try {
            Class<?> inputClass = Class.forName(INPUT_CLASS);
            Class<?> callbackClass = Class.forName(CALLBACK_CLASS);
            Object created = inputClass.getMethod("create", Context.class).invoke(null, context);
            report.append("ecarx Input.create: ").append(created != null ? "ok" : "null").append('\n');
            if (created == null) {
                return false;
            }
            Object proxy = Proxy.newProxyInstance(callbackClass.getClassLoader(),
                    new Class<?>[]{callbackClass}, (p, method, args) -> {
                        String name = method.getName();
                        if (("onKeyPressed".equals(name) || "onKeyReleased".equals(name))
                                && args != null && args.length == 1 && args[0] instanceof Integer) {
                            int code = (Integer) args[0];
                            boolean pressed = "onKeyPressed".equals(name);
                            main.post(() -> listener.onWheelKey(code, pressed));
                            return true; // Consumed: the key was asked for, it is ours.
                        }
                        return defaultValue(p, method, args);
                    });
            Object granted = inputClass.getMethod("requestKeysInterception", int[].class, callbackClass)
                    .invoke(created, keyCodes, proxy);
            report.append("ecarx requestKeysInterception(").append(keyCodes.length).append("): ")
                    .append(granted instanceof int[] ? Arrays.toString((int[]) granted) : String.valueOf(granted))
                    .append('\n');
            input = created;
            callback = proxy;
            return true;
        } catch (Throwable t) {
            report.append("ecarx input failed: ").append(rootCause(t)).append('\n');
            Log.w(TAG, "Key interception failed", t);
            return false;
        } finally {
            canary.disarm();
        }
    }

    void stop() {
        if (input == null || callback == null) {
            return;
        }
        try {
            Class.forName(INPUT_CLASS)
                    .getMethod("abandonKeysInterception", Class.forName(CALLBACK_CLASS))
                    .invoke(input, callback);
        } catch (Throwable t) {
            Log.w(TAG, "abandonKeysInterception failed", t);
        }
        input = null;
        callback = null;
    }

    String report() {
        return report.toString() + canary.describe() + '\n';
    }

    /** Every key the vendor SDK has a name for — what the diagnostics screen asks for. */
    static int[] allVendorKeyCodes() {
        List<Integer> codes = new ArrayList<>();
        try {
            for (Field f : Class.forName(KEYCODE_CLASS).getFields()) {
                if (f.getType() == int.class && Modifier.isStatic(f.getModifiers())) {
                    int code = f.getInt(null);
                    if (code > 0 && !codes.contains(code)) {
                        codes.add(code);
                    }
                }
            }
        } catch (Throwable ignored) {
            // No vendor SDK: fall back to what the popup would ask for anyway.
        }
        if (codes.isEmpty()) {
            for (int code : WheelNavigator.INTERCEPTED_KEYS) {
                codes.add(code);
            }
        }
        int[] out = new int[codes.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = codes.get(i);
        }
        return out;
    }

    /** "KEYCODE_DMSM_LEFT", from the vendor's own table; null when it has no name for it. */
    static String vendorName(int code) {
        try {
            for (Field f : Class.forName(KEYCODE_CLASS).getFields()) {
                if (f.getType() == int.class && Modifier.isStatic(f.getModifiers())
                        && f.getInt(null) == code) {
                    return f.getName();
                }
            }
        } catch (Throwable ignored) {
            // Not a vendor unit.
        }
        return null;
    }

    /**
     * Marks the answer the wheel has selected. Size and opacity rather than Android focus: the
     * popup may be on a display, or in a window, that never gets input focus, and a focus ring is
     * too thin to read at a glance from the driver's seat anyway.
     */
    static void showSelection(View[] answers, int selected) {
        for (int i = 0; i < answers.length; i++) {
            View v = answers[i];
            if (v == null) {
                continue;
            }
            boolean on = i == selected;
            v.setAlpha(on ? 1f : (v.isEnabled() ? 0.55f : 0.35f));
            v.setScaleX(on ? 1.12f : 1f);
            v.setScaleY(on ? 1.12f : 1f);
        }
    }

    /** "22 DPAD_RIGHT" — the number for the report, a name when one is known. */
    static String describeKey(int code) {
        String name = vendorName(code);
        if (name == null) {
            name = KeyEvent.keyCodeToString(code);
        }
        return code + " " + (name.startsWith("KEYCODE_") ? name.substring(8) : name);
    }

    static Object defaultValue(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "hashCode": return System.identityHashCode(proxy);
            case "equals": return args != null && args.length == 1 && proxy == args[0];
            case "toString": return "BarrierOpenerProxy";
            default: break;
        }
        Class<?> r = method.getReturnType();
        if (r == boolean.class) return false;
        if (r == int.class) return 0;
        if (r == long.class) return 0L;
        if (r == float.class) return 0f;
        if (r == double.class) return 0d;
        return null;
    }

    static String rootCause(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
