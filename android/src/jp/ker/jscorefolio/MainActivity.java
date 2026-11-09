package jp.ker.jscorefolio;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;

/**
 * Minimal WebView shell for the sheet-music viewer.
 *
 * Loads a self-contained HTML app from assets (file:///android_asset/www/index.html),
 * keeps the screen on, runs immersive fullscreen, and forwards Bluetooth foot-pedal
 * keys (which arrive as ordinary keyboard key events) into the page as synthetic
 * keydown events / a window.__pedalKey(key) hook.
 *
 * Also bridges the pieces a bare WebView does not provide on its own: a file
 * chooser for the page's PDF import, and download handling so the page's
 * "PDF書き出し" (a blob:/data: download) actually lands in Downloads.
 */
public class MainActivity extends Activity {

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_REQUEST_CODE = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep the screen on while scores are being played from.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        webView.setWebViewClient(new WebViewClient());
        // A WebView shows no file picker unless the shell provides one:
        // without this, the page's <input type="file"> (PDF import) is dead.
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                Intent intent = params.createIntent();
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE);
                } catch (Exception e) {
                    // Fallback: a plain open-document intent for PDFs.
                    try {
                        Intent fallback = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        fallback.addCategory(Intent.CATEGORY_OPENABLE);
                        fallback.setType("application/pdf");
                        startActivityForResult(fallback, FILE_CHOOSER_REQUEST_CODE);
                    } catch (Exception e2) {
                        filePathCallback = null;
                        callback.onReceiveValue(null);
                        return false;
                    }
                }
                return true;
            }
        });
        // Downloads: a bare WebView silently drops them. The app's PDF
        // export is generated in the page and offered as a blob:/data:
        // download, so route those through the DownloadBridge below;
        // ordinary http(s) downloads go to the system DownloadManager.
        webView.addJavascriptInterface(new DownloadBridge(), "GakufuDownload");
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent,
                                        String contentDisposition, String mimeType,
                                        long contentLength) {
                handleDownload(url, contentDisposition, mimeType);
            }
        });
        setContentView(webView);

        webView.loadUrl("file:///android_asset/www/index.html");
        hideSystemBars();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST_CODE || filePathCallback == null) {
            return;
        }
        Uri[] results = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                results = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) {
                    results[i] = clip.getItemAt(i).getUri();
                }
            } else if (data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
        }
        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
    }

    /**
     * Route a page download to the public Downloads folder.
     *
     * blob:/data: URLs cannot be fetched by DownloadManager (the bytes only
     * exist inside the page), so for those we ask the page itself to fetch
     * the blob and stream it back through the GakufuDownload bridge in
     * base64 chunks. http(s) URLs are enqueued with DownloadManager.
     */
    private void handleDownload(final String url, String contentDisposition,
                                final String mimeType) {
        final String fileName = sanitizeFileName(
                URLUtil.guessFileName(url, contentDisposition, mimeType), mimeType);
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            String js = "(async function(){"
                    + "try{"
                    + "  var resp = await fetch(" + JSONObject.quote(url) + ");"
                    + "  var blob = await resp.blob();"
                    + "  GakufuDownload.begin(" + JSONObject.quote(fileName) + ","
                    + JSONObject.quote(mimeType == null ? "" : mimeType) + ");"
                    + "  var CH = 262144;"
                    + "  for (var off = 0; off < blob.size; off += CH) {"
                    + "    var buf = await blob.slice(off, Math.min(off + CH, blob.size)).arrayBuffer();"
                    + "    var bytes = new Uint8Array(buf); var bin = '';"
                    + "    for (var i = 0; i < bytes.length; i += 32768) {"
                    + "      bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 32768));"
                    + "    }"
                    + "    GakufuDownload.chunk(btoa(bin));"
                    + "  }"
                    + "  GakufuDownload.finish();"
                    + "} catch (e) { GakufuDownload.failed(String(e)); }"
                    + "})();";
            webView.evaluateJavascript(js, null);
        } else {
            try {
                DownloadManager.Request req =
                        new DownloadManager.Request(Uri.parse(url));
                req.setTitle(fileName);
                if (mimeType != null) {
                    req.setMimeType(mimeType);
                }
                req.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                req.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS, fileName);
                DownloadManager dm =
                        (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                if (dm != null) {
                    dm.enqueue(req);
                }
            } catch (Exception e) {
                toast("保存に失敗しました");
            }
        }
    }

    /** Keep the suggested name (non-ASCII preserved), drop path separators. */
    private static String sanitizeFileName(String name, String mimeType) {
        if (name != null) {
            name = name.replaceAll("[/\\\\:*?\"<>|\\p{Cntrl}]", "_").trim();
        }
        if (name == null || name.isEmpty()) {
            name = "download";
        }
        if ("application/pdf".equals(mimeType)
                && !name.toLowerCase().endsWith(".pdf")) {
            int dot = name.lastIndexOf('.');
            name = (dot > 0 ? name.substring(0, dot) : name) + ".pdf";
        }
        return name;
    }

    /** Write bytes into the public Downloads collection (no permission needed on API 29+). */
    private void saveToDownloads(String name, String mime, byte[] bytes) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.MIME_TYPE, mime);
            values.put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS);
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            ContentResolver resolver = getContentResolver();
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IllegalStateException("MediaStore insert failed");
            }
            OutputStream out = resolver.openOutputStream(uri);
            if (out == null) {
                throw new IllegalStateException("MediaStore open failed");
            }
            out.write(bytes);
            out.close();
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            toast("ダウンロードに保存しました");
        } catch (Exception e) {
            toast("保存に失敗しました");
        }
    }

    private void toast(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    /** Receives page-generated download content (blob:/data:) in base64 chunks. */
    private class DownloadBridge {
        private ByteArrayOutputStream buffer;
        private String fileName;
        private String mimeType;

        @JavascriptInterface
        public synchronized void begin(String name, String mime) {
            fileName = name;
            mimeType = (mime == null || mime.isEmpty())
                    ? "application/octet-stream" : mime;
            buffer = new ByteArrayOutputStream();
        }

        @JavascriptInterface
        public synchronized void chunk(String base64) {
            if (buffer == null) {
                return;
            }
            try {
                buffer.write(Base64.decode(base64, Base64.DEFAULT));
            } catch (Exception ignored) {
                // A corrupt chunk surfaces as a short file; finish() still runs.
            }
        }

        @JavascriptInterface
        public synchronized void finish() {
            if (buffer == null) {
                failed("no data");
                return;
            }
            final byte[] bytes = buffer.toByteArray();
            final String name = fileName;
            final String mime = mimeType;
            buffer = null;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    saveToDownloads(name, mime, bytes);
                }
            });
            t.start();
        }

        @JavascriptInterface
        public void failed(String message) {
            toast("保存に失敗しました");
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
        }
    }

    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    /**
     * Foot-pedal keys: the pedal pairs as a Bluetooth keyboard and sends
     * PageUp/PageDown, arrow keys, Space or Enter depending on its mode.
     * We consume those keys (so the WebView does not also scroll or move
     * focus) and re-inject them into the page as DOM keydown events, so the
     * app sees them exactly as if they were real page keyboard input.
     * Volume and all other keys are left untouched.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        String key;
        String code;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_PAGE_UP:
                key = "PageUp"; code = "PageUp";
                break;
            case KeyEvent.KEYCODE_PAGE_DOWN:
                key = "PageDown"; code = "PageDown";
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                key = "ArrowLeft"; code = "ArrowLeft";
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                key = "ArrowRight"; code = "ArrowRight";
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                key = "ArrowUp"; code = "ArrowUp";
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                key = "ArrowDown"; code = "ArrowDown";
                break;
            case KeyEvent.KEYCODE_SPACE:
                key = " "; code = "Space";
                break;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                key = "Enter"; code = "Enter";
                break;
            default:
                return super.dispatchKeyEvent(event);
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            forwardKeyToPage(key, code);
        }
        return true;
    }

    private void forwardKeyToPage(final String key, final String code) {
        if (webView == null) {
            return;
        }
        // Forward through exactly ONE path. The page exposes __pedalKey as
        // its dedicated pedal entry and routes it to the same handler as real
        // keydown events; calling it AND dispatching a synthetic keydown made
        // every pedal press turn two pages. Prefer __pedalKey, and only fall
        // back to a synthetic keydown (on the focused element, bubbling up)
        // when the page does not define the hook.
        String js = "(function(){"
                + "var k=" + jsString(key) + ", c=" + jsString(code) + ";"
                + "try{"
                + "  if (typeof window.__pedalKey === 'function') { window.__pedalKey(k); return; }"
                + "  var t = document.activeElement || document.body || document;"
                + "  var ev = new KeyboardEvent('keydown', {key: k, code: c, bubbles: true, cancelable: true});"
                + "  t.dispatchEvent(ev);"
                + "}catch(e){}"
                + "})();";
        webView.evaluateJavascript(js, null);
    }

    private static String jsString(String s) {
        StringBuilder sb = new StringBuilder("'");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\'' || ch == '\\') {
                sb.append('\\');
            }
            sb.append(ch);
        }
        return sb.append('\'').toString();
    }
}
