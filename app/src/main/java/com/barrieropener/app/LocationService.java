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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.location.Location;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class LocationService extends Service {
    private static final String TAG = "LocationService";
    private static final String CHANNEL_ID = "BarrierOpenerServiceChannel";
    private static final String POPUP_CHANNEL_ID = "BarrierPopupChannel";
    private static final int NOTIFICATION_ID = 1;
    public static final int POPUP_NOTIFICATION_ID = 2;
    public static final String ACTION_STOP = "com.barrieropener.app.STOP";

    private static volatile boolean _isStarted = false;

    /** How often the timer-based closing trigger is re-evaluated without a fresh GPS fix. */
    private static final long CLOSE_TICK_MS = 15_000L;

    /**
     * How long after the service starts the "you're parked in a zone — open it?" question stays on
     * offer. It exists for the moment the engine is switched on, so it waits for GPS and the network
     * to come up (tens of seconds on a cold head unit) but gives up well before it could surprise
     * someone with a popup unrelated to starting the car.
     */
    private static final long STARTUP_PROMPT_WINDOW_MS = 5 * 60_000L;

    /**
     * How long the startup question waits for the car API to say whether it can read the engine and
     * the belt at all. Its connect is bounded well under this; the cap is for a vendor service that hangs, which
     * must not take the question down with it.
     */
    private static final long CAR_API_WAIT_MS = 20_000L;

    private LocationManager locationManager;
    private BarrierRepository repository;
    private TriggerStateStore triggerState;
    private PendingCloseStore pendingClose;
    private GearMonitor gearMonitor;
    private HostReachability hostReachability;
    /** Which barriers the car has driven into since it was last outside them; see the class. */
    private final ApproachTracker approachTracker = new ApproachTracker();
    /** Gate-api barriers whose "is it already closed?" check is in flight, by id. */
    private final Set<Long> closeChecksInFlight = new HashSet<>();
    /** Gate-api barriers whose "is it already open?" check is in flight, by id. */
    private final Set<Long> openChecksInFlight = new HashSet<>();
    private Location lastLocation;
    /** True until the startup question has been asked, or its window has run out. */
    private boolean startupPromptPending;
    private long startupPromptDeadline;
    private long startupPromptArmedAt;
    private final Handler closeTicker = new Handler(Looper.getMainLooper());
    private final Runnable closeTick = new Runnable() {
        @Override
        public void run() {
            checkPendingCloses(repository.getAll(), lastLocation);
            // The startup question is retried here too: a car standing still can stop producing
            // fixes altogether (the provider filters by distance), and the network usually joins
            // seconds after the first fix rather than before it.
            offerStartupOpenFromLastFix();
            closeTicker.postDelayed(this, CLOSE_TICK_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        repository = new BarrierRepository(this);
        repository.registerInvalidator();
        triggerState = new TriggerStateStore(this);
        pendingClose = new PendingCloseStore(this);
        locationManager = new LocationManager(this);
        gearMonitor = new GearMonitor(this);
        hostReachability = new HostReachability();
        // Armed here rather than in onStartCommand: MainActivity re-issues startForegroundService
        // on every resume, and re-arming there would greet the driver with an "open the gate?"
        // popup every time they came back to the app while parked at home.
        armStartupPrompt();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Log.d(TAG, "Stop action received");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        Log.d(TAG, "Starting service...");
        _isStarted = true;

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        startLocationUpdates();
        gearMonitor.start(new GearMonitor.Listener() {
            @Override
            public void onShiftedToPark() {
                LocationService.this.onShiftedToPark();
            }

            @Override
            public void onCarStateChanged() {
                // The driver buckling up, the engine settling or the car API answering is what a
                // waiting startup question is waiting for; don't leave it to the next 15 s tick.
                offerStartupOpenFromLastFix();
            }
        });
        closeTicker.removeCallbacks(closeTick);
        closeTicker.postDelayed(closeTick, CLOSE_TICK_MS);

        return START_STICKY;
    }

    public static boolean isStarted() {
        return _isStarted;
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);

        NotificationChannel serviceChannel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        manager.createNotificationChannel(serviceChannel);

        NotificationChannel popupChannel = new NotificationChannel(
                POPUP_CHANNEL_ID,
                getString(R.string.notification_popup_channel_name),
                NotificationManager.IMPORTANCE_HIGH
        );
        popupChannel.enableVibration(true);
        popupChannel.setBypassDnd(true);
        manager.createNotificationChannel(popupChannel);
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent contentPi = PendingIntent.getActivity(
                this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, LocationService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(contentPi)
                .addAction(0, getString(R.string.notification_action_stop), stopPi)
                .setOngoing(true)
                .build();
    }

    private void startLocationUpdates() {
        locationManager.startLocationUpdates(new LocationCallback() {
            @Override
            public void onLocationUpdated(Location location) {
                checkNearbyBarriers(location);
            }

            @Override
            public void onLocationError(String error) {
                Log.e(TAG, "Location error: " + error);
            }

            @Override
            public void onProviderStatusChanged(String provider, boolean enabled) {
                Log.d(TAG, "Provider " + provider + " " + (enabled ? "enabled" : "disabled"));
            }
        });
    }

    private void checkNearbyBarriers(Location location) {
        List<Barrier> barriers = repository.getAll();
        lastLocation = location;
        triggerState.releaseFarBarriers(location.getLatitude(), location.getLongitude(), barriers);
        approachTracker.update(barriers, location.getLatitude(), location.getLongitude());
        checkPendingCloses(barriers, location);

        NearbyResolver.Resolution res = NearbyResolver.resolve(
                barriers,
                BarrierDetector.Fix.from(location),
                id -> triggerState.isTriggered(id),
                approachTracker::isArmed,
                isParked());

        if (offerStartupOpen(res.inZone)) {
            return; // The startup question is on screen; don't stack detection on top of it.
        }

        if (res.primary == null) {
            return; // Nothing new to suggest by direction.
        }

        if (res.primary.getActionType() == Barrier.ActionType.GATE_API) {
            // A gate that can say it is already open is not offered for opening — the driver just
            // hears so. If the gate can't be read in time, the question is put as it always was.
            Barrier primary = res.primary;
            unlessGateAlreadyOpen(primary, () -> deliverDetectionPopup(res), () -> {
                Log.i(TAG, primary.getName() + " is already open; not asking");
                Toast.makeText(this, getString(R.string.gate_already_open, primary.getName()),
                        Toast.LENGTH_LONG).show();
                triggerState.markTriggered(primary.getId());
                approachTracker.disarm(primary.getId());
            });
            return;
        }
        deliverDetectionPopup(res);
    }

    private void deliverDetectionPopup(NearbyResolver.Resolution res) {
        boolean delivered = triggerBarrierPopup(res.primary, res.inZone);

        // Suppress every fresh directional match so popups don't stack — but only once we actually
        // reached the user. If neither the direct launch nor the notification can surface (e.g. all
        // notifications are blocked and background-activity launch is denied), leave the flags unset
        // so the next location update retries instead of silently muting the barrier. Each suppressed
        // match still appears in the popup's nearby list with its own call button.
        if (delivered) {
            for (Barrier b : res.freshMatches) {
                triggerState.markTriggered(b.getId());
                approachTracker.disarm(b.getId());
            }
        }
    }

    /**
     * Asks a gate-api gate where it is before offering to open it. {@code show} runs unless the gate
     * reports itself open or opening, in which case {@code alreadyOpen} runs instead. A gate that
     * cannot be read — network down, Home Assistant down — is offered anyway: on the approach the
     * popup's job is to appear before the car reaches the gate, and a failed "Yes" at least
     * explains itself with a toast. One check per barrier at a time, since fixes arrive every
     * second and the request may take ten.
     */
    private void unlessGateAlreadyOpen(Barrier barrier, Runnable show, Runnable alreadyOpen) {
        long barrierId = barrier.getId();
        if (!openChecksInFlight.add(barrierId)) {
            return;
        }
        GateApiClient.fetchState(barrier.getApiBaseUrl(), barrier.getApiToken(),
                new GateApiClient.Callback() {
                    @Override
                    public void onSuccess(GateState state) {
                        openChecksInFlight.remove(barrierId);
                        if (!_isStarted) {
                            return;
                        }
                        boolean open = state.haReachable && (state.state == GateState.State.OPEN
                                || state.state == GateState.State.OPENING);
                        (open ? alreadyOpen : show).run();
                    }

                    @Override
                    public void onFailure(GateApiClient.Failure failure) {
                        openChecksInFlight.remove(barrierId);
                        Log.d(TAG, "Could not read the gate before offering to open: " + failure);
                        if (_isStarted) {
                            show.run();
                        }
                    }
                });
    }

    /**
     * Arms the startup question. The service being created is the app's proxy for "the engine was
     * just switched on": on a head unit BootReceiver starts it, and boot is the ignition.
     */
    private void armStartupPrompt() {
        startupPromptPending = true;
        startupPromptArmedAt = SystemClock.elapsedRealtime();
        startupPromptDeadline = startupPromptArmedAt + STARTUP_PROMPT_WINDOW_MS;
    }

    /**
     * Asks whether to open the barrier the car is parked inside, once — the driver has just got in
     * and wants the gate opening before they set off.
     *
     * <p>Deliberately bypasses the suppression flag: the barrier is almost always still marked
     * triggered from driving in yesterday, which is precisely what keeps the normal path quiet
     * here. Marking it triggered again afterwards stops the driving path from re-asking as the car
     * pulls away.
     *
     * @return true when the question has been put and the caller should not also run detection
     */
    private boolean offerStartupOpen(List<Barrier> inZone) {
        if (!startupPromptPending) {
            return false;
        }
        long now = SystemClock.elapsedRealtime();
        if (now > startupPromptDeadline) {
            startupPromptPending = false;
            return false;
        }
        Barrier barrier = StartupPromptResolver.candidate(inZone);
        if (barrier == null) {
            return false; // Not standing in anyone's zone.
        }
        boolean carApiAnswered = gearMonitor.hasAnswered()
                || now - startupPromptArmedAt > CAR_API_WAIT_MS;
        switch (StartupPromptResolver.carGate(barrier, carApiAnswered, gearMonitor.isInPark(),
                gearMonitor.isDriverBuckled(), gearMonitor.engineRpm())) {
            case WAIT_FOR_CAR_API:
                return false; // onCarStateChanged re-runs this once the car API has answered.
            case WAITING_FOR_DRIVER:
            case WARMING_UP:
                // A remote start or a cold winter warm-up can outlast the whole window. The window
                // is about not surprising a driver long after they started the car; a car still
                // standing in the zone, parked, waiting for its driver or its engine, hasn't set
                // off yet — so it waits. The unit going to sleep is what ends it: elapsed time
                // keeps counting through sleep, so the deadline is long gone by the next wake.
                startupPromptDeadline = Math.max(startupPromptDeadline,
                        now + STARTUP_PROMPT_WINDOW_MS);
                return false;
            case READY:
                break;
        }
        if (StartupPromptResolver.needsReachableHost(barrier)
                && !hostReachability.isReadyFor(barrier, false)) {
            // The engine has just been started and Wi-Fi is still coming up: probe, and let the
            // probe itself re-run this the moment the gate's endpoint answers.
            hostReachability.probe(barrier, false, this::offerStartupOpenFromLastFix);
            return false;
        }
        if (barrier.getActionType() == Barrier.ActionType.GATE_API) {
            // The car is parked inside the zone; if the gate already stands open there is nothing
            // to ask. The question stays pending while the gate is being read, so a fix arriving
            // in the meantime neither re-asks nor gives up.
            unlessGateAlreadyOpen(barrier, () -> putStartupQuestion(barrier, inZone), () -> {
                Log.i(TAG, barrier.getName() + " is already open; startup question dropped");
                Toast.makeText(this, getString(R.string.gate_already_open, barrier.getName()),
                        Toast.LENGTH_LONG).show();
                startupPromptPending = false;
                triggerState.markTriggered(barrier.getId());
            });
            return false;
        }
        return putStartupQuestion(barrier, inZone);
    }

    /** Puts the startup question; true when it reached the driver and is therefore spent. */
    private boolean putStartupQuestion(Barrier barrier, List<Barrier> inZone) {
        if (!startupPromptPending) {
            return false; // Answered or dropped while the gate was being read.
        }
        // Same rule as the driving path: only a question that actually reached the driver counts as
        // asked, otherwise the next fix retries.
        if (!triggerBarrierPopup(barrier, inZone)) {
            return false;
        }
        startupPromptPending = false;
        triggerState.markTriggered(barrier.getId());
        return true;
    }

    /** The tick's route into {@link #offerStartupOpen}; it has no fix of its own to work from. */
    private void offerStartupOpenFromLastFix() {
        if (!startupPromptPending || lastLocation == null) {
            return;
        }
        NearbyResolver.Resolution res = NearbyResolver.resolve(
                repository.getAll(),
                BarrierDetector.Fix.from(lastLocation),
                id -> triggerState.isTriggered(id),
                isParked());
        offerStartupOpen(res.inZone);
    }

    /**
     * Whether the car is known to be standing in Park. Only a real reading counts: on a unit whose
     * gear can't be read this is false, and detection works as it did before the gear was available
     * at all.
     */
    private boolean isParked() {
        return Boolean.TRUE.equals(gearMonitor.isInPark());
    }

    /**
     * The Park closing trigger: the gearbox says the journey is over, so every gate opened on the
     * way here and configured for it gets its closing question now.
     */
    private void onShiftedToPark() {
        Map<Long, Long> pending = pendingClose.all();
        if (pending.isEmpty()) {
            return;
        }
        if (lastLocation == null) {
            // Without a fix there is no way to tell this gate's yard from a car park across town.
            Log.d(TAG, "Shifted into Park, but there is no fix to place the car by");
            return;
        }
        for (Barrier b : repository.getAll()) {
            if (!pending.containsKey(b.getId())
                    || CloseResolver.evaluateOnPark(b, distanceTo(b)) == null) {
                continue;
            }
            offerClose(b, CloseResolver.Reason.PARK);
        }
    }

    /**
     * Offers to close every barrier whose closing trigger has fired. Runs both on location updates
     * and on the periodic tick, because the timer must still fire when the car is parked and no
     * fresh fixes arrive — a gate left open is exactly the case where the vehicle stopped moving.
     */
    private void checkPendingCloses(List<Barrier> barriers, Location location) {
        Map<Long, Long> pending = pendingClose.all();
        if (pending.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Barrier b : barriers) {
            Long openedAt = pending.get(b.getId());
            if (openedAt == null) {
                continue;
            }
            if (!b.isClosable()) {
                // Closing was switched off after the gate was opened; drop the stale entry.
                pendingClose.clear(b.getId());
                continue;
            }
            double distance = distanceFrom(location, b);
            // Note this fix before judging: the one that puts the car at the gate may be the same
            // one the departure rule needs to know about.
            pendingClose.noteDistance(b.getId(), distance);
            CloseResolver.Reason reason = CloseResolver.evaluate(b, openedAt, now, distance,
                    pendingClose.closest(b.getId()));
            if (reason != null) {
                offerClose(b, reason);
            }
        }
    }

    /**
     * Puts the closing question — but only once the gate's endpoint is actually reachable. Until
     * then the pending entry is deliberately left standing: "ask once" must not be spent on a
     * question whose "Yes" would fail, and the next tick retries.
     */
    private void offerClose(Barrier barrier, CloseResolver.Reason reason) {
        if (!hostReachability.isReadyFor(barrier, true)) {
            // Park is an event, not a state: re-running the periodic check would not bring it back,
            // so the retry is bound to this barrier and this reason.
            long barrierId = barrier.getId();
            hostReachability.probe(barrier, true, () -> retryClose(barrierId, reason));
            return;
        }
        if (barrier.getActionType() == Barrier.ActionType.GATE_API) {
            offerCloseKnowingTheGate(barrier, reason);
            return;
        }
        putCloseQuestion(barrier, reason);
    }

    /**
     * A gate-api gate can say where it is, so ask it first: a gate the driver already closed by
     * hand — or that closed itself after "open slightly" — gets no question at all, just a word
     * that it is closed. And while the service reports Home Assistant down, the question is not
     * put and the entry is not spent, exactly as when the host is silent: "Yes" would only fail.
     */
    private void offerCloseKnowingTheGate(Barrier barrier, CloseResolver.Reason reason) {
        long barrierId = barrier.getId();
        if (!closeChecksInFlight.add(barrierId)) {
            return; // The previous check is still waiting on the service.
        }
        GateApiClient.fetchState(barrier.getApiBaseUrl(), barrier.getApiToken(),
                new GateApiClient.Callback() {
                    @Override
                    public void onSuccess(GateState state) {
                        closeChecksInFlight.remove(barrierId);
                        if (!_isStarted || pendingClose.openedAt(barrierId) == 0L) {
                            return; // Service gone, or cleared while we were asking.
                        }
                        if (!state.haReachable) {
                            Log.d(TAG, "Service up but Home Assistant down; closing question waits");
                            deferClose(barrierId, reason);
                            return;
                        }
                        if (state.state == GateState.State.CLOSED) {
                            Log.i(TAG, barrier.getName() + " is already closed; not asking");
                            pendingClose.clear(barrierId);
                            Toast.makeText(LocationService.this,
                                    getString(R.string.gate_already_closed, barrier.getName()),
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        putCloseQuestion(barrier, reason);
                    }

                    @Override
                    public void onFailure(GateApiClient.Failure failure) {
                        closeChecksInFlight.remove(barrierId);
                        Log.d(TAG, "Could not read the gate before asking to close: " + failure);
                        if (_isStarted) {
                            deferClose(barrierId, reason);
                        }
                    }
                });
    }

    /** Asks again after a tick, for a reason that — unlike the timer — would not recur by itself. */
    private void deferClose(long barrierId, CloseResolver.Reason reason) {
        closeTicker.postDelayed(() -> retryClose(barrierId, reason), CLOSE_TICK_MS);
    }

    private void putCloseQuestion(Barrier barrier, CloseResolver.Reason reason) {
        Log.d(TAG, "Offering to close " + barrier.getName() + " (" + reason + ")");
        triggerClosePopup(barrier);
        // Asked once; the popup clears the entry when the driver answers or it times out.
        pendingClose.clear(barrier.getId());
    }

    /** The endpoint answered after we deferred: put the question now, if it is still pending. */
    private void retryClose(long barrierId, CloseResolver.Reason reason) {
        if (pendingClose.openedAt(barrierId) == 0L) {
            return; // Answered, cleared or expired while we were waiting for the host.
        }
        for (Barrier barrier : repository.getAll()) {
            if (barrier.getId() == barrierId && barrier.isClosable()) {
                offerClose(barrier, reason);
                return;
            }
        }
    }

    /** Metres from the last known fix to the barrier; negative when there is no fix to measure from. */
    private double distanceTo(Barrier barrier) {
        return distanceFrom(lastLocation, barrier);
    }

    private static double distanceFrom(Location location, Barrier barrier) {
        if (location == null) {
            return -1;
        }
        float[] distance = new float[1];
        Location.distanceBetween(location.getLatitude(), location.getLongitude(),
                barrier.getLatitude(), barrier.getLongitude(), distance);
        return distance[0];
    }

    private void triggerClosePopup(Barrier barrier) {
        Intent popupIntent = new Intent(this, BarrierPopupActivity.class);
        popupIntent.putExtra("barrier_id", barrier.getId());
        popupIntent.putExtra("barrier_name", barrier.getName());
        popupIntent.putExtra(BarrierPopupActivity.EXTRA_MODE, BarrierPopupActivity.MODE_CLOSE);
        popupIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            startActivity(popupIntent);
        } catch (Exception e) {
            Log.w(TAG, "Could not start close popup directly", e);
        }
        showPopupFullScreenNotification(barrier, popupIntent);
    }

    private boolean triggerBarrierPopup(Barrier primary, List<Barrier> inZone) {
        Intent popupIntent = buildPopupIntent(primary, inZone);

        // Best effort: a direct launch works in the foreground / on lenient OEMs, but on Android
        // 10–12 a background-activity start from a service is silently dropped, so we don't rely on
        // it. The high-priority full-screen-intent notification is the reliable path that surfaces
        // over other apps and the lock screen, so its deliverability is what gates suppression.
        try {
            startActivity(popupIntent);
        } catch (Exception e) {
            Log.w(TAG, "Could not start popup activity directly", e);
        }
        return showPopupFullScreenNotification(primary, popupIntent);
    }

    private Intent buildPopupIntent(Barrier primary, List<Barrier> inZone) {
        // The nearby list is every in-zone barrier except the one already shown as the suggestion.
        List<Barrier> nearby = new ArrayList<>();
        for (Barrier b : inZone) {
            if (b.getId() != primary.getId()) {
                nearby.add(b);
            }
        }

        // Only ids travel in the intent — the popup re-reads the barriers from the database, so
        // whichever action (call or webhook) each one carries is always the current one.
        long[] ids = new long[nearby.size()];
        for (int i = 0; i < nearby.size(); i++) {
            ids[i] = nearby.get(i).getId();
        }

        Intent popupIntent = new Intent(this, BarrierPopupActivity.class);
        popupIntent.putExtra("barrier_id", primary.getId());
        popupIntent.putExtra("barrier_name", primary.getName());
        popupIntent.putExtra("nearby_ids", ids);
        popupIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return popupIntent;
    }

    /** Posts the heads-up / full-screen popup notification. Returns whether it can actually reach
     *  the user (notifications enabled and the popup channel not blocked). */
    private boolean showPopupFullScreenNotification(Barrier barrier, Intent popupIntent) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return false;
        }

        PendingIntent fullScreenPi = PendingIntent.getActivity(
                this, (int) barrier.getId(), popupIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification n = new NotificationCompat.Builder(this, POPUP_CHANNEL_ID)
                .setContentTitle(getString(R.string.popup_title_window, barrier.getName()))
                .setContentText(BarrierAction.describeTarget(barrier))
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(fullScreenPi, true)
                .setContentIntent(fullScreenPi)
                .setAutoCancel(true)
                // Outlives the popup's own countdown, so the notification is never the thing that
                // takes the question away while it is still on screen.
                .setTimeoutAfter(35_000L)
                .build();

        manager.notify(POPUP_NOTIFICATION_ID, n);
        return popupNotificationsDeliverable(manager);
    }

    private boolean popupNotificationsDeliverable(NotificationManager manager) {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            return false;
        }
        NotificationChannel channel = manager.getNotificationChannel(POPUP_CHANNEL_ID);
        return channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        _isStarted = false;
        // Everything on the ticker, not just the tick: deferred closing retries ride on it too.
        closeTicker.removeCallbacksAndMessages(null);
        gearMonitor.stop();
        hostReachability.shutdown();
        locationManager.stopLocationUpdates();
        repository.unregisterInvalidator();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
