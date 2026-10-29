package io.github.div3recovery.gst90;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Insets;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import android.os.CancellationSignal;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import java.io.OutputStream;

/**
 * 90+ Days GST Recovery – Gandhinagar Division.
 * Opens the web app (GitHub Pages) full-screen. Every update published on GitHub reaches
 * the app by itself; a new APK is needed only to change the icon, name or these settings.
 */
public class MainActivity extends Activity {

    private static final String START_URL = BuildConfig.START_URL;
    private static final String HOST = Uri.parse(START_URL).getHost();
    private static final int BRAND = 0xFF0B2470;
    private static final int PICK_FILE = 7;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BRAND);                 // colours the status-bar area royal blue
        web = new WebView(this);
        web.setBackgroundColor(0xFFF8F7F3);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // Android 15+ draws apps edge-to-edge: keep the page clear of status bar, nav bar and keyboard.
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((View v, WindowInsets insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return WindowInsets.CONSUMED;
            });
        }

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);                   // data saved on the phone, offline copy
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setTextZoom(100);                             // keep the layout stable with large system fonts
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setUserAgentString(s.getUserAgentString() + " GST90App/" + BuildConfig.VERSION_NAME);

        web.addJavascriptInterface(new Bridge(), "AndroidApp");
        web.setWebChromeClient(new Chrome());
        web.setWebViewClient(new Client());
        web.setDownloadListener((url, ua, cd, mime, len) -> {
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                web.evaluateJavascript("window.__appSaveUrl && window.__appSaveUrl(" + quote(url) + ",'download.' + (" + quote(mime == null ? "" : mime) + ".indexOf('csv')>=0?'csv':'xlsx'))", null);
            } else {
                open(Uri.parse(url));
            }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(START_URL);

        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
    }

    /* ---------- back button: one screen back inside the app; on the first screen, close ---------- */
    private void handleBack() {
        web.evaluateJavascript("(function(){try{var o=document.getElementById('dupOv');if(o){o.remove();return '1';}"
                + "if(typeof S!=='undefined'&&S.screen&&S.screen!=='sStart'&&typeof goBack==='function'){goBack();return '1';}}catch(e){}return '0';})()",
                value -> { if (!"\"1\"".equals(value)) finish(); });
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() { handleBack(); }   // Android 12 and older

    @Override
    protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); web.saveState(out); }

    @Override
    protected void onResume() { super.onResume(); web.onResume(); }

    @Override
    protected void onPause() { web.onPause(); super.onPause(); }

    @Override
    protected void onDestroy() {
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }

    /* ---------- choose an Excel file (Admin uploads) ---------- */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_FILE || fileCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                result = new Uri[data.getClipData().getItemCount()];
                for (int i = 0; i < result.length; i++) result[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    private class Chrome extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.ms-excel", "application/vnd.ms-excel.sheet.macroEnabled.12",
                    "application/octet-stream"});
            try {
                startActivityForResult(Intent.createChooser(i, "Choose Excel file"), PICK_FILE);
                return true;
            } catch (ActivityNotFoundException e) {
                fileCallback = null;
                toast(getString(R.string.no_app));
                return false;
            }
        }
    }

    private class Client extends WebViewClient {
        /** Pages of the app stay inside; phone numbers open the dialer; other links open outside (WhatsApp, browser…). */
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            String sc = u.getScheme() == null ? "" : u.getScheme();
            if (("https".equals(sc) || "http".equals(sc)) && HOST.equalsIgnoreCase(u.getHost())) return false;
            open(u);
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            view.evaluateJavascript(HELPER_JS, null);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
            if (req.isForMainFrame() && !online()) {
                view.loadDataWithBaseURL(null, OFFLINE_HTML, "text/html", "UTF-8", null);
            }
        }
    }

    /* ---------- what the web page can ask the phone to do ---------- */
    private class Bridge {
        /** Save a downloaded file (Excel / CSV) into Downloads and open it. */
        @JavascriptInterface
        public void saveFile(String name, String mime, String base64) {
            runOnUiThread(() -> saveToDownloads(name, mime, base64));
        }

        /** Print / Save as PDF (review sheets, dashboard). */
        @JavascriptInterface
        public void print(String title) {
            runOnUiThread(() -> printPage(title));
        }

        /** Share text (WhatsApp summary). */
        @JavascriptInterface
        public void share(String text) {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, text);
                try { startActivity(Intent.createChooser(i, "Share")); } catch (ActivityNotFoundException e) { toast(getString(R.string.no_app)); }
            });
        }
    }

    private void saveToDownloads(String name, String mime, String base64) {
        try {
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
            if (name == null || name.trim().isEmpty()) name = "download";
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/90+ Days GST Recovery");
            ContentResolver cr = getContentResolver();
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IllegalStateException("no uri");
            try (OutputStream os = cr.openOutputStream(uri)) { os.write(bytes); }
            toast(getString(R.string.saved) + " – " + name);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, v.getAsString(MediaStore.MediaColumns.MIME_TYPE));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try { startActivity(i); } catch (ActivityNotFoundException ignored) { /* no Excel viewer: file stays in Downloads */ }
        } catch (Exception e) {
            toast(getString(R.string.save_failed));
        }
    }

    private void printPage(String title) {
        PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
        String job = (title == null || title.trim().isEmpty()) ? getString(R.string.app_name) : title;
        final PrintDocumentAdapter inner = web.createPrintDocumentAdapter(job);
        PrintDocumentAdapter adapter = new PrintDocumentAdapter() {
            @Override public void onStart() { inner.onStart(); }
            @Override public void onLayout(PrintAttributes o, PrintAttributes n, CancellationSignal c, LayoutResultCallback cb, Bundle b) { inner.onLayout(o, n, c, cb, b); }
            @Override public void onWrite(PageRange[] p, ParcelFileDescriptor d, CancellationSignal c, WriteResultCallback cb) { inner.onWrite(p, d, c, cb); }
            @Override public void onFinish() {
                inner.onFinish();
                if (web != null) web.evaluateJavascript("window.dispatchEvent(new Event('afterprint'))", null);   // the page goes back to normal
            }
        };
        pm.print(job, adapter, new PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build());
    }

    private void open(Uri u) {
        Intent i = "tel".equals(u.getScheme()) ? new Intent(Intent.ACTION_DIAL, u) : new Intent(Intent.ACTION_VIEW, u);
        try { startActivity(i); } catch (ActivityNotFoundException e) { toast(getString(R.string.no_app)); }
    }

    private boolean online() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return true;
        NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void toast(String t) { Toast.makeText(this, t, Toast.LENGTH_SHORT).show(); }

    private static String quote(String s) { return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"; }

    /** Runs in the page: downloads (Excel/CSV), print and share go to the phone. */
    private static final String HELPER_JS =
            "(function(){if(window.__appReady)return;window.__appReady=true;" +
            "var st=document.createElement('style');st.textContent='#installBox{display:none!important}';document.head.appendChild(st);" +
            "function send(name,blob){var t=blob.type;if(!t||t==='application/octet-stream'){t=/\\.xlsx$/i.test(name)?'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet':/\\.csv$/i.test(name)?'text/csv':/\\.pdf$/i.test(name)?'application/pdf':'application/octet-stream';}" +
            "var r=new FileReader();r.onload=function(){var s=String(r.result);AndroidApp.saveFile(name,t,s.substring(s.indexOf(',')+1));};r.readAsDataURL(blob);}" +
            "window.__appSaveUrl=function(url,name){fetch(url).then(function(x){return x.blob();}).then(function(b){send(name,b);});};" +
            "var oc=HTMLAnchorElement.prototype.click;HTMLAnchorElement.prototype.click=function(){var h=this.href||'';" +
            "if(this.hasAttribute('download')&&(h.indexOf('blob:')===0||h.indexOf('data:')===0)){window.__appSaveUrl(h,this.getAttribute('download')||'download');return;}return oc.apply(this,arguments);};" +
            "window.print=function(){AndroidApp.print(document.title||'');};" +
            "navigator.share=function(d){AndroidApp.share((d&&(d.text||d.url))||'');return Promise.resolve();};" +
            "})();";

    private static final String OFFLINE_HTML =
            "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>" +
            "<body style='margin:0;font-family:sans-serif;background:#F8F7F3;display:flex;align-items:center;justify-content:center;height:100vh'>" +
            "<div style='text-align:center;padding:24px'><div style='font-size:46px'>&#128246;</div>" +
            "<h2 style='color:#0B2470;margin:10px 0 6px'>No internet</h2><p style='color:#555'>Connect to the internet and try again.</p>" +
            "<button onclick=\"location.href='" + START_URL + "'\" style='margin-top:12px;background:#0B2470;color:#fff;border:0;border-radius:12px;padding:12px 26px;font-size:16px'>Try again</button>" +
            "</div></body></html>";
}
