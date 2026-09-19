package com.primemakers.primemusic;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.audiofx.Equalizer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class MusicService extends Service {
    public static final String ACTION_PLAY_ID = "pm.PLAY_ID";
    public static final String ACTION_TOGGLE = "pm.TOGGLE";
    public static final String ACTION_PAUSE = "pm.PAUSE";
    public static final String ACTION_NEXT = "pm.NEXT";
    public static final String ACTION_PREV = "pm.PREV";
    public static final String ACTION_SEEK = "pm.SEEK";
    public static final String ACTION_STOP = "pm.STOP";
    public static final String EXTRA_ID = "track_id";
    public static final String EXTRA_POSITION = "position";

    private static final String CHANNEL_ID = "prime_music_playback";
    private static final int NOTIFICATION_ID = 7401;
    private static final String PREF_STATS = "pm_stats";
    private static final String PREF_LIBRARY = "pm_library";
    private static final long XP_STEP_MS = 5000L;

    private final IBinder binder = new LocalBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<Track> queue = new ArrayList<>();
    private final Random random = new Random();
    private final LruCache<String, String> artworkCache = new LruCache<>(48);

    private MediaPlayer player;
    private Equalizer equalizer;
    private MediaSession mediaSession;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private SharedPreferences stats;
    private SharedPreferences libraryPrefs;
    private int currentIndex = -1;
    private boolean prepared = false;
    private boolean shuffle = false;
    private int repeatMode = 0; // 0 off, 1 all, 2 one
    private boolean resumeAfterFocusGain = false;
    private long lastListeningTick = 0L;
    private long unsavedListeningMs = 0L;
    private boolean noisyReceiverRegistered = false;

    public class LocalBinder extends Binder {
        public MusicService getService() { return MusicService.this; }
    }

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) pausePlayback();
        }
    };

    private final AudioManager.OnAudioFocusChangeListener focusChangeListener = focusChange -> {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            resumeAfterFocusGain = isPlaying();
            pausePlayback();
        } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
            resumeAfterFocusGain = false;
            pausePlayback();
        } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN && resumeAfterFocusGain) {
            resumeAfterFocusGain = false;
            resumePlayback();
        }
    };

    private final Runnable listeningTicker = new Runnable() {
        @Override public void run() {
            long now = android.os.SystemClock.elapsedRealtime();
            if (isPlaying()) {
                if (lastListeningTick == 0L) lastListeningTick = now;
                long delta = Math.max(0L, Math.min(2000L, now - lastListeningTick));
                unsavedListeningMs += delta;
                if (unsavedListeningMs >= 4000L) flushListeningStats();
            }
            lastListeningTick = now;
            handler.postDelayed(this, 1000L);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        stats = getSharedPreferences(PREF_STATS, MODE_PRIVATE);
        libraryPrefs = getSharedPreferences(PREF_LIBRARY, MODE_PRIVATE);
        shuffle = stats.getBoolean("shuffle", false);
        repeatMode = stats.getInt("repeat", 0);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createNotificationChannel();
        createMediaSession();
        registerNoisyReceiver();
        reloadLibrary();
        handler.post(listeningTicker);
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        String action = intent.getAction();
        if (ACTION_PLAY_ID.equals(action)) {
            ensureForegroundForStart();
            playById(intent.getStringExtra(EXTRA_ID));
        } else if (ACTION_TOGGLE.equals(action)) {
            if (isPlaying()) pausePlayback(); else { ensureForegroundForStart(); resumePlayback(); }
        } else if (ACTION_PAUSE.equals(action)) {
            pausePlayback();
        } else if (ACTION_NEXT.equals(action)) {
            ensureForegroundForStart(); next(true);
        } else if (ACTION_PREV.equals(action)) {
            ensureForegroundForStart(); previous();
        } else if (ACTION_SEEK.equals(action)) {
            seekTo(intent.getLongExtra(EXTRA_POSITION, 0L));
        } else if (ACTION_STOP.equals(action)) {
            stopPlaybackAndService();
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        flushListeningStats();
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        if (mediaSession != null) mediaSession.release();
        if (noisyReceiverRegistered) {
            try { unregisterReceiver(noisyReceiver); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    private void registerNoisyReceiver() {
        try {
            IntentFilter f = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisyReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(noisyReceiver, f);
            noisyReceiverRegistered = true;
        } catch (Exception ignored) {}
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(
                    CHANNEL_ID, "Prime Music Playback", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("کنترل پخش موسیقی Prime Music");
            c.setSound(null, null);
            c.enableVibration(false);
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    private void createMediaSession() {
        mediaSession = new MediaSession(this, "PrimeMusicSession");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resumePlayback(); }
            @Override public void onPause() { pausePlayback(); }
            @Override public void onSkipToNext() { next(true); }
            @Override public void onSkipToPrevious() { previous(); }
            @Override public void onSeekTo(long pos) { seekTo(pos); }
            @Override public void onStop() { stopPlaybackAndService(); }
        });
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setActive(true);
        updateMediaSessionState();
    }

    public synchronized void reloadLibrary() {
        ArrayList<Track> fresh = new ArrayList<>();
        if (hasAudioPermission()) {
            Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            String[] projection = {
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM_ID,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DISPLAY_NAME
            };
            try (Cursor c = getContentResolver().query(
                    uri, projection, null, null, MediaStore.Audio.Media.DATE_ADDED + " DESC")) {
                if (c != null) {
                    int idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                    int titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                    int artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                    int albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
                    int durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                    int displayCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);
                    while (c.moveToNext()) {
                        long id = c.getLong(idCol);
                        String title = c.getString(titleCol);
                        if (title == null || title.isBlank()) title = stripExtension(c.getString(displayCol));
                        String artist = c.getString(artistCol);
                        long albumId = c.getLong(albumCol);
                        long duration = c.getLong(durationCol);
                        Uri contentUri = ContentUris.withAppendedId(uri, id);
                        String art = albumId > 0 ? "content://media/external/audio/albumart/" + albumId : "";
                        fresh.add(new Track("media:" + id, title, artist,
                                contentUri.toString(), art, duration, false, false));
                    }
                }
            } catch (Exception ignored) {}
        }

        addPickedTracks(fresh);
        if (fresh.isEmpty()) fresh.addAll(DefaultAudio.ensure(this));

        String currentId = getCurrentTrack() == null ? null : getCurrentTrack().id;
        queue.clear();
        queue.addAll(fresh);
        if (currentId != null) {
            currentIndex = indexOfId(currentId);
        } else if (currentIndex >= queue.size()) currentIndex = -1;
    }

    private boolean hasAudioPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void addPickedTracks(List<Track> target) {
        String raw = libraryPrefs.getString("picked_uris", "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String u = arr.optString(i, "");
                if (u.isBlank()) continue;
                Uri uri = Uri.parse(u);
                String title = "Imported Track";
                String artist = "Unknown Artist";
                long duration = 0L;
                try {
                    MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                    mmr.setDataSource(this, uri);
                    String t = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                    String a = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                    String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    if (t != null && !t.isBlank()) title = t;
                    if (a != null && !a.isBlank()) artist = a;
                    if (d != null) duration = Long.parseLong(d);
                    mmr.release();
                } catch (Exception ignored) {
                    try (Cursor c = getContentResolver().query(uri,
                            new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
                        if (c != null && c.moveToFirst()) title = stripExtension(c.getString(0));
                    } catch (Exception ignored2) {}
                }
                target.add(new Track("picked:" + Integer.toHexString(u.hashCode()), title, artist,
                        u, "", duration, false, true));
            }
        } catch (Exception ignored) {}
    }

    private String stripExtension(String name) {
        if (name == null) return "Unknown Track";
        int p = name.lastIndexOf('.');
        return p > 0 ? name.substring(0, p) : name;
    }

    public synchronized String getLibraryJson() {
        JSONArray a = new JSONArray();
        for (Track t : queue) {
            try { a.put(t.toJson()); } catch (Exception ignored) {}
        }
        return a.toString();
    }

    public synchronized String getStateJson() {
        JSONObject o = new JSONObject();
        try {
            Track t = getCurrentTrack();
            o.put("currentId", t == null ? JSONObject.NULL : t.id);
            o.put("title", t == null ? "" : t.title);
            o.put("artist", t == null ? "" : t.artist);
            o.put("playing", isPlaying());
            o.put("prepared", prepared);
            o.put("position", prepared && player != null ? Math.max(0, player.getCurrentPosition()) : 0);
            o.put("duration", prepared && player != null ? Math.max(0, player.getDuration()) : (t == null ? 0 : t.durationMs));
            o.put("shuffle", shuffle);
            o.put("repeat", repeatMode);
            int level = stats.getInt("level", 1);
            int xp = stats.getInt("xp", 0);
            o.put("level", level);
            o.put("xp", xp);
            o.put("needXP", requiredXP(level));
            o.put("listenSeconds", stats.getLong("listen_ms", 0L) / 1000L);
            JSONArray eq = new JSONArray();
            for (int i = 0; i < 5; i++) eq.put(stats.getInt("eq" + i, 0));
            o.put("eq", eq);
        } catch (Exception ignored) {}
        return o.toString();
    }

    public synchronized String getArtworkDataUri(String id) {
        if (id == null) return "";
        String cached = artworkCache.get(id);
        if (cached != null) return cached;
        int idx = indexOfId(id);
        if (idx < 0) return "";
        Track t = queue.get(idx);
        Bitmap bitmap = loadArtwork(t);
        if (bitmap == null) return "";
        int max = 320;
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        if (w > max || h > max) {
            float scale = Math.min(max / (float) w, max / (float) h);
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(w * scale)),
                    Math.max(1, Math.round(h * scale)), true);
            if (scaled != bitmap) bitmap.recycle();
            bitmap = scaled;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 78, out);
        bitmap.recycle();
        String data = "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        artworkCache.put(id, data);
        return data;
    }

    private Bitmap loadArtwork(Track t) {
        if (t == null) return null;
        if (t.artUri != null && !t.artUri.isBlank()) {
            try (InputStream in = getContentResolver().openInputStream(Uri.parse(t.artUri))) {
                if (in != null) {
                    Bitmap b = BitmapFactory.decodeStream(in);
                    if (b != null) return b;
                }
            } catch (Exception ignored) {}
        }
        if (!t.isDefault) {
            try {
                MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                if (t.source.startsWith("content:")) mmr.setDataSource(this, Uri.parse(t.source));
                else mmr.setDataSource(t.source);
                byte[] bytes = mmr.getEmbeddedPicture();
                mmr.release();
                if (bytes != null) return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            } catch (Exception ignored) {}
        }
        return null;
    }

    public synchronized void playById(String id) {
        if (queue.isEmpty()) reloadLibrary();
        int idx = indexOfId(id);
        if (idx < 0) return;
        if (idx == currentIndex && prepared && player != null) {
            if (player.isPlaying()) pausePlayback(); else resumePlayback();
            return;
        }
        playIndex(idx);
    }

    private synchronized void playIndex(int idx) {
        if (idx < 0 || idx >= queue.size()) return;
        currentIndex = idx;
        prepared = false;
        releasePlayer();
        Track t = queue.get(idx);
        requestAudioFocus();
        player = new MediaPlayer();
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            player.setAudioAttributes(attrs);
            player.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            if (t.source.startsWith("content:") || t.source.startsWith("android.resource:")) {
                player.setDataSource(this, Uri.parse(t.source));
            } else if (t.source.startsWith("file:")) {
                player.setDataSource(Uri.parse(t.source).getPath());
            } else {
                player.setDataSource(t.source);
            }
            player.setOnPreparedListener(mp -> {
                prepared = true;
                setupEqualizer();
                try { mp.start(); } catch (Exception ignored) {}
                lastListeningTick = android.os.SystemClock.elapsedRealtime();
                updateMediaMetadata();
                updateMediaSessionState();
                updateNotification();
            });
            player.setOnCompletionListener(mp -> handleCompletion());
            player.setOnErrorListener((mp, what, extra) -> {
                prepared = false;
                updateMediaSessionState();
                updateNotification();
                return true;
            });
            updateMediaMetadata();
            updateMediaSessionState();
            updateNotification();
            player.prepareAsync();
        } catch (Exception e) {
            prepared = false;
            releasePlayer();
            updateNotification();
        }
    }

    public synchronized void resumePlayback() {
        if (prepared && player != null) {
            requestAudioFocus();
            try { player.start(); } catch (Exception ignored) {}
            lastListeningTick = android.os.SystemClock.elapsedRealtime();
            updateMediaSessionState();
            updateNotification();
        } else if (currentIndex >= 0) {
            playIndex(currentIndex);
        } else if (!queue.isEmpty()) {
            playIndex(0);
        }
    }

    public synchronized void pausePlayback() {
        flushListeningStats();
        if (prepared && player != null && player.isPlaying()) {
            try { player.pause(); } catch (Exception ignored) {}
        }
        updateMediaSessionState();
        updateNotification();
    }

    public synchronized void next(boolean userInitiated) {
        if (queue.isEmpty()) return;
        int next;
        if (shuffle && queue.size() > 1) {
            do { next = random.nextInt(queue.size()); } while (next == currentIndex);
        } else {
            next = currentIndex + 1;
            if (next >= queue.size()) {
                if (repeatMode == 1 || userInitiated) next = 0;
                else { pausePlayback(); seekTo(0); return; }
            }
        }
        playIndex(Math.max(0, next));
    }

    public synchronized void previous() {
        if (queue.isEmpty()) return;
        if (prepared && player != null && player.getCurrentPosition() > 5000) {
            seekTo(0); return;
        }
        int prev = currentIndex - 1;
        if (prev < 0) prev = queue.size() - 1;
        playIndex(prev);
    }

    private synchronized void handleCompletion() {
        flushListeningStats();
        if (repeatMode == 2) {
            seekTo(0); resumePlayback();
        } else {
            next(false);
        }
    }

    public synchronized void seekTo(long positionMs) {
        if (prepared && player != null) {
            try { player.seekTo((int) Math.max(0, Math.min(positionMs, player.getDuration()))); } catch (Exception ignored) {}
            updateMediaSessionState();
        }
    }

    public synchronized boolean isPlaying() {
        try { return prepared && player != null && player.isPlaying(); } catch (Exception e) { return false; }
    }

    public synchronized void setShuffle(boolean value) {
        shuffle = value;
        stats.edit().putBoolean("shuffle", value).apply();
        updateNotification();
    }

    public synchronized void setRepeatMode(int value) {
        repeatMode = Math.max(0, Math.min(2, value));
        stats.edit().putInt("repeat", repeatMode).apply();
    }

    public synchronized void setEqBand(int logicalBand, int db) {
        logicalBand = Math.max(0, Math.min(4, logicalBand));
        db = Math.max(-12, Math.min(12, db));
        stats.edit().putInt("eq" + logicalBand, db).apply();
        applyEqBand(logicalBand, db);
    }

    private void setupEqualizer() {
        releaseEqualizer();
        if (player == null) return;
        try {
            equalizer = new Equalizer(0, player.getAudioSessionId());
            equalizer.setEnabled(true);
            for (int i = 0; i < 5; i++) applyEqBand(i, stats.getInt("eq" + i, 0));
        } catch (Exception e) {
            releaseEqualizer();
        }
    }

    private void applyEqBand(int logicalBand, int db) {
        if (equalizer == null) return;
        try {
            short bands = equalizer.getNumberOfBands();
            if (bands <= 0) return;
            short actual = (short) Math.round(logicalBand * (bands - 1) / 4.0);
            short[] range = equalizer.getBandLevelRange();
            int desired = db * 100;
            short level = (short) Math.max(range[0], Math.min(range[1], desired));
            equalizer.setBandLevel(actual, level);
        } catch (Exception ignored) {}
    }

    private void releaseEqualizer() {
        if (equalizer != null) {
            try { equalizer.setEnabled(false); equalizer.release(); } catch (Exception ignored) {}
            equalizer = null;
        }
    }

    private void releasePlayer() {
        releaseEqualizer();
        if (player != null) {
            try { player.reset(); player.release(); } catch (Exception ignored) {}
            player = null;
        }
        prepared = false;
    }

    private int indexOfId(String id) {
        if (id == null) return -1;
        for (int i = 0; i < queue.size(); i++) if (id.equals(queue.get(i).id)) return i;
        return -1;
    }

    private Track getCurrentTrack() {
        return currentIndex >= 0 && currentIndex < queue.size() ? queue.get(currentIndex) : null;
    }

    private void requestAudioFocus() {
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
            if (Build.VERSION.SDK_INT >= 26) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(attrs)
                        .setOnAudioFocusChangeListener(focusChangeListener)
                        .setAcceptsDelayedFocusGain(true)
                        .build();
                audioManager.requestAudioFocus(focusRequest);
            }
        } catch (Exception ignored) {}
    }

    private void flushListeningStats() {
        if (unsavedListeningMs <= 0) return;
        long total = stats.getLong("listen_ms", 0L) + unsavedListeningMs;
        long xpRemainder = stats.getLong("xp_remainder_ms", 0L) + unsavedListeningMs;
        int xp = stats.getInt("xp", 0);
        int level = Math.max(1, stats.getInt("level", 1));
        long gained = xpRemainder / XP_STEP_MS;
        xpRemainder %= XP_STEP_MS;
        xp += (int) Math.min(Integer.MAX_VALUE / 4L, gained);
        while (xp >= requiredXP(level)) {
            xp -= requiredXP(level);
            level++;
            if (level >= Integer.MAX_VALUE - 10) break;
        }
        stats.edit()
                .putLong("listen_ms", total)
                .putLong("xp_remainder_ms", xpRemainder)
                .putInt("xp", xp)
                .putInt("level", level)
                .apply();
        unsavedListeningMs = 0L;
    }

    public static int requiredXP(int level) {
        long v = 90L + Math.max(0L, (long) level - 1L) * 35L;
        return (int) Math.min(Integer.MAX_VALUE, v);
    }

    public synchronized JSONObject exportStats() {
        flushListeningStats();
        JSONObject o = new JSONObject();
        try {
            o.put("listen_ms", stats.getLong("listen_ms", 0L));
            o.put("xp_remainder_ms", stats.getLong("xp_remainder_ms", 0L));
            o.put("xp", stats.getInt("xp", 0));
            o.put("level", stats.getInt("level", 1));
            o.put("shuffle", shuffle);
            o.put("repeat", repeatMode);
            JSONArray eq = new JSONArray();
            for (int i = 0; i < 5; i++) eq.put(stats.getInt("eq" + i, 0));
            o.put("eq", eq);
        } catch (Exception ignored) {}
        return o;
    }

    public synchronized void importStats(JSONObject o) {
        if (o == null) return;
        SharedPreferences.Editor e = stats.edit();
        e.putLong("listen_ms", Math.max(0L, o.optLong("listen_ms", 0L)));
        e.putLong("xp_remainder_ms", Math.max(0L, o.optLong("xp_remainder_ms", 0L)));
        e.putInt("xp", Math.max(0, o.optInt("xp", 0)));
        e.putInt("level", Math.max(1, o.optInt("level", 1)));
        shuffle = o.optBoolean("shuffle", false);
        repeatMode = Math.max(0, Math.min(2, o.optInt("repeat", 0)));
        e.putBoolean("shuffle", shuffle).putInt("repeat", repeatMode);
        JSONArray eq = o.optJSONArray("eq");
        if (eq != null) for (int i = 0; i < Math.min(5, eq.length()); i++) e.putInt("eq" + i, eq.optInt(i, 0));
        e.apply();
    }

    private void ensureForegroundForStart() {
        try { startForeground(NOTIFICATION_ID, buildNotification()); } catch (Exception ignored) {}
    }

    private void updateNotification() {
        try {
            Notification n = buildNotification();
            if (isPlaying()) startForeground(NOTIFICATION_ID, n);
            else getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, n);
        } catch (Exception ignored) {}
    }

    private Notification buildNotification() {
        Track t = getCurrentTrack();
        String title = t == null ? "Prime Music" : t.title;
        String artist = t == null ? "آماده پخش" : t.artist;
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 10, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Action prev = new Notification.Action.Builder(
                android.R.drawable.ic_media_previous, "قبلی", servicePending(ACTION_PREV, 11)).build();
        Notification.Action playPause = new Notification.Action.Builder(
                isPlaying() ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                isPlaying() ? "مکث" : "پخش", servicePending(ACTION_TOGGLE, 12)).build();
        Notification.Action next = new Notification.Action.Builder(
                android.R.drawable.ic_media_next, "بعدی", servicePending(ACTION_NEXT, 13)).build();

        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_music)
                .setContentTitle(title)
                .setContentText(artist)
                .setContentIntent(content)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setOngoing(isPlaying())
                .addAction(prev).addAction(playPause).addAction(next)
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        Bitmap art = loadArtwork(t);
        if (art != null) b.setLargeIcon(art);
        return b.build();
    }

    private PendingIntent servicePending(String action, int requestCode) {
        Intent i = new Intent(this, MusicService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void updateMediaMetadata() {
        Track t = getCurrentTrack();
        if (mediaSession == null || t == null) return;
        MediaMetadata.Builder b = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "Prime Music")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, t.durationMs);
        Bitmap art = loadArtwork(t);
        if (art != null) b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        mediaSession.setMetadata(b.build());
    }

    private void updateMediaSessionState() {
        if (mediaSession == null) return;
        int state = isPlaying() ? PlaybackState.STATE_PLAYING : (prepared ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_NONE);
        long position = 0L;
        if (prepared && player != null) {
            try { position = player.getCurrentPosition(); } catch (Exception ignored) {}
        }
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE |
                PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT |
                PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP;
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, position, isPlaying() ? 1f : 0f)
                .build());
    }

    private void stopPlaybackAndService() {
        flushListeningStats();
        releasePlayer();
        currentIndex = -1;
        updateMediaSessionState();
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignored) {}
        stopSelf();
    }
}