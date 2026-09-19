package com.primemakers.primemusic;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int RC_PERMISSIONS = 1001;
    private static final int RC_AUDIO_PICK = 1002;
    private static final int RC_WEB_FILE = 1003;
    private static final int RC_BACKUP_EXPORT = 1004;
    private static final int RC_BACKUP_IMPORT = 1005;
    private static final String UI_PREFS = "pm_ui";
    private static final String LIBRARY_PREFS = "pm_library";

    private WebView webView;
    private MusicService musicService;
    private boolean serviceBound = false;
    private boolean pageReady = false;
    private ValueCallback<Uri[]> fileChooserCallback;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private final Runnable stateTicker = new Runnable() {
        @Override public void run() {
            if (pageReady && serviceBound && musicService != null) {
                pushState();
            }
            uiHandler.postDelayed(this, 600L);
        }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            musicService = ((MusicService.LocalBinder) service).getService();
            serviceBound = true;
            musicService.reloadLibrary();
            pushLibrary();
            pushState();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            musicService = null;
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        FrameLayout root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int left = insets.getSystemWindowInsetLeft();
            int top = insets.getSystemWindowInsetTop();
            int right = insets.getSystemWindowInsetRight();
            int bottom = insets.getSystemWindowInsetBottom();
            v.setPadding(left, top, right, bottom);
            return insets;
        });
        root.requestApplyInsets();
        getWindow().setStatusBarColor(android.graphics.Color.rgb(5,3,12));
        getWindow().setNavigationBarColor(android.graphics.Color.rgb(5,3,12));
        webView = findViewById(R.id.webview);
        configureWebView();
        webView.loadUrl("file:///android_asset/index.html");
        uiHandler.post(stateTicker);
    }

    @Override protected void onStart() {
        super.onStart();
        bindService(new Intent(this, MusicService.class), serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override protected void onResume() {
        super.onResume();
        if (serviceBound && musicService != null) {
            musicService.reloadLibrary();
            pushLibrary();
            pushState();
        }
    }

    @Override protected void onStop() {
        if (serviceBound) {
            try { unbindService(serviceConnection); } catch (Exception ignored) {}
            serviceBound = false;
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.removeJavascriptInterface("PrimeBridge");
            webView.destroy();
        }
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (webView != null) {
            webView.evaluateJavascript("window.PM&&PM.handleAndroidBack?PM.handleAndroidBack():false", value -> {
                if (!"true".equals(value)) {
                    if (webView.canGoBack()) webView.goBack(); else MainActivity.super.onBackPressed();
                }
            });
        } else super.onBackPressed();
    }

    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        webView.setLongClickable(false);
        webView.setHapticFeedbackEnabled(false);
        webView.addJavascriptInterface(new PrimeBridge(), "PrimeBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                pageReady = true;
                pushLibrary();
                pushState();
                view.evaluateJavascript("window.PM&&PM.nativeReady&&PM.nativeReady()", null);
                ensureRuntimePermissions(false);
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                                       FileChooserParams params) {
                if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
                fileChooserCallback = callback;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                try { startActivityForResult(i, RC_WEB_FILE); return true; }
                catch (Exception e) { fileChooserCallback = null; return false; }
            }
        });
    }

    private void ensureRuntimePermissions(boolean force) {
        ArrayList<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.READ_MEDIA_AUDIO);
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if (!missing.isEmpty() && (force || !getPreferences(MODE_PRIVATE).getBoolean("asked_once", false))) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("asked_once", true).apply();
            requestPermissions(missing.toArray(new String[0]), RC_PERMISSIONS);
        } else if (!missing.isEmpty() && force) {
            requestPermissions(missing.toArray(new String[0]), RC_PERMISSIONS);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == RC_PERMISSIONS) {
            if (serviceBound && musicService != null) musicService.reloadLibrary();
            pushLibrary();
            pushState();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RC_WEB_FILE) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    ClipData clip = data.getClipData();
                    result = new Uri[clip.getItemCount()];
                    for (int i = 0; i < clip.getItemCount(); i++) result[i] = clip.getItemAt(i).getUri();
                } else if (data.getData() != null) result = new Uri[]{data.getData()};
            }
            if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(result);
            fileChooserCallback = null;
            return;
        }
        if (requestCode == RC_AUDIO_PICK && resultCode == RESULT_OK && data != null) {
            handlePickedAudio(data);
            return;
        }
        if (requestCode == RC_BACKUP_EXPORT && resultCode == RESULT_OK && data != null && data.getData() != null) {
            writeBackup(data.getData());
            return;
        }
        if (requestCode == RC_BACKUP_IMPORT && resultCode == RESULT_OK && data != null && data.getData() != null) {
            readBackup(data.getData());
        }
    }

    private void handlePickedAudio(Intent data) {
        Set<String> uris = new LinkedHashSet<>();
        SharedPreferences prefs = getSharedPreferences(LIBRARY_PREFS, MODE_PRIVATE);
        try {
            JSONArray old = new JSONArray(prefs.getString("picked_uris", "[]"));
            for (int i = 0; i < old.length(); i++) uris.add(old.optString(i));
        } catch (Exception ignored) {}

        ArrayList<Uri> selected = new ArrayList<>();
        if (data.getClipData() != null) {
            ClipData clip = data.getClipData();
            for (int i = 0; i < clip.getItemCount(); i++) selected.add(clip.getItemAt(i).getUri());
        } else if (data.getData() != null) selected.add(data.getData());

        for (Uri uri : selected) {
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Exception ignored) {}
            uris.add(uri.toString());
        }
        JSONArray arr = new JSONArray();
        for (String u : uris) if (u != null && !u.isBlank()) arr.put(u);
        prefs.edit().putString("picked_uris", arr.toString()).apply();
        if (serviceBound && musicService != null) musicService.reloadLibrary();
        pushLibrary();
        jsToast(selected.size() + " آهنگ به کتابخانه اضافه شد");
    }

    private void openAudioPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(i, RC_AUDIO_PICK); }
        catch (Exception e) { Toast.makeText(this, "انتخابگر فایل باز نشد", Toast.LENGTH_SHORT).show(); }
    }

    private void exportBackup() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, "PrimeMusic-backup.json");
        startActivityForResult(i, RC_BACKUP_EXPORT);
    }

    private void importBackup() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        startActivityForResult(i, RC_BACKUP_IMPORT);
    }

    private void writeBackup(Uri uri) {
        try {
            JSONObject root = new JSONObject();
            root.put("format", "PrimeMusicLocalBackup");
            root.put("version", 2);
            String userRaw = getSharedPreferences(UI_PREFS, MODE_PRIVATE).getString("user_data", defaultUserData());
            root.put("userData", new JSONObject(userRaw));
            if (serviceBound && musicService != null) root.put("stats", musicService.exportStats());
            root.put("pickedUris", new JSONArray(getSharedPreferences(LIBRARY_PREFS, MODE_PRIVATE)
                    .getString("picked_uris", "[]")));
            try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new IllegalStateException("No output stream");
                out.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            jsToast("بکاپ محلی ذخیره شد ✓");
        } catch (Exception e) {
            jsToast("خطا در ساخت بکاپ");
        }
    }

    private void readBackup(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("No input stream");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            JSONObject root = new JSONObject(out.toString(StandardCharsets.UTF_8));
            if (!"PrimeMusicLocalBackup".equals(root.optString("format"))) throw new IllegalArgumentException("Bad format");
            JSONObject user = root.optJSONObject("userData");
            if (user != null) getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit().putString("user_data", user.toString()).apply();
            JSONObject st = root.optJSONObject("stats");
            if (st != null && serviceBound && musicService != null) musicService.importStats(st);
            if (serviceBound && musicService != null) musicService.reloadLibrary();
            pushLibrary(); pushState();
            if (pageReady) webView.evaluateJavascript("window.PM&&PM.reloadUserData&&PM.reloadUserData()", null);
            jsToast("بکاپ بازیابی شد ✓");
        } catch (Exception e) {
            jsToast("فایل بکاپ معتبر نیست");
        }
    }

    private void pushLibrary() {
        if (!pageReady || !serviceBound || musicService == null) return;
        String json = musicService.getLibraryJson();
        runOnUiThread(() -> webView.evaluateJavascript("window.PM&&PM.setLibrary(" + json + ")", null));
    }

    private void pushState() {
        if (!pageReady || !serviceBound || musicService == null) return;
        String json = musicService.getStateJson();
        runOnUiThread(() -> webView.evaluateJavascript("window.PM&&PM.onNativeState(" + json + ")", null));
    }

    private void jsToast(String message) {
        if (!pageReady) return;
        String q = JSONObject.quote(message);
        runOnUiThread(() -> webView.evaluateJavascript("window.PM&&PM.toast(" + q + ")", null));
    }

    private void sendServiceAction(String action) {
        Intent i = new Intent(this, MusicService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= 26 && (MusicService.ACTION_TOGGLE.equals(action) ||
                MusicService.ACTION_NEXT.equals(action) || MusicService.ACTION_PREV.equals(action))) {
            try { startForegroundService(i); } catch (Exception e) { startService(i); }
        } else startService(i);
    }

    private void playTrack(String id) {
        Intent i = new Intent(this, MusicService.class).setAction(MusicService.ACTION_PLAY_ID).putExtra(MusicService.EXTRA_ID, id);
        if (Build.VERSION.SDK_INT >= 26) {
            try { startForegroundService(i); } catch (Exception e) { startService(i); }
        } else startService(i);
    }

    private String defaultUserData() {
        return "{\"name\":\"Prime Listener\",\"bio\":\"موسیقی، دنیای من است 🎵\",\"avatar\":\"\",\"theme\":\"galaxy\",\"frame\":\"galaxy\",\"autoTheme\":true,\"favorites\":[]}";
    }

    public final class PrimeBridge {
        @JavascriptInterface public String getLibrary() {
            return serviceBound && musicService != null ? musicService.getLibraryJson() : "[]";
        }
        @JavascriptInterface public String getState() {
            return serviceBound && musicService != null ? musicService.getStateJson() : "{}";
        }
        @JavascriptInterface public void play(String id) { runOnUiThread(() -> playTrack(id)); }
        @JavascriptInterface public void toggle() { runOnUiThread(() -> sendServiceAction(MusicService.ACTION_TOGGLE)); }
        @JavascriptInterface public void pause() { runOnUiThread(() -> sendServiceAction(MusicService.ACTION_PAUSE)); }
        @JavascriptInterface public void next() { runOnUiThread(() -> sendServiceAction(MusicService.ACTION_NEXT)); }
        @JavascriptInterface public void previous() { runOnUiThread(() -> sendServiceAction(MusicService.ACTION_PREV)); }
        @JavascriptInterface public void seek(long ms) {
            runOnUiThread(() -> {
                Intent i = new Intent(MainActivity.this, MusicService.class).setAction(MusicService.ACTION_SEEK)
                        .putExtra(MusicService.EXTRA_POSITION, ms);
                startService(i);
            });
        }
        @JavascriptInterface public void setShuffle(boolean enabled) {
            if (serviceBound && musicService != null) musicService.setShuffle(enabled);
        }
        @JavascriptInterface public void setRepeat(int mode) {
            if (serviceBound && musicService != null) musicService.setRepeatMode(mode);
        }
        @JavascriptInterface public void setEqBand(int index, int db) {
            if (serviceBound && musicService != null) musicService.setEqBand(index, db);
        }
        @JavascriptInterface public String getArtwork(String id) {
            return serviceBound && musicService != null ? musicService.getArtworkDataUri(id) : "";
        }
        @JavascriptInterface public void pickAudio() { runOnUiThread(MainActivity.this::openAudioPicker); }
        @JavascriptInterface public void requestAudioPermission() { runOnUiThread(() -> ensureRuntimePermissions(true)); }
        @JavascriptInterface public void rescan() {
            if (serviceBound && musicService != null) musicService.reloadLibrary();
            runOnUiThread(MainActivity.this::pushLibrary);
        }
        @JavascriptInterface public boolean hasAudioPermission() {
            if (Build.VERSION.SDK_INT >= 33)
                return checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED;
            return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
        @JavascriptInterface public String getUserData() {
            return getSharedPreferences(UI_PREFS, MODE_PRIVATE).getString("user_data", defaultUserData());
        }
        @JavascriptInterface public void saveUserData(String json) {
            try {
                JSONObject checked = new JSONObject(json);
                getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit().putString("user_data", checked.toString()).apply();
            } catch (Exception ignored) {}
        }
        @JavascriptInterface public void openRubika() {
            runOnUiThread(() -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://rubika.ir/pm_g_d_group"))); }
                catch (Exception e) { jsToast("مرورگر یا روبیکا پیدا نشد"); }
            });
        }
        @JavascriptInterface public void openAppSettings() {
            runOnUiThread(() -> {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null));
                startActivity(i);
            });
        }
        @JavascriptInterface public void exportBackup() { runOnUiThread(MainActivity.this::exportBackup); }
        @JavascriptInterface public void importBackup() { runOnUiThread(MainActivity.this::importBackup); }
        @JavascriptInterface public String appVersion() { return "2.1-local"; }
    }
}