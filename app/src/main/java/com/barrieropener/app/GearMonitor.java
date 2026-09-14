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

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Reports when the driver shifts into Park, using whichever car API the head unit happens to ship.
 *
 * <p>Everything here goes through reflection on purpose. {@code android.car} is a system library
 * that only exists on units whose firmware ships it: it cannot be compiled against from an ordinary
 * APK, and on a unit without it any direct reference would fail to link at install time.
 *
 * <p>"Whichever API" is not hedging — a real Geely head unit answered like this:
 * {@code createCar(Context)} missing (car-lib predates it, only the asynchronous
 * {@code ServiceConnection} form exists), {@code CarPropertyManager} present but reporting
 * {@code GEAR_SELECTION} as unavailable and reading a constant 0, and
 * {@code CarPropertyEventCallback} — the class the modern subscription needs — absent altogether.
 * The gear lives in the older {@link #SENSOR_MANAGER_CLASS} on such units. So the sources are tried
 * in order, and a source only counts when it yields a <i>valid</i> gear value: a property that
 * politely returns 0 forever is worse than no property at all, because it looks like it works.
 *
 * <p>Two more things cost a trip to a real car to learn, so don't undo them: {@code CAR_POWERTRAIN}
 * is a <i>dangerous</i> permission and must be granted at runtime, and some firmwares accept a
 * subscription and then never deliver an event — hence {@link #POLL_MS}, which re-reads the gear
 * whenever it can be read at all.
 *
 * <p>The listener fires on a <i>transition</i> into Park, never on the first value read. A car
 * whose ignition was just switched on is already in Park, and firing then would ask about closing
 * a gate the moment the driver arrives at the car rather than when they arrive home.
 *
 * <p>The engine speed and the driver's seat belt ride along on the same connection, for the
 * startup prompt's rules ({@link StartupPromptResolver#carGate}). Both are only read from the vendor
 * SDK, and only polled: that question tolerates a two-second lag, and more subscriptions are more
 * things a firmware could accept and then ignore.
 */
@SuppressLint("PrivateApi") // android.car is an optional *system* library, not a hidden API.
public class GearMonitor {
    private static final String TAG = "GearMonitor";

    /** Dangerous permission guarding the powertrain data; must be requested at runtime. */
    public static final String PERMISSION_POWERTRAIN = "android.car.permission.CAR_POWERTRAIN";

    private static final String CAR_CLASS = "android.car.Car";
    private static final String PROPERTY_MANAGER_CLASS =
            "android.car.hardware.property.CarPropertyManager";
    /** Modern subscription interface; absent on older car-libs. */
    private static final String PROPERTY_CALLBACK_CLASS =
            "android.car.hardware.property.CarPropertyManager$CarPropertyEventCallback";
    private static final String SENSOR_MANAGER_CLASS = "android.car.hardware.CarSensorManager";
    private static final String SENSOR_LISTENER_CLASS =
            "android.car.hardware.CarSensorManager$OnSensorChangedListener";
    /** {@code Car.PROPERTY_SERVICE} / {@code Car.SENSOR_SERVICE}. */
    private static final String PROPERTY_SERVICE = "property";
    private static final String SENSOR_SERVICE = "sensor";

    /** {@code VehiclePropertyIds.GEAR_SELECTION} — where the selector lever is. */
    private static final int PROP_GEAR_SELECTION = 289408000;
    /** {@code VehiclePropertyIds.CURRENT_GEAR} — fallback for units that don't publish the lever. */
    private static final int PROP_CURRENT_GEAR = 289408001;
    private static final int[] GEAR_PROPERTIES = {PROP_GEAR_SELECTION, PROP_CURRENT_GEAR};
    /** {@code CarSensorManager.SENSOR_TYPE_GEAR}; read from the class when it is there. */
    private static final int SENSOR_TYPE_GEAR = 7;
    /** {@code VehicleGear.GEAR_PARK}. */
    private static final int GEAR_PARK = 0x0004;
    /** {@code CarPropertyManager.SENSOR_RATE_ONCHANGE}. */
    private static final float RATE_ON_CHANGE = 0f;

    /** How often the gear is re-read when the car service takes the subscription but stays mute. */
    private static final long POLL_MS = 2_000L;
    /** The legacy connect is asynchronous; this is how long we wait for the service to bind. */
    private static final long LEGACY_CONNECT_TIMEOUT_MS = 5_000L;

    // --- ECarX AdaptAPI: the vendor SDK on Geely head units, present in the system classpath.
    private static final String ECARX_CAR_CLASS = "com.ecarx.xui.adaptapi.car.Car";
    private static final String ECARX_ICAR_CLASS = "com.ecarx.xui.adaptapi.car.ICar";
    private static final String ECARX_SENSOR_CLASS = "com.ecarx.xui.adaptapi.car.sensor.ISensor";
    private static final String ECARX_SENSOR_EVENT_CLASS =
            "com.ecarx.xui.adaptapi.car.sensor.ISensorEvent";
    private static final String ECARX_SENSOR_LISTENER_CLASS =
            "com.ecarx.xui.adaptapi.car.sensor.ISensor$ISensorListener";
    /** {@code ISensor.SENSOR_TYPE_GEAR}; read from the on-device class, this is the fallback. */
    private static final int ECARX_SENSOR_TYPE_GEAR = 2097664;
    /** {@code ISensorEvent.GEAR_PARK} — the vendor numbers its gears nothing like VehicleGear. */
    private static final int ECARX_GEAR_PARK = 2097712;
    private static final int ECARX_GEAR_UNKNOWN = -1;
    /** {@code ISensor.RATE_NORMAL}. */
    private static final int ECARX_RATE_NORMAL = 3;
    /** {@code ISensor.SENSOR_TYPE_RPM}; an analogue sensor, read with {@code getSensorLatestValue}. */
    private static final int ECARX_SENSOR_TYPE_RPM = 1050880;
    /** {@code ISensor.SENSOR_TYPE_SAFE_BELT_DRIVER}. */
    private static final int ECARX_SENSOR_TYPE_BELT_DRIVER = 2101760;
    /** {@code ISensorEvent.SAFE_BELT_STATE_BUCKLED} / {@code SAFE_BELT_STATE_UNBUCKLED}. */
    private static final int ECARX_BELT_BUCKLED = 2101762;
    private static final int ECARX_BELT_UNBUCKLED = 2101761;

    /**
     * Crash canary for the vendor SDK. On some firmwares {@code Car.create} kills the process
     * natively — no Java catch survives that — so an attempt is recorded (with {@code commit},
     * which a native death cannot lose) before the SDK is touched and cleared once it answers.
     * After {@link #MAX_VENDOR_CRASHES} unfinished attempts the vendor path stays off, and the
     * app runs like it would on any other head unit instead of crash-looping at every boot.
     */
    private static final String CANARY_PREFS = "gear_vendor_canary";
    private static final String KEY_ATTEMPTS = "attempts";
    private static final String KEY_SURVIVED = "survived";
    private static final int MAX_VENDOR_CRASHES = 3;

    public interface Listener {
        /** Called on the main thread when the transmission has just been put into Park. */
        void onShiftedToPark();

        /**
         * Called on the main thread when the first connection attempt has come back (whatever it
         * found), and then whenever the engine speed or the driver's belt reading changes.
         */
        default void onCarStateChanged() {}
    }

    /** Where a usable gear reading came from. */
    private enum Source { NONE, PROPERTY, SENSOR, VENDOR }

    /** One source, resolved: the managers it needed and the reading that proved it works. */
    private static final class Detection {
        Source source = Source.NONE;
        Object propertyManager;
        Object sensorManager;
        /** ECarX {@code ISensor}, when the vendor SDK is the one answering. */
        Object vendorSensor;
        int property = PROP_GEAR_SELECTION;
        /** What "Park" is worth in this source's numbering; the vendor's is not VehicleGear's. */
        int parkValue = GEAR_PARK;
        Integer gear;
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** Car binder calls can block, and the legacy connect waits on a latch — never on the main thread. */
    private HandlerThread carThread;
    private Handler carHandler;

    private Object car;
    private Detection detection;
    private Object subscription;
    private boolean polling;
    private volatile Listener listener;
    /** Set once the unit has told us it has no gear data; asked once, not on every service start. */
    private volatile boolean unavailable;
    /** The permission state we gave up under, so a grant that arrives later gets another attempt. */
    private volatile boolean unavailableWithPermission;
    /** Null until the first reading arrives; see the class comment on why the first one is muted. */
    private Integer lastGear;
    /** Mirrors {@link #lastGear} for readers on the main thread; null while the gear is unknown. */
    private volatile Boolean inPark;
    /** Latest engine speed; null while it can't be read. */
    private volatile Float engineRpm;
    /** Latest driver's belt state; null while it can't be read. */
    private volatile Boolean driverBuckled;
    /** Set once the first connection attempt has finished, successfully or not. */
    private volatile boolean answered;

    public GearMonitor(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Cheap check for the UI: does this head unit ship any car API at all? */
    public static boolean isSupportedOnDevice(Context context) {
        return carClass() != null || vendorApiPresent();
    }

    private static boolean vendorApiPresent() {
        try {
            Class.forName(ECARX_CAR_CLASS);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Whether the powertrain permission has been granted; without it nothing can be read. */
    public static boolean hasPowertrainPermission(Context context) {
        return ContextCompat.checkSelfPermission(context, PERMISSION_POWERTRAIN)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** "P", "D", … plus the raw value, because a vendor may use a value we don't have a name for. */
    public static String describeGear(Integer gear) {
        if (gear == null) {
            return "?";
        }
        String name;
        switch (gear) {
            case 0x0001: name = "N"; break;
            case 0x0002: name = "R"; break;
            case GEAR_PARK: name = "P"; break;
            case 0x0008: name = "D"; break;
            // The vendor SDK numbers its gears in a range of its own.
            case ECARX_GEAR_PARK: name = "P"; break;
            case 2097696: name = "D"; break;
            case 2097680: name = "N"; break;
            case 2097728: name = "R"; break;
            case ECARX_GEAR_UNKNOWN: name = "unknown"; break;
            default: name = "gear"; break;
        }
        return name + " (" + gear + ")";
    }

    public boolean isRunning() {
        return subscription != null || polling;
    }

    /**
     * Whether the first connection attempt has come back. Until it has, a missing reading means
     * "not yet" rather than "this unit has none", and the difference decides whether to wait.
     */
    public boolean hasAnswered() {
        return answered;
    }

    /** True in Park, false in any other known gear, null when the gear can't be read. */
    public Boolean isInPark() {
        return inPark;
    }

    /** Engine speed in rpm, or null when this unit doesn't report it (or not yet). */
    public Float engineRpm() {
        return engineRpm;
    }

    /** True when the driver's belt is buckled, false when not, null when it can't be read (or not yet). */
    public Boolean isDriverBuckled() {
        return driverBuckled;
    }

    /**
     * Starts watching. Returns immediately: the connection itself happens on {@link #carThread},
     * because a head unit's car service can take seconds to bind at boot — exactly when this runs.
     */
    public void start(Listener listener) {
        this.listener = listener;
        if (unavailable && unavailableWithPermission != hasPowertrainPermission(context)) {
            // The driver granted (or revoked) powertrain access since we gave up — worth retrying.
            // MainActivity re-issues startForegroundService on every resume, so coming back from
            // the permission dialog is exactly when this runs.
            unavailable = false;
        }
        if (isRunning() || unavailable) {
            return;
        }
        if (carThread == null) {
            carThread = new HandlerThread("gear-monitor");
            carThread.start();
            carHandler = new Handler(carThread.getLooper());
        }
        carHandler.post(this::connectAndSubscribe);
    }

    private void connectAndSubscribe() {
        StringBuilder r = new StringBuilder();
        try {
            if (carClass() == null && !vendorApiPresent()) {
                r.append("no car API on this unit\n");
                markUnavailable();
                return;
            }
            if (!hasPowertrainPermission(context)) {
                // Not fatal on every firmware — some grant powertrain reads without it — so we go
                // on and let the sources themselves have the final word.
                r.append(PERMISSION_POWERTRAIN).append(": DENIED (trying anyway)\n");
            }
            // Vendor first: on a unit that has it, this answers in milliseconds, while the
            // standard route would first spend up to LEGACY_CONNECT_TIMEOUT_MS binding a car
            // service that turns out to publish nothing.
            detection = detectVendor(context, r);
            if (detection.source == Source.NONE) {
                car = openCar(context, r);
                detection = detect(context, car, r);
            }
            if (detection.source == Source.NONE) {
                r.append("no usable gear source\n");
                stopInternal();
                markUnavailable();
                return;
            }
            lastGear = detection.gear; // Seeds the transition detector; muted by design.
            inPark = detection.gear == detection.parkValue;
            engineRpm = readEngineRpm(detection, r);
            driverBuckled = readDriverBelt(detection, r);
            subscription = subscribe(r);
            // Backstop: some firmwares accept the subscription and then never deliver an event,
            // and on this unit the reading itself is what proved the source works anyway.
            polling = true;
            carHandler.postDelayed(poll, POLL_MS);
            r.append("watching ").append(detection.source)
                    .append(subscription != null ? " (events + polling)" : " (polling only)")
                    .append(", gear now ").append(describeGear(lastGear))
                    .append(", engine ").append(describeRpm(engineRpm))
                    .append(", belt ").append(describeBelt(driverBuckled)).append('\n');
        } catch (Throwable t) {
            r.append("connect failed: ").append(t).append('\n');
            stopInternal();
            markUnavailable();
        } finally {
            Log.d(TAG, r.toString().trim());
            answered = true;
            notifyCarStateChanged();
        }
    }

    private void notifyCarStateChanged() {
        mainHandler.post(() -> {
            Listener l = listener;
            if (l != null) {
                l.onCarStateChanged();
            }
        });
    }

    /** Stops retrying until something that could change the answer — a permission grant — happens. */
    private void markUnavailable() {
        unavailable = true;
        unavailableWithPermission = hasPowertrainPermission(context);
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!polling) {
                return;
            }
            Integer value = read(detection, null);
            if (isValidGear(value)) {
                handleGear(value);
            }
            Float rpm = readEngineRpm(detection, null);
            Boolean buckled = readDriverBelt(detection, null);
            if (!Objects.equals(rpm, engineRpm) || !Objects.equals(buckled, driverBuckled)) {
                engineRpm = rpm;
                driverBuckled = buckled;
                notifyCarStateChanged();
            }
            carHandler.postDelayed(this, POLL_MS);
        }
    };

    /** @return the registered listener/callback proxy, or null when no subscription could be made. */
    private Object subscribe(StringBuilder r) {
        switch (detection.source) {
            case PROPERTY: return subscribeProperty(r);
            case SENSOR: return subscribeSensor(r);
            case VENDOR: return subscribeVendor(r);
            default: return null;
        }
    }

    private Object subscribeVendor(StringBuilder r) {
        try {
            Class<?> listenerClass = Class.forName(ECARX_SENSOR_LISTENER_CLASS);
            Object proxy = Proxy.newProxyInstance(listenerClass.getClassLoader(),
                    new Class<?>[]{listenerClass}, vendorListener);
            Class<?> sensorClass = Class.forName(ECARX_SENSOR_CLASS);
            Object ok;
            try {
                ok = sensorClass
                        .getMethod("registerListener", listenerClass, int.class, int.class)
                        .invoke(detection.vendorSensor, proxy, vendorGearSensorType(),
                                intConstantOr(ECARX_SENSOR_CLASS, "RATE_NORMAL", ECARX_RATE_NORMAL));
            } catch (NoSuchMethodException e) {
                ok = sensorClass.getMethod("registerListener", listenerClass, int.class)
                        .invoke(detection.vendorSensor, proxy, vendorGearSensorType());
            }
            r.append("ecarx registerListener(gear): ")
                    .append(Boolean.FALSE.equals(ok) ? "refused" : "ok").append('\n');
            return Boolean.FALSE.equals(ok) ? null : proxy;
        } catch (Throwable t) {
            r.append("ecarx registerListener(gear): ").append(rootCause(t)).append('\n');
            return null;
        }
    }

    private Object subscribeProperty(StringBuilder r) {
        try {
            Class<?> callbackClass = Class.forName(PROPERTY_CALLBACK_CLASS);
            Object proxy = Proxy.newProxyInstance(callbackClass.getClassLoader(),
                    new Class<?>[]{callbackClass}, propertyCallback);
            Object ok = Class.forName(PROPERTY_MANAGER_CLASS)
                    .getMethod("registerCallback", callbackClass, int.class, float.class)
                    .invoke(detection.propertyManager, proxy, detection.property, RATE_ON_CHANGE);
            r.append("registerCallback(").append(detection.property).append("): ")
                    .append(Boolean.TRUE.equals(ok) ? "ok" : "refused").append('\n');
            return Boolean.TRUE.equals(ok) ? proxy : null;
        } catch (Throwable t) {
            r.append("registerCallback(").append(detection.property).append("): ")
                    .append(rootCause(t)).append('\n');
            return null;
        }
    }

    private Object subscribeSensor(StringBuilder r) {
        try {
            Class<?> listenerClass = Class.forName(SENSOR_LISTENER_CLASS);
            Object proxy = Proxy.newProxyInstance(listenerClass.getClassLoader(),
                    new Class<?>[]{listenerClass}, sensorListener);
            Object ok = Class.forName(SENSOR_MANAGER_CLASS)
                    .getMethod("registerListener", listenerClass, int.class, int.class)
                    .invoke(detection.sensorManager, proxy, sensorTypeGear(), sensorRate());
            r.append("registerListener(gear): ")
                    .append(Boolean.FALSE.equals(ok) ? "refused" : "ok").append('\n');
            return Boolean.FALSE.equals(ok) ? null : proxy;
        } catch (Throwable t) {
            r.append("registerListener(gear): ").append(rootCause(t)).append('\n');
            return null;
        }
    }

    public void stop() {
        listener = null;
        if (carHandler != null) {
            carHandler.post(this::stopInternal);
            carThread.quitSafely();
            carThread = null;
            carHandler = null;
        } else {
            stopInternal();
        }
    }

    private void stopInternal() {
        polling = false;
        try {
            if (subscription != null && detection != null) {
                if (detection.source == Source.PROPERTY) {
                    Class.forName(PROPERTY_MANAGER_CLASS)
                            .getMethod("unregisterCallback", Class.forName(PROPERTY_CALLBACK_CLASS))
                            .invoke(detection.propertyManager, subscription);
                } else if (detection.source == Source.VENDOR) {
                    Class.forName(ECARX_SENSOR_CLASS)
                            .getMethod("unregisterListener", Class.forName(ECARX_SENSOR_LISTENER_CLASS))
                            .invoke(detection.vendorSensor, subscription);
                } else {
                    Class.forName(SENSOR_MANAGER_CLASS)
                            .getMethod("unregisterListener", Class.forName(SENSOR_LISTENER_CLASS))
                            .invoke(detection.sensorManager, subscription);
                }
            }
        } catch (Throwable t) {
            Log.d(TAG, "Error while unsubscribing from the gear: " + t);
        }
        disconnect(car);
        subscription = null;
        detection = null;
        car = null;
        lastGear = null;
        inPark = null;
        engineRpm = null;
        driverBuckled = null;
    }

    private final InvocationHandler propertyCallback = (proxy, method, args) -> {
        if ("onChangeEvent".equals(method.getName()) && args != null && args.length > 0) {
            Integer value = propertyValueOf(args[0]);
            if (isValidGear(value)) {
                handleGear(value);
            }
            return null;
        }
        return defaultProxyAnswer(proxy, method, args, "GearPropertyCallback");
    };

    private final InvocationHandler vendorListener = (proxy, method, args) -> {
        // ISensorListener: onSensorEventChanged(int sensorType, int value) for discrete signals
        // like the gear; the float and status callbacks are for other sensors entirely.
        if ("onSensorEventChanged".equals(method.getName()) && args != null && args.length > 1
                && args[0] instanceof Integer && args[1] instanceof Integer
                && (Integer) args[0] == vendorGearSensorType()) {
            int value = (Integer) args[1];
            if (isValidGear(value)) {
                handleGear(value);
            }
            return null;
        }
        return defaultProxyAnswer(proxy, method, args, "GearVendorListener");
    };

    private final InvocationHandler sensorListener = (proxy, method, args) -> {
        if ("onSensorChanged".equals(method.getName()) && args != null && args.length > 0) {
            Integer value = sensorValueOf(args[0]);
            if (isValidGear(value)) {
                handleGear(value);
            }
            return null;
        }
        return defaultProxyAnswer(proxy, method, args, "GearSensorListener");
    };

    /** The proxy still has to behave like an object to whatever collection the car service keeps it in. */
    private static Object defaultProxyAnswer(Object proxy, Method method, Object[] args, String name) {
        switch (method.getName()) {
            case "equals": return proxy == args[0];
            case "hashCode": return System.identityHashCode(proxy);
            case "toString": return name;
            default: return null;
        }
    }

    /** Single place where a new gear value becomes (or doesn't become) a question for the driver. */
    private synchronized void handleGear(int gear) {
        Integer previous = lastGear;
        lastGear = gear;
        int park = detection != null ? detection.parkValue : GEAR_PARK;
        inPark = gear == park;
        if (previous == null || previous == gear || gear != park) {
            return;
        }
        Log.d(TAG, "Shifted into Park (from " + describeGear(previous) + ")");
        // Delivery thread is the car service's business; the rest of the app lives on the main one.
        mainHandler.post(() -> {
            Listener l = listener;
            if (l != null) {
                l.onShiftedToPark();
            }
        });
    }

    // --- source detection ----------------------------------------------------------------------

    /**
     * Tries every gear source in order and returns the first one that yields a real reading.
     * A source that answers 0 — "unknown gear", which is what this unit's property manager does
     * forever — is rejected: watching it would look like the feature works while it never fires.
     */
    private static Detection detect(Context context, Object car, StringBuilder r) {
        Detection d = detectVendor(context, r);
        if (d.source != Source.NONE) {
            return d;
        }
        if (car == null) {
            return d;
        }

        d.propertyManager = getCarManager(car, PROPERTY_SERVICE, r);
        if (d.propertyManager != null) {
            for (int property : GEAR_PROPERTIES) {
                Integer value = readProperty(d.propertyManager, property, r);
                if (isValidGear(value)) {
                    d.source = Source.PROPERTY;
                    d.property = property;
                    d.gear = value;
                    return d;
                }
            }
        }

        d.sensorManager = getCarManager(car, SENSOR_SERVICE, r);
        if (d.sensorManager != null) {
            Integer value = readSensor(d.sensorManager, r);
            if (isValidGear(value)) {
                d.source = Source.SENSOR;
                d.gear = value;
            }
        }
        return d;
    }

    /**
     * The ECarX AdaptAPI route, and on a Geely head unit the only one that works: its
     * {@code CarPropertyManager} publishes an empty property list and its {@code CarSensorManager}
     * no sensors at all, while the vendor SDK — which sits in the system classpath, so reflection
     * reaches it without bundling anything — answers with the gear straight away.
     */
    private static Detection detectVendor(Context context, StringBuilder r) {
        Detection d = new Detection();
        try {
            Class.forName(ECARX_CAR_CLASS);
        } catch (Throwable t) {
            return d; // Not a vendor unit; nothing to report.
        }
        if (!armVendorCanary(context, r)) {
            return d;
        }
        try {
            Object car = Class.forName(ECARX_CAR_CLASS).getMethod("create", Context.class)
                    .invoke(null, context);
            r.append("ecarx Car.create: ").append(car != null ? "ok" : "null").append('\n');
            if (car == null) {
                return d;
            }
            Object sensor = Class.forName(ECARX_ICAR_CLASS).getMethod("getSensorManager")
                    .invoke(car);
            r.append("ecarx getSensorManager: ").append(sensor != null ? "ok" : "null").append('\n');
            if (sensor == null) {
                return d;
            }
            r.append("ecarx isSensorSupported(gear): ")
                    .append(vendorSensorStatus(sensor, vendorGearSensorType())).append('\n');
            Integer gear = readVendorGear(sensor, r);
            if (isValidGear(gear)) {
                d.source = Source.VENDOR;
                d.vendorSensor = sensor;
                d.gear = gear;
                d.parkValue = vendorConstant("GEAR_PARK", ECARX_GEAR_PARK);
            }
        } catch (Throwable t) {
            r.append("ecarx failed: ").append(rootCause(t)).append('\n');
        } finally {
            // Survived the vendor SDK: whatever it answered, it did not take the process with it.
            disarmVendorCanary(context);
        }
        return d;
    }

    private static Integer readVendorGear(Object sensor, StringBuilder r) {
        if (sensor == null) {
            return null;
        }
        try {
            Object value = Class.forName(ECARX_SENSOR_CLASS).getMethod("getSensorEvent", int.class)
                    .invoke(sensor, vendorGearSensorType());
            if (r != null) {
                r.append("ecarx getSensorEvent(gear): ")
                        .append(value instanceof Integer
                                ? describeGear((Integer) value) : String.valueOf(value))
                        .append('\n');
            }
            return value instanceof Integer ? (Integer) value : null;
        } catch (Throwable t) {
            if (r != null) {
                r.append("ecarx getSensorEvent(gear): ").append(rootCause(t)).append('\n');
            }
            return null;
        }
    }

    private static String vendorSensorStatus(Object sensor, int sensorType) {
        try {
            return String.valueOf(Class.forName(ECARX_SENSOR_CLASS)
                    .getMethod("isSensorSupported", int.class)
                    .invoke(sensor, sensorType));
        } catch (Throwable t) {
            return String.valueOf(rootCause(t));
        }
    }

    /**
     * Engine speed from whichever source the gear came from — which in practice means the vendor
     * SDK: the standard sources on the units we know of publish nothing, so they aren't asked.
     */
    private static Float readEngineRpm(Detection detection, StringBuilder r) {
        if (detection == null || detection.source != Source.VENDOR) {
            if (r != null) {
                r.append("engine rpm: not read from ")
                        .append(detection == null ? Source.NONE : detection.source).append('\n');
            }
            return null;
        }
        int type = intConstantOr(ECARX_SENSOR_CLASS, "SENSOR_TYPE_RPM", ECARX_SENSOR_TYPE_RPM);
        if (r != null) {
            r.append("ecarx isSensorSupported(rpm): ")
                    .append(vendorSensorStatus(detection.vendorSensor, type)).append('\n');
        }
        try {
            Object value = Class.forName(ECARX_SENSOR_CLASS)
                    .getMethod("getSensorLatestValue", int.class)
                    .invoke(detection.vendorSensor, type);
            if (r != null) {
                r.append("ecarx getSensorLatestValue(rpm): ").append(value).append('\n');
            }
            if (!(value instanceof Float)) {
                return null;
            }
            float rpm = (Float) value;
            // Negative is the SDK's "unknown"; NaN would be a firmware that doesn't know either.
            return Float.isNaN(rpm) || rpm < 0 ? null : rpm;
        } catch (Throwable t) {
            if (r != null) {
                r.append("ecarx getSensorLatestValue(rpm): ").append(rootCause(t)).append('\n');
            }
            return null;
        }
    }

    /**
     * The driver's belt, from the vendor SDK only, like the rpm. Anything but the two real states —
     * -1 "unknown" included — is no reading: a firmware that answers something else for a belt it
     * doesn't wire up must not look like a driver who never buckles.
     */
    private static Boolean readDriverBelt(Detection detection, StringBuilder r) {
        if (detection == null || detection.source != Source.VENDOR) {
            if (r != null) {
                r.append("driver belt: not read from ")
                        .append(detection == null ? Source.NONE : detection.source).append('\n');
            }
            return null;
        }
        int type = intConstantOr(ECARX_SENSOR_CLASS, "SENSOR_TYPE_SAFE_BELT_DRIVER",
                ECARX_SENSOR_TYPE_BELT_DRIVER);
        if (r != null) {
            r.append("ecarx isSensorSupported(belt): ")
                    .append(vendorSensorStatus(detection.vendorSensor, type)).append('\n');
        }
        try {
            Object value = Class.forName(ECARX_SENSOR_CLASS).getMethod("getSensorEvent", int.class)
                    .invoke(detection.vendorSensor, type);
            if (r != null) {
                r.append("ecarx getSensorEvent(belt): ").append(value).append('\n');
            }
            if (!(value instanceof Integer)) {
                return null;
            }
            int state = (Integer) value;
            if (state == vendorConstant("SAFE_BELT_STATE_BUCKLED", ECARX_BELT_BUCKLED)) {
                return true;
            }
            if (state == vendorConstant("SAFE_BELT_STATE_UNBUCKLED", ECARX_BELT_UNBUCKLED)) {
                return false;
            }
            return null;
        } catch (Throwable t) {
            if (r != null) {
                r.append("ecarx getSensorEvent(belt): ").append(rootCause(t)).append('\n');
            }
            return null;
        }
    }

    /** For the log: "buckled", "unbuckled" or "?". */
    private static String describeBelt(Boolean buckled) {
        return buckled == null ? "?" : buckled ? "buckled" : "unbuckled";
    }

    /** Whole rpm for the log; "?" when there is no reading. */
    private static String describeRpm(Float rpm) {
        return rpm == null ? "?" : String.valueOf(Math.round(rpm));
    }

    private static int vendorGearSensorType() {
        Integer fromClass = intConstant(ECARX_SENSOR_CLASS, "SENSOR_TYPE_GEAR");
        return fromClass != null ? fromClass : ECARX_SENSOR_TYPE_GEAR;
    }

    /** Vendor gear constants live on {@code ISensorEvent}; the literals are only a fallback. */
    private static int vendorConstant(String name, int fallback) {
        Integer fromClass = intConstant(ECARX_SENSOR_EVENT_CLASS, name);
        return fromClass != null ? fromClass : fallback;
    }

    /**
     * @return false when the vendor SDK has already killed us often enough that it is not worth
     *         another try. Uses {@code commit()} deliberately: an asynchronous write would not
     *         survive the native crash it exists to count.
     */
    private static boolean armVendorCanary(Context context, StringBuilder r) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(CANARY_PREFS, Context.MODE_PRIVATE);
        int attempts = prefs.getInt(KEY_ATTEMPTS, 0);
        boolean survived = prefs.getBoolean(KEY_SURVIVED, false);
        if (!survived && attempts >= MAX_VENDOR_CRASHES) {
            r.append("ecarx: disabled after ").append(attempts)
                    .append(" crashed attempts\n");
            return false;
        }
        prefs.edit().putInt(KEY_ATTEMPTS, attempts + 1).commit();
        return true;
    }

    private static void disarmVendorCanary(Context context) {
        context.getApplicationContext().getSharedPreferences(CANARY_PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_ATTEMPTS, 0).putBoolean(KEY_SURVIVED, true).commit();
    }

    /** Reads the gear from whichever source {@code detection} settled on. */
    private static Integer read(Detection detection, StringBuilder r) {
        if (detection == null) {
            return null;
        }
        switch (detection.source) {
            case PROPERTY: return readProperty(detection.propertyManager, detection.property, r);
            case SENSOR: return readSensor(detection.sensorManager, r);
            case VENDOR: return readVendorGear(detection.vendorSensor, r);
            default: return null;
        }
    }

    /** 0 ({@code VehicleGear}) and -1 ({@code ISensorEvent}) both mean "unknown gear". */
    private static boolean isValidGear(Integer gear) {
        return gear != null && gear != 0 && gear != ECARX_GEAR_UNKNOWN;
    }

    private static Integer readProperty(Object manager, int propertyId, StringBuilder r) {
        if (manager == null) {
            return null;
        }
        try {
            Object value = Class.forName(PROPERTY_MANAGER_CLASS)
                    .getMethod("getIntProperty", int.class, int.class)
                    .invoke(manager, propertyId, 0);
            if (r != null) {
                r.append("getIntProperty(").append(propertyId).append("): ")
                        .append(value instanceof Integer
                                ? describeGear((Integer) value) : String.valueOf(value))
                        .append('\n');
            }
            return value instanceof Integer ? (Integer) value : null;
        } catch (Throwable t) {
            if (r != null) {
                r.append("getIntProperty(").append(propertyId).append("): ")
                        .append(rootCause(t)).append('\n');
            }
            return null;
        }
    }

    /**
     * The pre-{@code CarPropertyManager} route: {@code CarSensorManager} publishes the gear as a
     * sensor, and that is the API the older car-libs on Geely/ECARX head units actually implement.
     */
    private static Integer readSensor(Object manager, StringBuilder r) {
        if (manager == null) {
            return null;
        }
        try {
            Object event = Class.forName(SENSOR_MANAGER_CLASS)
                    .getMethod("getLatestSensorEvent", int.class)
                    .invoke(manager, sensorTypeGear());
            Integer value = sensorValueOf(event);
            if (r != null) {
                r.append("getLatestSensorEvent(gear): ")
                        .append(event == null ? "null" : describeGear(value)).append('\n');
            }
            return value;
        } catch (Throwable t) {
            if (r != null) {
                r.append("getLatestSensorEvent(gear): ").append(rootCause(t)).append('\n');
            }
            return null;
        }
    }

    private static int sensorTypeGear() {
        Integer fromClass = intConstant(SENSOR_MANAGER_CLASS, "SENSOR_TYPE_GEAR");
        return fromClass != null ? fromClass : SENSOR_TYPE_GEAR;
    }

    /** Rate constants were renumbered between car-lib versions; 0 (fastest) is valid in all of them. */
    private static int sensorRate() {
        Integer normal = intConstant(SENSOR_MANAGER_CLASS, "SENSOR_RATE_NORMAL");
        return normal != null ? normal : 0;
    }

    private static int intConstantOr(String className, String fieldName, int fallback) {
        Integer value = intConstant(className, fieldName);
        return value != null ? value : fallback;
    }

    private static Integer intConstant(String className, String fieldName) {
        try {
            Field field = Class.forName(className).getField(fieldName);
            Object value = field.get(null);
            return value instanceof Integer ? (Integer) value : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** @param carPropertyValue a {@code CarPropertyValue}, read reflectively. */
    private static Integer propertyValueOf(Object carPropertyValue) {
        try {
            Object value = carPropertyValue.getClass().getMethod("getValue").invoke(carPropertyValue);
            return value instanceof Integer ? (Integer) value : null;
        } catch (Throwable t) {
            Log.d(TAG, "Unreadable gear property value: " + t);
            return null;
        }
    }

    /**
     * @param carSensorEvent a {@code CarSensorEvent}. Its gear lives in the public {@code intValues}
     *                       array; {@code getGearData()} is the typed accessor and exists only on
     *                       some versions, so the raw array comes first.
     */
    private static Integer sensorValueOf(Object carSensorEvent) {
        if (carSensorEvent == null) {
            return null;
        }
        try {
            Object values = carSensorEvent.getClass().getField("intValues").get(carSensorEvent);
            if (values != null && Array.getLength(values) > 0) {
                return (Integer) Array.get(values, 0);
            }
        } catch (Throwable ignored) {
            // Fall through to the typed accessor.
        }
        try {
            Object gearData = carSensorEvent.getClass().getMethod("getGearData",
                    Class.forName("android.car.hardware.CarSensorEvent$GearData"))
                    .invoke(carSensorEvent, (Object) null);
            Object gear = gearData.getClass().getField("gear").get(gearData);
            return gear instanceof Integer ? (Integer) gear : null;
        } catch (Throwable t) {
            Log.d(TAG, "Unreadable gear sensor event: " + t);
            return null;
        }
    }

    // --- connection helpers ---------------------------------------------------------------------

    private static Class<?> carClass() {
        try {
            return Class.forName(CAR_CLASS);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Opens a Car instance, trying every shape {@code createCar} has had. The one-argument form is
     * recent; the {@code ServiceConnection} form is what Android 9/10-era car-libs shipped — and it
     * is the one a real Geely unit answered to — so it binds asynchronously and is waited on here
     * (never on the main thread — see {@link #start}).
     */
    private static Object openCar(Context context, StringBuilder r) {
        Class<?> carClass = carClass();
        if (carClass == null) {
            return null;
        }
        try {
            Object car = carClass.getMethod("createCar", Context.class).invoke(null, context);
            r.append("createCar(Context): ").append(car != null ? "ok" : "null").append('\n');
            if (car != null) {
                return car;
            }
        } catch (Throwable t) {
            r.append("createCar(Context): ").append(rootCause(t)).append('\n');
        }
        try {
            Object car = carClass.getMethod("createCar", Context.class, Handler.class)
                    .invoke(null, context, null);
            r.append("createCar(Context, Handler): ").append(car != null ? "ok" : "null").append('\n');
            if (car != null) {
                return car;
            }
        } catch (Throwable t) {
            r.append("createCar(Context, Handler): ").append(rootCause(t)).append('\n');
        }
        try {
            final CountDownLatch bound = new CountDownLatch(1);
            ServiceConnection connection = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    bound.countDown();
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                }
            };
            Object car = carClass.getMethod("createCar", Context.class, ServiceConnection.class)
                    .invoke(null, context, connection);
            if (car != null) {
                carClass.getMethod("connect").invoke(car);
                boolean ok = bound.await(LEGACY_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                r.append("createCar(Context, ServiceConnection): ")
                        .append(ok ? "connected" : "timeout").append('\n');
                if (ok) {
                    return car;
                }
                disconnect(car);
            }
        } catch (Throwable t) {
            r.append("createCar(Context, ServiceConnection): ").append(rootCause(t)).append('\n');
        }
        return null;
    }

    private static Object getCarManager(Object car, String service, StringBuilder r) {
        try {
            Object manager = car.getClass().getMethod("getCarManager", String.class)
                    .invoke(car, service);
            r.append("getCarManager(").append(service).append("): ")
                    .append(manager != null ? "ok" : "null").append('\n');
            return manager;
        } catch (Throwable t) {
            r.append("getCarManager(").append(service).append("): ").append(rootCause(t)).append('\n');
            return null;
        }
    }

    private static void disconnect(Object car) {
        if (car == null) {
            return;
        }
        try {
            car.getClass().getMethod("disconnect").invoke(car);
        } catch (Throwable t) {
            Log.d(TAG, "Error while disconnecting from the car service: " + t);
        }
    }

    /** Reflection wraps the interesting exception in an InvocationTargetException; unwrap it. */
    private static Throwable rootCause(Throwable t) {
        return t.getCause() != null ? t.getCause() : t;
    }
}
