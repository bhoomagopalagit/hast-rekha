package com.bhoomagopala.hastrekha;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Hast Rekha Palm Reader: the web app bundled inside a full-screen WebView.
 * Adds the three things a plain WebView lacks: picking or taking a palm photo,
 * saving the PDF and share card to Downloads, and the back button.
 *
 * It also updates itself. Each time it opens it checks the latest GitHub release:
 * a newer palm reader (index.html) is downloaded and used straight away, and when
 * the Android code itself has changed it offers the new APK.
 */
public class MainActivity extends Activity {

    private static final int PICK_PHOTO = 1001;
    private static final String START_URL = "file:///android_asset/www/index.html";
    private static final String RELEASE = "https://github.com/bhoomagopalagit/hast-rekha/releases/latest/download/";
    private static final String APK_URL = RELEASE + "HastRekha.apk";

    private long openedAt;
    private volatile boolean touched;

    private WebView web;
    private ValueCallback<Uri[]> pendingPick;
    private Uri cameraUri;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        web.setBackgroundColor(0xFF13153A);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);

        web.addJavascriptInterface(new Saver(), "AndroidSave");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("file".equals(scheme) || "blob".equals(scheme) || "data".equals(scheme)) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (pendingPick != null) pendingPick.onReceiveValue(null);
                pendingPick = callback;
                return openPicker(params.isCaptureEnabled());
            }
        });

        web.setOnTouchListener((v, e) -> { touched = true; return false; });

        if (state != null) web.restoreState(state);
        else web.loadUrl(startUrl());
        openedAt = System.currentTimeMillis();
        new Thread(this::checkForUpdate, "update-check").start();
    }

    /* ---------------- self-update ---------------- */

    private SharedPreferences prefs() {
        return getSharedPreferences("update", MODE_PRIVATE);
    }

    private File updatedPage() {
        return new File(new File(getFilesDir(), "www"), "index.html");
    }

    /** The downloaded palm reader if it is newer than the one inside this APK, otherwise the bundled one. */
    private String startUrl() {
        File f = updatedPage();
        if (f.isFile() && prefs().getInt("web", 0) > BuildConfig.WEB_VERSION) return Uri.fromFile(f).toString();
        return START_URL;
    }

    private int currentWebVersion() {
        return Math.max(BuildConfig.WEB_VERSION, updatedPage().isFile() ? prefs().getInt("web", 0) : 0);
    }

    private static byte[] fetch(String address, int maxBytes) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Cache-Control", "no-cache");
        try {
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > maxBytes) throw new IllegalStateException("too big");
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    private void checkForUpdate() {
        try {
            JSONObject v = new JSONObject(new String(fetch(RELEASE + "version.json", 4096), StandardCharsets.UTF_8));
            int web = v.optInt("web", 0), shell = v.optInt("shell", 0);

            // A new APK is needed: remind at most once a day until it is installed.
            long now = System.currentTimeMillis();
            if (shell > BuildConfig.SHELL_VERSION && now - prefs().getLong("askedAt", 0) > 24L * 3600 * 1000) {
                prefs().edit().putLong("askedAt", now).apply();
                runOnUiThread(this::offerNewApk);
            }

            if (web > currentWebVersion()) {
                byte[] page = fetch(RELEASE + "index.html", 20 * 1024 * 1024);
                String text = new String(page, StandardCharsets.UTF_8);
                // Only use a complete palm reader page; anything else is ignored.
                if (page.length < 100_000 || !text.contains("Hast Rekha") || !text.trim().endsWith("</html>")) return;
                File dir = updatedPage().getParentFile();
                if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
                File tmp = new File(dir, "index.html.part");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(page);
                }
                if (!tmp.renameTo(updatedPage())) return;
                prefs().edit().putInt("web", web).apply();
                runOnUiThread(this::useNewPage);
            }
        } catch (Exception ignored) {
            // Offline or GitHub unreachable: keep using what is installed.
        }
    }

    /** Switch to the new version at once if the person has only just opened the app; otherwise on the next open. */
    private void useNewPage() {
        if (isFinishing()) return;
        if (!touched && System.currentTimeMillis() - openedAt < 8000) {
            web.loadUrl(startUrl());
            Toast.makeText(this, "Palm reader updated", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Update downloaded. It will be used next time you open the app.", Toast.LENGTH_LONG).show();
        }
    }

    private void offerNewApk() {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("New version of the app")
                .setMessage("A new version of Hast Rekha is ready. Download it and tap Install to update.")
                .setPositiveButton("Download", (d, w) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(APK_URL)));
                    } catch (Exception ignored) {
                    }
                })
                .setNegativeButton("Later", null)
                .show();
    }

    /** Gallery picker, with the camera offered too. The camera writes straight into the photo library. */
    private boolean openPicker(boolean cameraFirst) {
        Intent gallery = new Intent(Intent.ACTION_GET_CONTENT);
        gallery.addCategory(Intent.CATEGORY_OPENABLE);
        gallery.setType("image/*");

        Intent camera = null;
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, "palm-" + System.currentTimeMillis() + ".jpg");
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Hast Rekha");
            cameraUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (cameraUri != null) {
                camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                camera.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
                camera.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
        } catch (Exception e) {
            cameraUri = null;
        }

        Intent launch;
        if (cameraFirst && camera != null) {
            launch = camera;
        } else {
            launch = Intent.createChooser(gallery, "Choose your palm photo");
            if (camera != null) launch.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{camera});
        }
        try {
            startActivityForResult(launch, PICK_PHOTO);
            return true;
        } catch (Exception e) {
            dropCameraFile();
            if (pendingPick != null) pendingPick.onReceiveValue(null);
            pendingPick = null;
            return false;
        }
    }

    private void dropCameraFile() {
        if (cameraUri != null) {
            try {
                getContentResolver().delete(cameraUri, null, null);
            } catch (Exception ignored) {
            }
            cameraUri = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != PICK_PHOTO) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        Uri[] result = null;
        if (resultCode == RESULT_OK) {
            if (data != null && data.getData() != null) {
                result = new Uri[]{data.getData()};
                dropCameraFile();
            } else if (cameraUri != null) {
                result = new Uri[]{cameraUri};
                cameraUri = null;
            }
        } else {
            dropCameraFile();
        }
        if (pendingPick != null) pendingPick.onReceiveValue(result);
        pendingPick = null;
    }

    /** Called from the page as AndroidSave.save(base64, fileName, mimeType). Saves into Downloads. */
    private class Saver {
        @JavascriptInterface
        public void save(String base64, String fileName, String mimeType) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new IllegalStateException("no uri");
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new IllegalStateException("no stream");
                    out.write(bytes);
                }
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Saved to Downloads: " + fileName, Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Couldn't save the file. Try again.", Toast.LENGTH_LONG).show());
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
