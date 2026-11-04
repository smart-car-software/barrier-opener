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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

/**
 * Steering-wheel buttons through Geely's OneOS API service — the route that actually delivers them
 * on a Monjaro (KX11): the ECarX {@code Input} interception is accepted there and then never calls
 * back. The service hands out a key-input binder to which a listener is registered with the codes
 * it wants; while registered, those keys come to us instead of their usual owner.
 *
 * <p>Spoken in raw binder transactions rather than through a bundled client library: the protocol
 * is three calls, and the vendor's library is neither public nor ours to ship. The interface
 * descriptors and transaction numbers below are what the service on the unit answers to.
 */
final class OneOsKeys {
    private static final String TAG = "OneOsKeys";
    private static final String SERVICE_PACKAGE = "com.geely.service.oneosapi";
    private static final String SERVICE_CLASS = "com.geely.service.oneosapi.OneOSApiService";

    private static final String SERVICE_MANAGER = "com.geely.lib.oneosapi.IServiceManager";
    private static final int SM_GET_SERVICE = 2;
    /** {@code IServiceManager.getService} index of the key-input manager. */
    private static final int SERVICE_KEY_INPUT = 8;

    private static final String INPUT_MANAGER = "com.geely.lib.oneosapi.input.IInputManager";
    private static final int IM_REGISTER_LISTENER = 3;
    private static final int IM_UNREGISTER_LISTENER = 4;

    private static final String INPUT_LISTENER = "com.geely.lib.oneosapi.input.IInputListener";
    private static final int IL_ON_KEY_CODE_EVENT = 1;
    private static final int IL_LAST = 6; // onShortClick … onDoubleClick: (keyCode, function)
    /** {@code KeyCodeEvent} actions. */
    private static final int ACTION_DOWN = 0;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final StringBuilder report = new StringBuilder();
    private int[] keyCodes;
    private WheelKeys.Listener listener;
    private IBinder inputManager;
    private boolean bound;
    /** Diagnostics: also report the higher-level callbacks (short click, long press, …). */
    private EventSink eventSink;

    interface EventSink {
        void onOneOsEvent(String event);
    }

    private final Binder inputListener = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(INPUT_LISTENER);
                return true;
            }
            if (code < IL_ON_KEY_CODE_EVENT || code > IL_LAST) {
                return super.onTransact(code, data, reply, flags);
            }
            data.enforceInterface(INPUT_LISTENER);
            int keyCode = data.readInt();
            int second = data.readInt();
            if (code == IL_ON_KEY_CODE_EVENT) {
                int function = data.readInt();
                main.post(() -> {
                    WheelKeys.Listener l = listener;
                    if (l != null && second != 3) { // 3 = ACTION_CANCEL
                        l.onWheelKey(keyCode, second == ACTION_DOWN);
                    }
                    sink("event " + WheelKeys.describeKey(keyCode) + " action=" + second
                            + " fn=" + function);
                });
            } else {
                String[] names = {"", "", "shortClick", "holdStart", "holdStop", "longPress", "doubleClick"};
                main.post(() -> sink(names[code] + " " + WheelKeys.describeKey(keyCode)
                        + " fn=" + second));
            }
            if (reply != null) {
                reply.writeNoException();
            }
            return true;
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            note("oneos connected");
            try {
                IBinder input = getService(service, SERVICE_KEY_INPUT);
                note("oneos getService(" + SERVICE_KEY_INPUT + "): " + (input != null ? "ok" : "null"));
                if (input == null || keyCodes == null) {
                    return;
                }
                registerListener(input, keyCodes);
                inputManager = input;
                note("oneos registerListener(" + keyCodes.length + "): ok");
            } catch (Throwable t) {
                note("oneos failed: " + WheelKeys.rootCause(t));
                Log.w(TAG, "OneOS key registration failed", t);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            note("oneos disconnected");
            inputManager = null;
        }
    };

    OneOsKeys(Context context) {
        this.context = context.getApplicationContext();
    }

    void setEventSink(EventSink sink) {
        this.eventSink = sink;
    }

    /** Binds and registers asynchronously; harmless on units without the service. */
    void start(int[] keyCodes, WheelKeys.Listener listener) {
        stop();
        report.setLength(0);
        this.keyCodes = keyCodes;
        this.listener = listener;
        Intent intent = new Intent().setClassName(SERVICE_PACKAGE, SERVICE_CLASS);
        try {
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            note("oneos bindService: " + bound);
        } catch (Throwable t) {
            bound = false;
            note("oneos bindService failed: " + WheelKeys.rootCause(t));
        }
    }

    void stop() {
        listener = null;
        if (inputManager != null) {
            try {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(INPUT_MANAGER);
                    data.writeStrongBinder(inputListener);
                    data.writeString(context.getPackageName());
                    inputManager.transact(IM_UNREGISTER_LISTENER, data, reply, 0);
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            } catch (Throwable t) {
                Log.w(TAG, "unregisterListener failed", t);
            }
            inputManager = null;
        }
        if (bound) {
            try {
                context.unbindService(connection);
            } catch (RuntimeException ignored) {
                // Already unbound by the system.
            }
            bound = false;
        }
    }

    String report() {
        return report.toString();
    }

    private static IBinder getService(IBinder serviceManager, int type) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_MANAGER);
            data.writeInt(type);
            serviceManager.transact(SM_GET_SERVICE, data, reply, 0);
            reply.readException();
            return reply.readStrongBinder();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void registerListener(IBinder input, int[] codes) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(INPUT_MANAGER);
            data.writeStrongBinder(inputListener);
            data.writeString(context.getPackageName());
            data.writeIntArray(codes);
            input.transact(IM_REGISTER_LISTENER, data, reply, 0);
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void note(String line) {
        report.append(line).append('\n');
        Log.i(TAG, line);
        sink(line);
    }

    private void sink(String line) {
        EventSink s = eventSink;
        if (s != null) {
            s.onOneOsEvent(line);
        }
    }
}
