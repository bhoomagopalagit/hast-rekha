package com.bhoomagopala.hastrekha;

import android.app.Activity;
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

import java.io.OutputStream;

/**
 * Hast Rekha Palm Reader: the web app bundled inside a full-screen WebView.
 * Adds the three things a plain WebView lacks: picking or taking a palm photo,
 * saving the PDF and share card to Downloads, and the back button.
 */
public class MainActivity extends Activity {

    private static final int PICK_PHOTO = 1001;
    private static final String START_URL = "file:///android_asset/www/index.html";

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

        if (state != null) web.restoreState(state);
        else web.loadUrl(START_URL);
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
