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

import java.lang.reflect.Proxy;
import java.util.Arrays;

/**
 * An experiment, and a hack: the ECarX cluster ("DIM") has no slot for an arbitrary warning, but
 * it does have an incoming-call card that the driver answers or rejects with the wheel. Feeding it
 * a fake call named "Open the gate?" through {@code DimInteraction.getPhoneCallInteraction()} and
 * listening for answer/reject would be a question on the driver's screen answered from the wheel —
 * if the firmware lets a non-phone app do it at all. Only the diagnostics screen touches this; the
 * risk of clashing with a real call is why it is not wired to the popup until it is proven.
 */
final class DimCallCard {
    private static final String TAG = "DimCallCard";
    private static final String DIM_CLASS = "com.ecarx.xui.adaptapi.diminteraction.DimInteraction";
    private static final String PHONE_CLASS =
            "com.ecarx.xui.adaptapi.diminteraction.IPhoneCallInteraction";
    private static final String CALL_INFO_CLASS = PHONE_CLASS + "$ICallInfo";
    private static final String CALLBACK_CLASS = PHONE_CLASS + "$IPhoneCallInteractionCallback";

    static final int CALL_STATUS_IDLE = 0;
    static final int CALL_STATUS_RINGING = 8;
    static final int CALL_STATUS_IN_COMING_CALL = 12;
    static final int CALL_STATUS_END = 15;

    interface Listener {
        /** Main thread: what the cluster called back with — "onAnswerCall", "onRejectCall", … */
        void onDimEvent(String event);
    }

    private final Context context;
    private final VendorCanary canary;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Object dim;
    private Object phone;
    private Object callback;

    DimCallCard(Context context) {
        this.context = context.getApplicationContext();
        this.canary = new VendorCanary(context, "ecarx_dim");
    }

    static boolean isPresent() {
        try {
            Class.forName(DIM_CLASS);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Connects and reports what the DIM API answers; registers {@code listener} for callbacks. */
    String connect(Listener listener) {
        StringBuilder r = new StringBuilder();
        if (!isPresent()) {
            return "ecarx dim: not present\n";
        }
        if (phone != null) {
            return "ecarx dim: already connected\n";
        }
        if (!canary.arm()) {
            return "ecarx dim: disabled after crashes\n";
        }
        try {
            Class<?> dimClass = Class.forName(DIM_CLASS);
            dim = dimClass.getMethod("create", Context.class).invoke(null, context);
            r.append("dim create: ").append(dim != null ? dim.getClass().getName() : "null").append('\n');
            if (dim == null) {
                return r.toString();
            }
            r.append("dim showPresentationOption: ")
                    .append(safe(() -> dimClass.getMethod("getShowPresentationOption").invoke(dim)))
                    .append('\n');
            for (String getter : new String[]{"getVendorInteraction", "getNaviInteraction",
                    "getDimMenuInteraction", "getVrInteraction"}) {
                r.append("dim ").append(getter).append(": ")
                        .append(safe(() -> {
                            Object o = dimClass.getMethod(getter).invoke(dim);
                            return o == null ? "null" : o.getClass().getName();
                        })).append('\n');
            }
            phone = dimClass.getMethod("getPhoneCallInteraction").invoke(dim);
            r.append("dim getPhoneCallInteraction: ")
                    .append(phone != null ? phone.getClass().getName() : "null").append('\n');
            if (phone == null) {
                return r.toString();
            }
            Class<?> callbackClass = Class.forName(CALLBACK_CLASS);
            callback = Proxy.newProxyInstance(callbackClass.getClassLoader(),
                    new Class<?>[]{callbackClass}, (p, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return WheelKeys.defaultValue(p, method, args);
                        }
                        String event = method.getName()
                                + (args == null ? "" : Arrays.toString(args));
                        main.post(() -> listener.onDimEvent(event));
                        return WheelKeys.defaultValue(p, method, args);
                    });
            Class.forName(PHONE_CLASS).getMethod("registerPhoneCallback", callbackClass)
                    .invoke(phone, callback);
            r.append("dim registerPhoneCallback: ok\n");
        } catch (Throwable t) {
            r.append("dim failed: ").append(WheelKeys.rootCause(t)).append('\n');
            Log.w(TAG, "DIM connect failed", t);
        } finally {
            canary.disarm();
        }
        return r.toString();
    }

    /** Puts a fake call card with {@code title} on the cluster; {@code status} as in ICallInfo. */
    String show(String title, int status) {
        if (phone == null) {
            return "dim: not connected\n";
        }
        try {
            Class<?> infoClass = Class.forName(CALL_INFO_CLASS);
            Object info = Proxy.newProxyInstance(infoClass.getClassLoader(),
                    new Class<?>[]{infoClass}, (p, method, args) -> {
                        switch (method.getName()) {
                            case "getCallStatus": return status;
                            case "getContactName":
                            case "getTitle": return title;
                            case "getContactNumber":
                            case "getCompany": return "";
                            case "getCallId":
                            case "getActiveCallId":
                            case "getCallCount": return 1;
                            case "isHandFree": return true;
                            default: return WheelKeys.defaultValue(p, method, args);
                        }
                    });
            Class.forName(PHONE_CLASS).getMethod("updateCallInfo", infoClass).invoke(phone, info);
            return "dim updateCallInfo(status=" + status + "): ok\n";
        } catch (Throwable t) {
            Log.w(TAG, "updateCallInfo failed", t);
            return "dim updateCallInfo failed: " + WheelKeys.rootCause(t) + '\n';
        }
    }

    /** Takes the fake call down again: ended, then idle. */
    String clear() {
        return show("", CALL_STATUS_END) + show("", CALL_STATUS_IDLE);
    }

    void release() {
        if (phone != null && callback != null) {
            try {
                Class.forName(PHONE_CLASS)
                        .getMethod("unRegisterPhoneCallback", Class.forName(CALLBACK_CLASS))
                        .invoke(phone, callback);
            } catch (Throwable t) {
                Log.w(TAG, "unRegisterPhoneCallback failed", t);
            }
        }
        phone = null;
        callback = null;
        dim = null;
    }

    private interface Call {
        Object get() throws Exception;
    }

    private static String safe(Call call) {
        try {
            return String.valueOf(call.get());
        } catch (Throwable t) {
            return WheelKeys.rootCause(t);
        }
    }
}
