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

import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;

import java.util.ArrayList;
import java.util.List;

public class BarrierPopupActivity extends AppCompatActivity {
    private static final String TAG = "BarrierPopupActivity";
    private static final long AUTO_DISMISS_DELAY_MS = 10_000L;
    private static final long COUNTDOWN_TICK_MS = 1_000L;

    private SoundPool soundPool;
    private int popupSoundId;
    private boolean soundLoaded;
    private boolean shouldPlaySoundWhenLoaded;

    private TextView barrierNameText;
    private TextView autoDismissText;
    private Button btnWait;
    private CountDownTimer countdown;

    /** Intent extra: "close" puts the popup in closing mode; anything else means opening. */
    public static final String EXTRA_MODE = "popup_mode";
    public static final String MODE_CLOSE = "close";

    private String barrierName;
    private boolean closeMode;
    private long primaryId = -1;
    private Barrier primaryBarrier;
    private long[] nearbyIds;

    /** Snapshot of all barriers; used to resolve ids and to recompute the in-zone list live. */
    private List<Barrier> allBarriers = new ArrayList<>();
    private LocationManager popupLocationManager;
    private String lastNearbySignature;

    private final LocationCallback popupLocationCallback = new LocationCallback() {
        @Override
        public void onLocationUpdated(Location location) {
            recomputeNearby(location);
        }

        @Override
        public void onLocationError(String error) {
            // Keep whatever list we already have.
        }

        @Override
        public void onProviderStatusChanged(String provider, boolean enabled) {
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showOverLockscreen();
        setContentView(R.layout.activity_barrier_popup);

        initViews();
        initSoundPool();
        bindFromIntent();
        startLocationTracking();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // singleInstance activity: a fresh barrier (new popup, or a tap on the full-screen
        // notification) is delivered here, not via onCreate. Re-bind so the UI reflects it.
        setIntent(intent);
        bindFromIntent();
    }

    private void bindFromIntent() {
        long previousPrimaryId = primaryId;
        // Reload before resolving ids: the popup can be re-delivered long after it first appeared,
        // and the user may have edited a barrier's action in the meantime.
        loadBarriers();
        getIntentData();
        boolean primaryChanged = primaryId != previousPrimaryId;

        updateUI();
        // Force a re-render of the nearby list for this (possibly new) primary barrier.
        lastNearbySignature = null;
        buildNearbyList();

        // Only re-alert (reset the Wait button, restart the countdown, replay the sound) when the
        // suggested barrier actually changed. A same-primary redelivery must NOT undo a "Wait" the
        // user pressed or restart the auto-dismiss out from under them.
        if (primaryChanged) {
            resetWaitButton();
            restartCountdown();
            playSound();
        }

        // The popup is now on screen, so the heads-up notification that backed the full-screen
        // intent is redundant — clear it so it doesn't linger in the shade after we're done.
        cancelPopupNotification();
    }

    private void cancelPopupNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.cancel(LocationService.POPUP_NOTIFICATION_ID);
        }
    }

    private void resetWaitButton() {
        btnWait.setEnabled(true);
        btnWait.setBackground(AppCompatResources.getDrawable(this, R.drawable.button_blue_background));
    }

    private void showOverLockscreen() {
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (km != null) {
            km.requestDismissKeyguard(this, null);
        }
    }

    private void initSoundPool() {
        if (soundPool != null) return;
        try {
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            soundPool = new SoundPool.Builder()
                    .setMaxStreams(1)
                    .setAudioAttributes(audioAttributes)
                    .build();
            soundPool.setOnLoadCompleteListener((pool, sampleId, status) -> {
                if (status == 0 && sampleId == popupSoundId) {
                    soundLoaded = true;
                    if (shouldPlaySoundWhenLoaded) {
                        playSoundInternal();
                    }
                }
            });
            popupSoundId = soundPool.load(this, R.raw.popup_sound, 1);
        } catch (RuntimeException e) {
            Log.e(TAG, "Error initializing sound pool", e);
        }
    }

    private void initViews() {
        barrierNameText = findViewById(R.id.textBarrierName);
        autoDismissText = findViewById(R.id.textAutoDismiss);

        findViewById(R.id.btnYes).setOnClickListener(v -> confirmAction());
        findViewById(R.id.btnNo).setOnClickListener(v -> dismissPopup());
        btnWait = findViewById(R.id.btnWait);
        btnWait.setOnClickListener(v -> cancelAutoDismiss());
        btnWait.setEnabled(true);
        btnWait.setBackground(AppCompatResources.getDrawable(this, R.drawable.button_blue_background));
    }

    private void getIntentData() {
        Intent intent = getIntent();
        barrierName = intent.getStringExtra("barrier_name");
        closeMode = MODE_CLOSE.equals(intent.getStringExtra(EXTRA_MODE));
        primaryId = intent.getLongExtra("barrier_id", -1);
        nearbyIds = intent.getLongArrayExtra("nearby_ids");
        primaryBarrier = findBarrier(primaryId);
    }

    private void loadBarriers() {
        DatabaseHelper db = new DatabaseHelper(this);
        allBarriers = db.getAllBarriers();
        db.close();
    }

    private Barrier findBarrier(long id) {
        for (Barrier b : allBarriers) {
            if (b.getId() == id) {
                return b;
            }
        }
        return null;
    }

    /** Resolves the ids the service handed us into live barrier records. */
    private List<Barrier> resolve(long[] ids) {
        List<Barrier> resolved = new ArrayList<>();
        if (ids != null) {
            for (long id : ids) {
                Barrier b = findBarrier(id);
                if (b != null) {
                    resolved.add(b);
                }
            }
        }
        return resolved;
    }

    /**
     * Subscribes to location for the lifetime of the popup so the "also in range" list updates
     * live — a barrier with a smaller radius that the car only enters after the popup appeared
     * will show up as soon as we cross into its zone.
     */
    private void startLocationTracking() {
        popupLocationManager = new LocationManager(this);
        popupLocationManager.startLocationUpdates(popupLocationCallback);
    }

    /** Recomputes the in-zone list from the current location and re-renders if it changed. */
    private void recomputeNearby(Location location) {
        List<Barrier> inZone = new ArrayList<>();
        for (Barrier b : allBarriers) {
            if (b.getId() == primaryId) {
                continue; // The suggested barrier is shown above, not in the list.
            }
            if (distanceTo(location, b) <= b.getDetectionRadius()) {
                inZone.add(b);
            }
        }
        inZone.sort((a, b) -> Double.compare(distanceTo(location, a), distanceTo(location, b)));
        renderNearby(inZone);
    }

    private static float distanceTo(Location location, Barrier barrier) {
        float[] d = new float[1];
        Location.distanceBetween(location.getLatitude(), location.getLongitude(),
                barrier.getLatitude(), barrier.getLongitude(), d);
        return d[0];
    }

    /**
     * Keyed on the SET of ids (order-independent) so GPS jitter that merely reorders equidistant
     * rows doesn't tear down and re-inflate the list (which would flicker and could swallow an
     * in-progress tap on an open button). Only a membership change rebuilds.
     */
    private static String signatureOf(List<Barrier> barriers) {
        long[] ids = new long[barriers.size()];
        for (int i = 0; i < barriers.size(); i++) {
            ids[i] = barriers.get(i).getId();
        }
        java.util.Arrays.sort(ids);
        StringBuilder sb = new StringBuilder("id");
        for (long id : ids) sb.append(id).append(',');
        return sb.toString();
    }

    /** Renders the nearby list from the intent extras (initial paint before the first fix). */
    private void buildNearbyList() {
        // Closing is about one specific gate the driver left open; offering the neighbours' open
        // buttons in that moment would only invite a mis-tap.
        renderNearby(closeMode ? new ArrayList<>() : resolve(nearbyIds));
    }

    /**
     * Renders the "also in range" list: every barrier whose zone the car is inside, regardless of
     * direction, each with its own open button. Skips the rebuild when the set is unchanged so the
     * per-second location updates don't flicker the rows or steal a tap mid-press.
     */
    private void renderNearby(List<Barrier> barriers) {
        String signature = signatureOf(barriers);
        if (signature.equals(lastNearbySignature)) {
            return;
        }
        lastNearbySignature = signature;

        View divider = findViewById(R.id.nearbyDivider);
        TextView header = findViewById(R.id.nearbyHeader);
        LinearLayout container = findViewById(R.id.nearbyContainer);
        container.removeAllViews();

        if (barriers.isEmpty()) {
            divider.setVisibility(View.GONE);
            header.setVisibility(View.GONE);
            container.setVisibility(View.GONE);
            return;
        }

        divider.setVisibility(View.VISIBLE);
        header.setVisibility(View.VISIBLE);
        container.setVisibility(View.VISIBLE);

        LayoutInflater inflater = LayoutInflater.from(this);
        for (final Barrier barrier : barriers) {
            View row = inflater.inflate(R.layout.item_popup_nearby, container, false);
            TextView name = row.findViewById(R.id.nearbyName);
            Button open = row.findViewById(R.id.nearbyCall);

            name.setText(barrier.getName());
            open.setText(barrier.getActionType() == Barrier.ActionType.WEBHOOK
                    ? R.string.action_open : R.string.call);
            open.setOnClickListener(v -> {
                BarrierAction.trigger(this, barrier);
                dismissPopup();
            });
            container.addView(row);
        }
    }

    private void updateUI() {
        if (barrierName != null) {
            barrierNameText.setText(getString(
                    closeMode ? R.string.popup_close_title_window : R.string.popup_title_window,
                    barrierName));
        }
        autoDismissText.setVisibility(View.VISIBLE);
        autoDismissText.setText(getString(R.string.auto_dismiss,
                (int) (AUTO_DISMISS_DELAY_MS / 1000L)));
    }

    private void restartCountdown() {
        if (countdown != null) {
            countdown.cancel();
        }
        autoDismissText.setVisibility(View.VISIBLE);
        countdown = new CountDownTimer(AUTO_DISMISS_DELAY_MS, COUNTDOWN_TICK_MS) {
            @Override
            public void onTick(long millisUntilFinished) {
                int seconds = (int) Math.ceil(millisUntilFinished / 1000.0);
                autoDismissText.setText(getString(R.string.auto_dismiss, seconds));
            }

            @Override
            public void onFinish() {
                dismissPopup();
            }
        };
        countdown.start();
    }

    private void cancelAutoDismiss() {
        if (countdown != null) {
            countdown.cancel();
            countdown = null;
        }
        autoDismissText.setVisibility(View.GONE);
        btnWait.setEnabled(false);
        btnWait.setBackground(AppCompatResources.getDrawable(this, R.drawable.button_gray_background));
    }

    private void playSound() {
        if (soundLoaded) {
            playSoundInternal();
        } else {
            shouldPlaySoundWhenLoaded = true;
        }
    }

    private void playSoundInternal() {
        if (soundPool == null) return;
        try {
            soundPool.play(popupSoundId, 1.0f, 1.0f, 1, 0, 1.0f);
        } catch (RuntimeException e) {
            Log.e(TAG, "Error playing sound", e);
        }
    }

    private void confirmAction() {
        if (closeMode) {
            BarrierAction.triggerClose(this, primaryBarrier);
        } else {
            BarrierAction.trigger(this, primaryBarrier);
            armClosingIfConfigured();
        }
        dismissPopup();
    }

    /**
     * Arms the closing triggers, but only for a barrier that asked for them. Opening from the
     * barrier list does not arm anything — that happens while configuring at a desk, not while
     * driving through a gate.
     */
    private void armClosingIfConfigured() {
        if (primaryBarrier != null && primaryBarrier.isClosable()) {
            new PendingCloseStore(this).markOpened(primaryBarrier.getId());
        }
    }

    private void dismissPopup() {
        // The closing question has now been put to the driver — answered or not, we don't nag.
        if (closeMode && primaryId != -1) {
            new PendingCloseStore(this).clear(primaryId);
        }
        if (countdown != null) {
            countdown.cancel();
            countdown = null;
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        if (countdown != null) {
            countdown.cancel();
            countdown = null;
        }
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
        if (popupLocationManager != null) {
            popupLocationManager.destroy();
            popupLocationManager = null;
        }
        cancelPopupNotification();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        dismissPopup();
    }
}
