package com.fixmylife.collection;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.os.SystemClock;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FML Collection: the collection side of fixmylifeco.com in one app.
 *
 *  - Opens on the digital collection (/archive). Bottom bar: Collection,
 *    Inventory (/items), Create (pack builder, maintenance codes, label
 *    stickers, box maker, ...) and NFC.
 *  - NFC reading is always on while the app is open: hold a tag to the phone
 *    and its link opens here. Tags tapped while the app is closed open it too.
 *  - NFC writing: "Write" puts a link on a tag — the current item's short
 *    /i/<id> link by default. The /pack page also calls FmlApp.writeTag(url)
 *    from its "Write to NFC tag" button.
 *  - Downloads from the label / box tools (blob: links) are saved to
 *    Downloads/FML Collection; photo pickers can use the camera.
 *  - Checks GitHub releases on start and offers to update itself (Updater).
 */
public class MainActivity extends Activity implements NfcAdapter.ReaderCallback {

    static final String SITE = BuildConfig.SITE;
    static final String HOME = SITE + "/archive";

    static final int BG = Color.BLACK;
    static final int BAR = Color.parseColor("#0B0B0B");
    static final int TEXT = Color.parseColor("#F5F5F2");
    static final int MUTED = Color.parseColor("#80FFFFFF");
    static final int ACCENT = Color.parseColor("#E8C878");
    static final int DIALOG_THEME = android.R.style.Theme_Material_Dialog_Alert;

    private static final int REQ_FILE = 41;

    /** Pages that stay inside the app; anything else on the site opens in the browser. */
    private static final String[] APP_PATHS = {
        "/archive", "/box", "/pack", "/i/", "/items", "/maintenance",
        "/collection", "/tools", "/supplies", "/saves", "/api/",
    };

    /** Download + print hooks injected into every page (idempotent). */
    private static final String HOOK_JS =
        "(function(){if(window.__fmlHook)return;window.__fmlHook=true;"
      + "window.__fmlSave=function(href,name){fetch(href).then(function(r){return r.blob();}).then(function(b){"
      + "return new Promise(function(res){var fr=new FileReader();fr.onload=function(){res([fr.result,b.type]);};fr.readAsDataURL(b);});"
      + "}).then(function(x){var d=String(x[0]);FmlApp.saveFile(d.substring(d.indexOf(',')+1),x[1]||'application/octet-stream',name||'download');})"
      + ".catch(function(e){FmlApp.toast('Download failed: '+e);});};"
      + "var isLocal=function(h){return h.indexOf('blob:')===0||h.indexOf('data:')===0;};"
      + "var orig=HTMLAnchorElement.prototype.click;"
      + "HTMLAnchorElement.prototype.click=function(){var h=this.href||'';"
      + "if(this.hasAttribute('download')&&isLocal(h)){window.__fmlSave(h,this.getAttribute('download'));return;}"
      + "return orig.apply(this,arguments);};"
      + "document.addEventListener('click',function(e){var a=e.target&&e.target.closest?e.target.closest('a[download]'):null;"
      + "if(!a)return;var h=a.href||'';if(isLocal(h)){e.preventDefault();window.__fmlSave(h,a.getAttribute('download'));}},true);"
      + "window.print=function(){FmlApp.print();};"
      + "})();";

    private WebView web;
    private ProgressBar progress;
    private final Map<String, TextView> tabs = new LinkedHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    private NfcAdapter nfc;
    private volatile String writeUrl;          // non-null = next tag gets this link
    private AlertDialog writeDialog;
    private String lastTagId = "";
    private long lastTagAt;

    private ValueCallback<Uri[]> fileCallback;
    private Uri cameraUri;

    private volatile String currentUrl = HOME;
    Updater updater;

    /* ------------------------------------------------------------ lifecycle */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        nfc = NfcAdapter.getDefaultAdapter(this);
        setContentView(buildUi());
        setupWeb();
        if (!openFromIntent(getIntent())) web.loadUrl(HOME);
        updater = new Updater(this);
        main.postDelayed(() -> updater.check(false), 2500);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openFromIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nfc != null && nfc.isEnabled()) {
            Bundle opts = new Bundle();
            opts.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250);
            nfc.enableReaderMode(this, this,
                NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B
                    | NfcAdapter.FLAG_READER_NFC_F | NfcAdapter.FLAG_READER_NFC_V, opts);
        }
        if (updater != null) updater.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (nfc != null) nfc.disableReaderMode(this);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    /* ------------------------------------------------------------ UI */

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(ACCENT));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(BG));
        root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        web = new WebView(this);
        web.setBackgroundColor(BG);
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        View line = new View(this);
        line.setBackgroundColor(Color.parseColor("#22FFFFFF"));
        root.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(BAR);
        bar.addView(tab("collection", "Collection", v -> web.loadUrl(HOME)));
        bar.addView(tab("inventory", "Inventory", v -> web.loadUrl(SITE + "/items")));
        bar.addView(tab("create", "Create", v -> showCreateMenu()));
        bar.addView(tab("nfc", "NFC", v -> showNfcMenu()));
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        return root;
    }

    private TextView tab(String key, String label, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(13);
        t.setTextColor(MUTED);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setOnClickListener(click);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        tabs.put(key, t);
        return t;
    }

    private void markTab(String url) {
        String path = Uri.parse(url == null ? "" : url).getPath();
        if (path == null) path = "";
        String active = "collection";
        if (path.startsWith("/items") || path.startsWith("/i/")) active = "inventory";
        else if (path.startsWith("/pack") || path.startsWith("/maintenance") || path.startsWith("/tools")
            || path.startsWith("/collection/labels") || path.startsWith("/collection/gameindex")
            || path.startsWith("/supplies") || path.startsWith("/saves")) active = "create";
        for (Map.Entry<String, TextView> e : tabs.entrySet()) {
            e.getValue().setTextColor(e.getKey().equals(active) ? ACCENT : MUTED);
        }
    }

    void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private void showCreateMenu() {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        addItem(labels, actions, "New tagged item — box, loose game or papers", "/pack");
        addItem(labels, actions, "Maintenance code", "/maintenance");
        addItem(labels, actions, "Cartridge label stickers", "/tools/cart-labels");
        addItem(labels, actions, "Label press", "/collection/labels");
        addItem(labels, actions, "Game box maker", "/tools/box-maker");
        addItem(labels, actions, "Collection organizer", "/collection");
        addItem(labels, actions, "Game index", "/collection/gameindex");
        addItem(labels, actions, "Saves", "/saves");
        addItem(labels, actions, "Protection supplies", "/supplies");
        labels.add("Reload page");
        actions.add(() -> web.reload());
        labels.add("Open this page in the browser");
        actions.add(() -> openExternal(Uri.parse(currentUrl)));
        labels.add("Check for updates (" + BuildConfig.VERSION_NAME + ")");
        actions.add(() -> updater.check(true));

        new AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle("Create")
            .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
            .show();
    }

    private void addItem(List<String> labels, List<Runnable> actions, String label, String path) {
        labels.add(label);
        actions.add(() -> web.loadUrl(SITE + path));
    }

    /* ------------------------------------------------------------ web */

    private static boolean isOurHost(Uri u) {
        String h = u.getHost();
        return h != null && (h.equals("www.fixmylifeco.com") || h.equals("fixmylifeco.com"));
    }

    private static boolean isAppPath(String path) {
        if (path == null) return false;
        for (String p : APP_PATHS) if (path.startsWith(p)) return true;
        return false;
    }

    private boolean trusted() {
        return isOurHost(Uri.parse(currentUrl));
    }

    void openExternal(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException e) {
            toast("No app can open " + u);
        }
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void setupWeb() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " FMLCollectionApp/" + BuildConfig.VERSION_NAME);
        CookieManager.getInstance().setAcceptCookie(true);
        web.addJavascriptInterface(new Bridge(), "FmlApp");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String scheme = u.getScheme() == null ? "" : u.getScheme();
                if (scheme.equals("blob") || scheme.equals("data")) return false;
                if (scheme.equals("https") && isOurHost(u) && isAppPath(u.getPath())) return false;
                openExternal(u);
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                currentUrl = url;
                progress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                currentUrl = url;
                view.evaluateJavascript(HOOK_JS, null);
                progress.setVisibility(View.GONE);
                markTab(url);
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                currentUrl = url;
                markTab(url);
                view.evaluateJavascript(HOOK_JS, null);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (!req.isForMainFrame()) return;
                String failed = req.getUrl().toString();
                String html = "<html><body style='background:#000;color:#F5F5F2;font-family:sans-serif;padding:32px'>"
                    + "<h2>Can't reach fixmylifeco.com</h2><p style='color:#aaa'>Check the connection and try again.</p>"
                    + "<p><a style='color:#E8C878' href='" + failed.replace("'", "%27") + "'>Try again</a></p></body></html>";
                view.loadDataWithBaseURL(SITE, html, "text/html", "utf-8", failed);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                progress.setProgress(p);
                progress.setVisibility(p < 100 ? View.VISIBLE : View.GONE);
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                return showFileChooser(callback, params);
            }
        });

        web.setDownloadListener((url, ua, contentDisposition, mime, length) -> {
            String name = URLUtil.guessFileName(url, contentDisposition, mime);
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                web.evaluateJavascript(HOOK_JS + "window.__fmlSave(" + JSONObject.quote(url) + "," + JSONObject.quote(name) + ");", null);
                return;
            }
            try {
                DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                r.setMimeType(mime);
                r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "FML Collection/" + name);
                ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
                toast("Downloading " + name);
            } catch (Exception e) {
                openExternal(Uri.parse(url));
            }
        });
    }

    /* ------------------------------------------------------------ files */

    private boolean showFileChooser(ValueCallback<Uri[]> callback, WebChromeClient.FileChooserParams params) {
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        fileCallback = callback;

        Intent pick;
        try {
            pick = params.createIntent();
        } catch (Exception e) {
            pick = new Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        }
        if (params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
            pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }

        boolean images = false;
        for (String a : params.getAcceptTypes()) if (a == null || a.isEmpty() || a.startsWith("image")) images = true;

        Intent chooser = Intent.createChooser(pick, "Add photo");
        cameraUri = null;
        if (images) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, "fml_" + System.currentTimeMillis() + ".jpg");
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FML Collection");
            cameraUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (cameraUri != null) {
                Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
                chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Parcelable[]{cam});
            }
        }
        try {
            startActivityForResult(chooser, REQ_FILE);
        } catch (ActivityNotFoundException e) {
            fileCallback = null;
            toast("No app to pick files with");
            return false;
        }
        return true;
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_FILE) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        Uri[] result = null;
        if (resultCode == RESULT_OK) {
            if (data != null && data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                result = new Uri[n];
                for (int i = 0; i < n; i++) result[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data != null && data.getData() != null) {
                result = new Uri[]{data.getData()};
            } else if (cameraUri != null) {
                result = new Uri[]{cameraUri};
                cameraUri = null; // keep the photo
            }
        }
        if (cameraUri != null) { // camera wasn't used: drop the empty placeholder
            try { getContentResolver().delete(cameraUri, null, null); } catch (Exception ignored) { }
            cameraUri = null;
        }
        if (fileCallback != null) fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    private void saveFile(String base64, String mime, String rawName) {
        String name = rawName.replaceAll("[\\\\/:*?\"<>|]", "_");
        try {
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, mime);
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FML Collection");
            final Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IllegalStateException("Couldn't create the file");
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("Couldn't open the file");
                out.write(bytes);
            }
            main.post(() -> new AlertDialog.Builder(this, DIALOG_THEME)
                .setTitle("Saved to Downloads")
                .setMessage("FML Collection/" + name)
                .setPositiveButton("Share", (d, w) -> {
                    Intent send = new Intent(Intent.ACTION_SEND).setType(mime)
                        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(send, "Share " + name));
                })
                .setNeutralButton("Open", (d, w) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                    } catch (ActivityNotFoundException e) {
                        toast("No app can open " + mime);
                    }
                })
                .setNegativeButton("OK", null)
                .show());
        } catch (Exception e) {
            main.post(() -> toast("Save failed: " + e.getMessage()));
        }
    }

    private void printPage() {
        PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
        pm.print("FML Collection", web.createPrintDocumentAdapter("FML Collection"), new PrintAttributes.Builder().build());
    }

    /** window.FmlApp in the page. Only pages on fixmylifeco.com can use it. */
    class Bridge {
        @JavascriptInterface public boolean isApp() { return true; }

        @JavascriptInterface public String version() { return BuildConfig.VERSION_NAME; }

        @JavascriptInterface public void writeTag(String url) {
            if (!trusted()) return;
            main.post(() -> startWrite(url));
        }

        @JavascriptInterface public void saveFile(String base64, String mime, String name) {
            if (!trusted()) return;
            new Thread(() -> MainActivity.this.saveFile(base64, mime, name)).start();
        }

        @JavascriptInterface public void toast(String msg) {
            main.post(() -> MainActivity.this.toast(msg));
        }

        @JavascriptInterface public void print() {
            if (!trusted()) return;
            main.post(MainActivity.this::printPage);
        }
    }

    /* ------------------------------------------------------------ NFC: open */

    /** A tag (or link) that launched the app. True if something was opened. */
    @SuppressWarnings("deprecation")
    private boolean openFromIntent(Intent intent) {
        if (intent == null) return false;
        if (Intent.ACTION_SEND.equals(intent.getAction())) return openSharedLink(intent.getStringExtra(Intent.EXTRA_TEXT));
        String url = null;
        if (NfcAdapter.ACTION_NDEF_DISCOVERED.equals(intent.getAction())) {
            Parcelable[] raw = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);
            if (raw != null && raw.length > 0) url = firstUri((NdefMessage) raw[0]);
        }
        if (url == null && intent.getData() != null) url = intent.getData().toString();
        if (url == null) return false;
        Uri u = Uri.parse(url);
        if (!isOurHost(u)) return false;
        web.loadUrl(url);
        return true;
    }

    /**
     * A link shared to the app (a Mercari / Yahoo Auctions listing, a Google Maps
     * place) becomes the listing link of the item being edited, or of a new one.
     */
    private boolean openSharedLink(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("https?://\\S+").matcher(text == null ? "" : text);
        if (!m.find()) {
            toast("No link in what was shared");
            return false;
        }
        String link = m.group();
        String item = "";
        String shortLink = shortLinkFor(currentUrl);
        int i = shortLink.indexOf("/i/");
        if (i >= 0) item = shortLink.substring(i + 3);
        String target = SITE + "/pack?" + (item.isEmpty() ? "" : "item=" + Uri.encode(item) + "&") + "listing=" + Uri.encode(link);
        web.loadUrl(target);
        toast(item.isEmpty() ? "Listing link added to a new item" : "Listing link added to " + item + " — tap Save changes");
        return true;
    }

    private static String firstUri(NdefMessage msg) {
        if (msg == null) return null;
        for (NdefRecord r : msg.getRecords()) {
            Uri u = r.toUri();
            if (u != null) return u.toString();
        }
        return null;
    }

    @Override
    public void onTagDiscovered(Tag tag) { // NFC thread
        String id = hex(tag.getId());
        long now = SystemClock.elapsedRealtime();
        String target = writeUrl;
        if (target != null) {
            final String[] result = writeTag(tag, target); // {error or null, previous link}
            lastTagId = id;
            lastTagAt = now;
            main.post(() -> afterWrite(target, result[0], result[1]));
            return;
        }
        if (id.equals(lastTagId) && now - lastTagAt < 3000) return; // same tag still held
        lastTagId = id;
        lastTagAt = now;
        final String url = readTag(tag);
        main.post(() -> openTagLink(url));
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        if (b != null) for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static String readTag(Tag tag) {
        Ndef ndef = Ndef.get(tag);
        if (ndef == null) return null;
        try {
            ndef.connect();
            NdefMessage msg = ndef.getNdefMessage();
            return firstUri(msg != null ? msg : ndef.getCachedNdefMessage());
        } catch (Exception e) {
            return firstUri(ndef.getCachedNdefMessage());
        } finally {
            try { ndef.close(); } catch (Exception ignored) { }
        }
    }

    private void openTagLink(String url) {
        if (url == null) {
            toast("This tag has no link yet. Open an item, then NFC → Write a link.");
            return;
        }
        Uri u = Uri.parse(url);
        if (isOurHost(u)) {
            web.loadUrl(url);
            return;
        }
        new AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle("Not a collection tag")
            .setMessage(url)
            .setPositiveButton("Open in browser", (d, w) -> openExternal(u))
            .setNegativeButton("Cancel", null)
            .show();
    }

    /* ------------------------------------------------------------ NFC: write */

    private void showNfcMenu() {
        if (nfc == null) {
            toast("This phone doesn't have NFC");
            return;
        }
        if (!nfc.isEnabled()) {
            new AlertDialog.Builder(this, DIALOG_THEME)
                .setTitle("NFC is off")
                .setMessage("Turn on NFC to read and write tags.")
                .setPositiveButton("Settings", (d, w) -> startActivity(new Intent(Settings.ACTION_NFC_SETTINGS)))
                .setNegativeButton("Cancel", null)
                .show();
            return;
        }
        String link = shortLinkFor(currentUrl);
        new AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle("NFC tags")
            .setMessage("Reading is always on: hold a tag to the back of the phone and its page opens."
                + (link.isEmpty() ? "\n\nTo write, open an item (or save one on Create → New tagged item) first, or type a link."
                                  : "\n\nThis page's link:\n" + link))
            .setPositiveButton("Write a link…", (d, w) -> promptWrite(link))
            .setNegativeButton("Close", null)
            .show();
    }

    /** The link a tag should hold for the page that's open: /i/<id> for saved items. */
    static String shortLinkFor(String url) {
        Uri u = Uri.parse(url == null ? "" : url);
        if (!isOurHost(u) || u.getPath() == null) return "";
        String path = u.getPath();
        if (path.startsWith("/i/") && path.length() > 3) return SITE + path;
        if (path.startsWith("/pack")) {
            String item = u.getQueryParameter("item");
            if (item != null && !item.isEmpty()) return SITE + "/i/" + item;
            if (path.startsWith("/pack/")) return url;
        }
        if (path.startsWith("/box/")) return url;
        return "";
    }

    private void promptWrite(String prefill) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(prefill.isEmpty() ? SITE + "/i/" : prefill);
        input.setSelection(input.getText().length());
        input.setTextColor(TEXT);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input);
        new AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle("Link to write")
            .setView(box)
            .setPositiveButton("Write", (d, w) -> startWrite(input.getText().toString().trim()))
            .setNegativeButton("Cancel", null)
            .show();
    }

    void startWrite(String url) {
        if (url == null || !url.startsWith("https://") || url.length() < 12) {
            toast("That doesn't look like a link: " + url);
            return;
        }
        if (nfc == null || !nfc.isEnabled()) {
            showNfcMenu();
            return;
        }
        writeUrl = url;
        if (writeDialog != null) writeDialog.dismiss();
        writeDialog = new AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle("Hold a tag to the phone")
            .setMessage("Writing:\n" + url + "\n\nKeep the tag against the back of the phone until it says done.")
            .setNegativeButton("Cancel", (d, w) -> writeUrl = null)
            .setOnCancelListener(d -> writeUrl = null)
            .show();
    }

    /** {error or null, previous link or null}. Runs on the NFC thread. */
    private static String[] writeTag(Tag tag, String url) {
        NdefMessage msg = new NdefMessage(new NdefRecord[]{NdefRecord.createUri(url)});
        Ndef ndef = Ndef.get(tag);
        if (ndef != null) {
            try {
                ndef.connect();
                if (!ndef.isWritable()) return new String[]{"This tag is locked (read-only).", null};
                if (ndef.getMaxSize() < msg.toByteArray().length) {
                    return new String[]{"This tag is too small for the link (" + ndef.getMaxSize() + " bytes).", null};
                }
                String previous = firstUri(ndef.getCachedNdefMessage());
                ndef.writeNdefMessage(msg);
                return new String[]{null, previous};
            } catch (Exception e) {
                return new String[]{"Write failed — hold the tag still and try again. (" + e.getClass().getSimpleName() + ")", null};
            } finally {
                try { ndef.close(); } catch (Exception ignored) { }
            }
        }
        NdefFormatable f = NdefFormatable.get(tag);
        if (f == null) return new String[]{"This kind of tag can't hold a link. NTAG213/215/216 tags work.", null};
        try {
            f.connect();
            f.format(msg);
            return new String[]{null, null};
        } catch (Exception e) {
            return new String[]{"Couldn't format this tag — try again. (" + e.getClass().getSimpleName() + ")", null};
        } finally {
            try { f.close(); } catch (Exception ignored) { }
        }
    }

    private void afterWrite(String url, String error, String previous) {
        if (writeDialog != null) {
            writeDialog.setOnCancelListener(null);
            writeDialog.dismiss();
            writeDialog = null;
        }
        if (error != null) {
            writeUrl = null;
            new AlertDialog.Builder(this, DIALOG_THEME)
                .setTitle("Not written")
                .setMessage(error)
                .setPositiveButton("Try again", (d, w) -> startWrite(url))
                .setNegativeButton("Cancel", null)
                .show();
            return;
        }
        writeUrl = null;
        String msg = url;
        if (previous != null && !previous.equals(url)) msg += "\n\nReplaced:\n" + previous;
        new AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle("Tag written ✓")
            .setMessage(msg)
            .setPositiveButton("Open it", (d, w) -> {
                if (isOurHost(Uri.parse(url))) web.loadUrl(url);
            })
            .setNegativeButton("Done", null)
            .show();
    }
}
