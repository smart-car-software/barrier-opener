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
import android.content.Intent;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.view.KeyEvent;

/**
 * Takes the wheel's media keys away from the music player for as long as a question is up, by
 * briefly being the active "playing" media session — Android routes media buttons to that one.
 * Nothing is actually played and no audio focus is requested, so the music keeps going; it just
 * stops skipping tracks when the driver presses the keys to answer. Released with the question.
 *
 * <p>The heavy alternative the Monjaro community tools use — patching the input service's code as
 * root and restarting it — is not something to do every time a gate comes into range.
 */
final class MediaKeyCatcher {
    private final Context context;
    private MediaSession session;

    MediaKeyCatcher(Context context) {
        this.context = context.getApplicationContext();
    }

    void start(WheelKeys.Listener listener) {
        stop();
        MediaSession s = new MediaSession(context, "barrier-opener-question");
        s.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        s.setCallback(new MediaSession.Callback() {
            @Override
            public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                KeyEvent key = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (key != null && key.getRepeatCount() == 0) {
                    listener.onWheelKey(key.getKeyCode(), key.getAction() == KeyEvent.ACTION_DOWN);
                }
                return true; // Swallowed: the player must not act on it.
            }
        });
        s.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(PlaybackState.STATE_PLAYING, 0, 0f)
                .build());
        s.setActive(true);
        session = s;
    }

    void stop() {
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
    }
}
