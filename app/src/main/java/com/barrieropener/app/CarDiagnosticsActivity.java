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

import android.app.Presentation;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/**
 * Diagnostic build only. The driver has no adb in the car, and two things cannot be known from
 * here: which codes the steering wheel sends (and through which channel), and whether Android may
 * draw on the driver's screen at all. This screen logs every key from both channels — ordinary key
 * events and the ECarX interception, asked for every key the vendor SDK has a name for — and puts
 * a test question on each display Android can see, by each route the platform offers, plus the
 * incoming-call-card hack on the vendor cluster API. "Copy report" carries the result back.
 */
public class CarDiagnosticsActivity extends AppCompatActivity {
    private static final int LOG_LINES = 14;
    private static final long CLUSTER_TEST_MS = 30_000L;

    private final Deque<String> keyLog = new ArrayDeque<>();
    private final StringBuilder report = new StringBuilder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView keyLogText;
    private TextView reportText;
    private WheelKeys wheelKeys;
    private OneOsKeys oneOsKeys;
    private DimKeyBroadcasts dimKeys;
    private DimCallCard callCard;

    // The test card on the cluster, while one is up.
    private WindowManager overlayWm;
    private View overlayView;
    private View[] overlayAnswers;
    private int overlaySelected;
    private Presentation presentation;
    private final Runnable removeOverlay = this::removeOverlay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_car_diagnostics);
        Toolbar toolbar = findViewById(R.id.diagToolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        keyLogText = findViewById(R.id.diagKeyLog);
        reportText = findViewById(R.id.diagReport);
        keyLogText.setText(R.string.car_diag_no_keys);
        wheelKeys = new WheelKeys(this);
        oneOsKeys = new OneOsKeys(this);
        dimKeys = new DimKeyBroadcasts(this);
        oneOsKeys.setEventSink(line -> appendReport("oneos: " + line));
        callCard = new DimCallCard(this);

        appendReport("device: " + Build.MANUFACTURER + " " + Build.MODEL + " / " + Build.PRODUCT
                + "\nandroid: " + Build.VERSION.RELEASE + " (sdk " + Build.VERSION.SDK_INT + ")"
                + "\nbuild: " + Build.DISPLAY
                + "\napp: " + BuildConfig.VERSION_NAME
                + "\noverlay permission: " + Settings.canDrawOverlays(this)
                + "\npopup copy display: " + ClusterPrompt.chosenDisplay(this)
                + "\necarx input present: " + WheelKeys.isPresent()
                + "\necarx dim present: " + DimCallCard.isPresent()
                + "\n--- displays\n" + ClusterDisplay.describeAll(this));
        buildActions();
    }

    private void buildActions() {
        LinearLayout actions = findViewById(R.id.diagActions);
        if (ClusterDisplay.secondaryDisplays(this).isEmpty()) {
            TextView none = new TextView(this);
            none.setText(R.string.car_diag_no_displays);
            actions.addView(none);
        }
        for (Display d : ClusterDisplay.secondaryDisplays(this)) {
            int id = d.getDisplayId();
            addAction(actions, getString(R.string.car_diag_overlay, id), () -> showOverlay(id, false));
            addAction(actions, getString(R.string.car_diag_overlay_focus, id), () -> showOverlay(id, true));
            addAction(actions, getString(R.string.car_diag_use_display, id), () -> {
                ClusterPrompt.chooseDisplay(this, id);
                appendReport("popup copy display: #" + id);
            });
            addAction(actions, getString(R.string.car_diag_launch, id), () -> launchPopupOn(id));
            addAction(actions, getString(R.string.car_diag_presentation, id), () -> showPresentation(id));
        }
        addAction(actions, getString(R.string.car_diag_call_incoming),
                () -> showCallCard(DimCallCard.CALL_STATUS_IN_COMING_CALL));
        addAction(actions, getString(R.string.car_diag_call_ringing),
                () -> showCallCard(DimCallCard.CALL_STATUS_RINGING));
        addAction(actions, getString(R.string.car_diag_call_clear),
                () -> appendReport("call card clear:\n" + callCard.clear()));
        addAction(actions, getString(R.string.car_diag_copy), this::copyReport);
        addAction(actions, getString(R.string.car_diag_share), this::shareReport);
    }

    private void addAction(LinearLayout parent, String label, Runnable action) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setOnClickListener(v -> action.run());
        parent.addView(b);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Everything the vendor has a name for, volume and media included: the point here is to
        // learn which button is which, and this screen is only open while the driver is testing.
        boolean ok = wheelKeys.start(WheelKeys.allVendorKeyCodes(), this::onVendorKey);
        appendReport("--- wheel interception: " + (ok ? "on" : "off") + "\n" + wheelKeys.report());
        oneOsKeys.start(WheelKeys.allVendorKeyCodes(), this::onOneOsKey);
        dimKeys.start(this::onDimKey);
    }

    private void onDimKey(WheelNavigator.Action action, String description) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date())
                + " dim " + description + " -> " + action;
        keyLog.addFirst(line);
        while (keyLog.size() > LOG_LINES) {
            keyLog.removeLast();
        }
        keyLogText.setText(String.join("\n", keyLog));
        report.append("key ").append(line).append('\n');
        if (overlayView != null && action != WheelNavigator.Action.NONE) {
            if (action == WheelNavigator.Action.CONFIRM) {
                appendReport("cluster card: chosen " + overlaySelected);
                removeOverlay();
            } else {
                overlaySelected = WheelNavigator.move(overlaySelected, action,
                        new boolean[]{true, true, true});
                ClusterPrompt.paint(overlayAnswers, overlaySelected, null);
            }
        }
    }

    @Override
    protected void onPause() {
        wheelKeys.stop();
        oneOsKeys.stop();
        dimKeys.stop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        removeOverlay();
        callCard.release();
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            logKey("android", event.getKeyCode(), "down sc=" + event.getScanCode()
                    + " src=0x" + Integer.toHexString(event.getSource()));
            onWheel(event.getKeyCode());
        }
        // Back still leaves the screen; everything else is only being observed.
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            return super.dispatchKeyEvent(event);
        }
        return true;
    }

    private void onOneOsKey(int code, boolean pressed) {
        logKey("oneos", code, pressed ? "press" : "release");
        if (pressed) {
            onWheel(code);
        }
    }

    private void onVendorKey(int code, boolean pressed) {
        logKey("ecarx", code, pressed ? "press" : "release");
        if (pressed) {
            onWheel(code);
        }
    }

    private void logKey(String source, int code, String what) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date())
                + " " + source + " " + WheelKeys.describeKey(code) + " " + what;
        keyLog.addFirst(line);
        while (keyLog.size() > LOG_LINES) {
            keyLog.removeLast();
        }
        keyLogText.setText(String.join("\n", keyLog));
        report.append("key ").append(line).append('\n');
    }

    /** Drives the test card on the cluster, the way the real question would be driven. */
    private void onWheel(int code) {
        if (overlayView == null) {
            return;
        }
        WheelNavigator.Action action = WheelNavigator.actionFor(code);
        if (action == WheelNavigator.Action.CONFIRM) {
            appendReport("cluster card: chosen " + overlaySelected);
            removeOverlay();
            return;
        }
        overlaySelected = WheelNavigator.move(overlaySelected, action,
                new boolean[]{true, true, true});
        ClusterPrompt.paint(overlayAnswers, overlaySelected, null);
    }

    private Display findDisplay(int id) {
        for (Display d : ClusterDisplay.secondaryDisplays(this)) {
            if (d.getDisplayId() == id) {
                return d;
            }
        }
        return null;
    }

    private void showOverlay(int displayId, boolean focusable) {
        removeOverlay();
        Display display = findDisplay(displayId);
        if (display == null) {
            appendReport("overlay #" + displayId + ": display gone");
            return;
        }
        try {
            // The display context carries no theme; the card only needs the app's resources.
            LayoutInflater inflater = LayoutInflater.from(new ContextThemeWrapper(
                    createDisplayContext(display), R.style.Theme_BarrierOpener));
            View card = inflater.inflate(R.layout.view_cluster_prompt, null);
            ((TextView) card.findViewById(R.id.clusterTitle)).setText(
                    getString(R.string.popup_title_window, getString(R.string.car_diag_test_barrier)));
            overlayAnswers = new View[]{card.findViewById(R.id.clusterYes),
                    card.findViewById(R.id.clusterNo), card.findViewById(R.id.clusterWait)};
            overlaySelected = 0;
            ClusterPrompt.paint(overlayAnswers, overlaySelected, null);
            if (focusable) {
                // Keys that reach the cluster display's focused window land here.
                card.setFocusable(true);
                card.setFocusableInTouchMode(true);
                card.setOnKeyListener((v, keyCode, event) -> {
                    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                        logKey("cluster", keyCode, "down sc=" + event.getScanCode()
                                + " dev=" + event.getDeviceId());
                        onWheel(keyCode);
                    }
                    return keyCode != KeyEvent.KEYCODE_BACK;
                });
            }
            overlayWm = ClusterDisplay.showOverlay(this, display, card,
                    android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL,
                    ClusterPrompt.CARD_WIDTH_PX, ClusterPrompt.CARD_TOP_PX, focusable);
            if (focusable) {
                card.requestFocus();
            }
            overlayView = card;
            main.postDelayed(removeOverlay, CLUSTER_TEST_MS);
            appendReport("overlay #" + displayId + (focusable ? " (focus)" : "") + ": added");
        } catch (Throwable t) {
            appendReport("overlay #" + displayId + " failed: " + WheelKeys.rootCause(t));
        }
    }

    /**
     * The platform's own way to put content on a presentation display — what every display but
     * #0 is on this unit. Shown as an overlay window, so it does not need this activity in front.
     */
    private void showPresentation(int displayId) {
        removeOverlay();
        Display display = findDisplay(displayId);
        if (display == null) {
            appendReport("presentation #" + displayId + ": display gone");
            return;
        }
        try {
            Presentation p = new Presentation(this, display, R.style.Theme_BarrierOpener);
            p.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            View card = p.getLayoutInflater().inflate(R.layout.view_cluster_prompt, null);
            ((TextView) card.findViewById(R.id.clusterTitle)).setText(
                    getString(R.string.popup_title_window, getString(R.string.car_diag_test_barrier)));
            overlayAnswers = new View[]{card.findViewById(R.id.clusterYes),
                    card.findViewById(R.id.clusterNo), card.findViewById(R.id.clusterWait)};
            overlaySelected = 0;
            ClusterPrompt.paint(overlayAnswers, overlaySelected, null);
            p.setContentView(card);
            p.show();
            presentation = p;
            overlayView = card;
            main.postDelayed(removeOverlay, CLUSTER_TEST_MS);
            appendReport("presentation #" + displayId + ": shown");
        } catch (Throwable t) {
            appendReport("presentation #" + displayId + " failed: " + WheelKeys.rootCause(t));
        }
    }

    private void removeOverlay() {
        if (presentation != null) {
            try {
                presentation.dismiss();
            } catch (RuntimeException ignored) {
                // Its display went away first.
            }
            presentation = null;
        }
        main.removeCallbacks(removeOverlay);
        ClusterDisplay.removeOverlay(overlayWm, overlayView);
        overlayWm = null;
        overlayView = null;
        overlayAnswers = null;
    }

    private void launchPopupOn(int displayId) {
        Intent intent = new Intent(this, BarrierPopupActivity.class)
                .putExtra("barrier_name", getString(R.string.car_diag_test_barrier))
                .putExtra("barrier_id", -1L)
                .putExtra(BarrierPopupActivity.EXTRA_TEST, true);
        try {
            ClusterDisplay.launchOn(this, intent, displayId);
            appendReport("launch popup on #" + displayId + ": started");
        } catch (Throwable t) {
            appendReport("launch popup on #" + displayId + " failed: " + WheelKeys.rootCause(t));
        }
    }

    private void showCallCard(int status) {
        StringBuilder r = new StringBuilder("--- call card (status " + status + ")\n");
        r.append(callCard.connect(event -> appendReport("dim event: " + event)));
        r.append(callCard.show(getString(R.string.popup_title_window,
                getString(R.string.car_diag_test_barrier)), status));
        appendReport(r.toString());
    }

    private void appendReport(String text) {
        report.append(text);
        if (!text.endsWith("\n")) {
            report.append('\n');
        }
        reportText.setText(report);
    }

    private void copyReport() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("barrier-opener diagnostics", report));
            Toast.makeText(this, R.string.car_diag_copied, Toast.LENGTH_SHORT).show();
        }
    }

    /** Straight into Telegram or wherever, for units where the clipboard leads nowhere. */
    private void shareReport() {
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, report.toString());
        try {
            startActivity(Intent.createChooser(send, getString(R.string.car_diag_share)));
        } catch (RuntimeException e) {
            Toast.makeText(this, WheelKeys.rootCause(e), Toast.LENGTH_LONG).show();
        }
    }
}
