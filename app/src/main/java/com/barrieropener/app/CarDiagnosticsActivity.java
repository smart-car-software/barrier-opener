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

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
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
 * What the wheel sends, for a driver with no adb in the car: the ECarX input library's key log,
 * read as root the same way the popup reads it ({@link RootStream}), plus a test of keeping the
 * media keys from the player. Everything this screen once probed that turned out not to work on a
 * Monjaro (vendor key listeners, broadcasts, cluster call card, other display routes) is gone;
 * {@code git log} has it if a new car needs it again.
 */
public class CarDiagnosticsActivity extends AppCompatActivity {
    private static final int LOG_LINES = 14;
    private static final int MAX_REPORT_LINES = 600;

    private final Deque<String> keyLog = new ArrayDeque<>();
    private final StringBuilder report = new StringBuilder();
    private TextView keyLogText;
    private TextView reportText;
    private RootStream rootStream;
    private MediaKeyCatcher mediaKeys;
    private int reportLines;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_car_diagnostics);
        Toolbar toolbar = findViewById(R.id.diagToolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        keyLogText = findViewById(R.id.diagKeyLog);
        reportText = findViewById(R.id.diagReport);
        keyLogText.setText(R.string.car_diag_no_keys);
        rootStream = new RootStream(this);
        mediaKeys = new MediaKeyCatcher(this);

        appendReport("device: " + Build.MANUFACTURER + " " + Build.MODEL + " / " + Build.PRODUCT
                + "\nandroid: " + Build.VERSION.RELEASE + " (sdk " + Build.VERSION.SDK_INT + ")"
                + "\nbuild: " + Build.DISPLAY
                + "\napp: " + BuildConfig.VERSION_NAME
                + "\noverlay permission: " + Settings.canDrawOverlays(this)
                + "\ncluster popup: " + AppSettings.clusterPopup(this)
                + "\nwheel keys: " + AppSettings.wheelKeys(this)
                + " (keep from music: " + AppSettings.muteMedia(this) + ")");

        LinearLayout actions = findViewById(R.id.diagActions);
        Button send = addAction(actions, getString(R.string.logs_send), null);
        send.setOnClickListener(v -> {
            send.setEnabled(false);
            Toast.makeText(this, R.string.logs_sending, Toast.LENGTH_SHORT).show();
            LogSender.send(this, report.toString(), message -> {
                send.setEnabled(true);
                appendReport(message);
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            });
        });
        addAction(actions, getString(R.string.car_diag_root_inputimpl), this::startKeyLog);
        addAction(actions, getString(R.string.car_diag_media_on), () -> {
            mediaKeys.start((code, pressed) -> log("kept from music", code, pressed));
            appendReport("media keys: held");
        });
        addAction(actions, getString(R.string.car_diag_media_off), () -> {
            mediaKeys.stop();
            appendReport("media keys: given back");
        });
        addAction(actions, getString(R.string.car_diag_copy), this::copyReport);
        addAction(actions, getString(R.string.car_diag_share), this::shareReport);
    }

    private Button addAction(LinearLayout parent, String label, Runnable action) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        if (action != null) {
            b.setOnClickListener(v -> action.run());
        }
        parent.addView(b);
        return b;
    }

    private void startKeyLog() {
        appendReport("--- wheel key log");
        rootStream.start("logcat -b main -v raw -T 1 InputImpl:V *:S", null, line -> {
            if (line == null) {
                appendReport("key log: ended");
                return;
            }
            int code = WheelNavigator.pressedKeyFromInputImplLog(line);
            if (code > 0) {
                log("wheel", code, true);
            } else if (!line.contains("onKeyReleased")) {
                appendReport(line);
            }
        });
    }

    private void log(String source, int code, boolean pressed) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date())
                + " " + source + " " + WheelKeys.describeKey(code)
                + " → " + (source.equals("wheel") ? WheelNavigator.actionFor(code)
                        : WheelNavigator.actionForMediaKey(code)) + (pressed ? "" : " (up)");
        keyLog.addFirst(line);
        while (keyLog.size() > LOG_LINES) {
            keyLog.removeLast();
        }
        keyLogText.setText(String.join("\n", keyLog));
        appendReport(line);
    }

    @Override
    protected void onDestroy() {
        rootStream.stop();
        mediaKeys.stop();
        super.onDestroy();
    }

    private void appendReport(String text) {
        if (reportLines++ >= MAX_REPORT_LINES) {
            return;
        }
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
