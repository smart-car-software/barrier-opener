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
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.util.Log;
import android.view.KeyEvent;

/**
 * Keeps the wheel's media keys away from the music player while a question is up, so the key
 * that answers does not also skip the track. Nothing is patched and no root is needed.
 *
 * <p>On a Monjaro the key service hands ⏮ ⏭ ⏯ to Android's media-session dispatch, which gives
 * them to the "media button session" — and Android 9 picks that by who played audio last, not by
 * who claims to be playing. So a session alone is not enough (that was tried): this one also
 * plays silence, without asking for audio focus, so the player keeps playing undisturbed while the
 * keys come here and are dropped. Released with the question; the player, still playing, becomes
 * the media button session again.
 */
final class MediaKeyCatcher {
    private static final String TAG = "MediaKeyCatcher";
    private static final int RATE = 8000;

    private final Context context;
    private MediaSession session;
    private AudioTrack silence;

    MediaKeyCatcher(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * @param listener told about each key the player was kept from — on a Monjaro the only place
     *                 the wheel's presses arrive while this holds them; may be null
     * @return whether the keys are held (the silent track is playing)
     */
    boolean start(WheelKeys.Listener listener) {
        stop();
        MediaSession s = new MediaSession(context, "barrier-opener-question");
        s.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        s.setCallback(new MediaSession.Callback() {
            @Override
            public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                KeyEvent key = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (listener != null && key != null && key.getRepeatCount() == 0) {
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
        silence = playSilence();
        return silence != null;
    }

    /** A looping buffer of zeros as media audio: "playing", inaudible, no focus requested. */
    private static AudioTrack playSilence() {
        try {
            int frames = RATE / 2;
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(frames * 2)
                    .build();
            t.write(new short[frames], 0, frames);
            t.setLoopPoints(0, frames, -1);
            t.play();
            return t;
        } catch (RuntimeException e) {
            Log.w(TAG, "Silent track not started", e);
            return null;
        }
    }

    void stop() {
        if (silence != null) {
            try {
                silence.stop();
            } catch (IllegalStateException ignored) {
                // Never started.
            }
            silence.release();
            silence = null;
        }
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
    }
}
