package app.cableclub;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;
import slt.Desktop;
import slt.client.ClientApp;

import java.util.ArrayList;
import java.util.List;

/**
 * The window of the app: Cable Club's interface (the same as on the PC, laid out for the phone) in a web view, on
 * the application's local address. The interface asks it, through "CableClubAndroid", for what only the phone
 * can do: pick ROM files, keep the screen on and hide the system bars during a game, vibrate under a finger.
 */
public final class MainActivity extends Activity {

    private static final int PICK_ROMS = 1;
    private static final int PICK_FILE = 2;
    /** The web engine the interface needs (Chrome 111, 2023: color-mix() in the styles). */
    private static final int MIN_WEBVIEW = 111;

    private WebView web;
    private FrameLayout root;
    private ValueCallback<Uri[]> fileCallback;
    private volatile boolean playing;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(0x16, 0x16, 0x16));
        setContentView(root);
        ClientApp app = CableClubApp.client();
        if (app == null) {
            TextView t = new TextView(this);
            Throwable f = CableClubApp.failure();
            t.setText("Cable Club n'a pas pu démarrer.\n\n" + (f == null ? "" : f.getMessage()));
            t.setTextColor(Color.WHITE);
            t.setPadding(48, 96, 48, 48);
            root.addView(t);
            return;
        }
        PackageInfo engine = WebView.getCurrentWebViewPackage();
        if (engine == null || major(engine.versionName) < MIN_WEBVIEW) {
            askWebViewUpdate(engine);
            return;
        }
        // a debug build lets the computer's developer tools inspect the interface (chrome://inspect)
        WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(0x16, 0x16, 0x16));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);
        web.addJavascriptInterface(new Bridge(), "CableClubAndroid");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("127.0.0.1".equals(u.getHost())) {
                    return false;
                }
                // pages of the web (GitHub, help…) open in the phone's browser
                Desktop.browse(u.toString());
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                // the profile picture
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), PICK_FILE);
                    return true;
                } catch (RuntimeException e) {
                    fileCallback = null;
                    return false;
                }
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        // the interface stays clear of the system bars and of the camera's notch
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);
        }
        web.loadUrl(app.url());
    }

    private static int major(String version) {
        try {
            return Integer.parseInt(version.substring(0, version.indexOf('.')));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * The phone's web engine is too old for the interface (a phone that never updated it: the Play Store does it on
     * its own): the way to update it instead of a page that never loads.
     */
    private void askWebViewUpdate(PackageInfo engine) {
        String pkg = engine == null ? "com.google.android.webview" : engine.packageName;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(64, 160, 64, 64);
        TextView t = new TextView(this);
        t.setText("Cable Club a besoin d'une version plus récente du moteur web d'Android (Android System WebView "
                + (engine == null ? "absent" : engine.versionName) + ", il faut la version " + MIN_WEBVIEW + " ou plus récente).\n\n"
                + "Mettez-le à jour depuis le Play Store, puis revenez ici.");
        t.setTextColor(Color.WHITE);
        t.setTextSize(16);
        box.addView(t);
        Button update = new Button(this);
        update.setText("Mettre à jour");
        update.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)));
            } catch (RuntimeException e) {
                Desktop.browse("https://play.google.com/store/apps/details?id=" + pkg);
            }
        });
        Button retry = new Button(this);
        retry.setText("Réessayer");
        retry.setOnClickListener(v -> recreate());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 48;
        box.addView(update, lp);
        box.addView(retry, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(box);
    }

    /** Android 11 and 12: the back button before the new callbacks. */
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        back();
    }

    /** Back: the interface first (a panel, a dialog, the previous page), then the app goes to the background. */
    private void back() {
        if (web == null) {
            finish();
            return;
        }
        web.evaluateJavascript("window.ccBack ? window.ccBack() : false", result -> {
            if ("true".equals(result)) {
                return;
            }
            if (web.canGoBack()) {
                web.goBack();
            } else {
                moveTaskToBack(true);
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) {
            // the game pauses while the app is not shown (Réglages > Jeu)
            web.evaluateJavascript("window.ccHidden && window.ccHidden(true)", null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            web.evaluateJavascript("window.ccHidden && window.ccHidden(false)", null);
        }
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) {
            ClientApp app = CableClubApp.client();
            if (app != null) {
                app.quit();
            }
        }
        if (web != null) {
            web.destroy();
        }
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_FILE) {
            ValueCallback<Uri[]> cb = fileCallback;
            fileCallback = null;
            if (cb != null) {
                cb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
            }
            return;
        }
        if (request == PICK_ROMS && result == RESULT_OK && data != null) {
            List<Uri> files = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    files.add(data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                files.add(data.getData());
            }
            ClientApp app = CableClubApp.client();
            if (app != null && !files.isEmpty()) {
                app.importRoms(getContentResolver(), files);
            }
        }
    }

    /** During a game: screen kept on, system bars hidden (a swipe from the edge shows them). */
    private void setPlaying(boolean on) {
        playing = on;
        Window w = getWindow();
        if (on) {
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        WindowInsetsController c = w.getInsetsController();
        if (c != null) {
            if (on) {
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(WindowInsets.Type.systemBars());
            } else {
                c.show(WindowInsets.Type.systemBars());
            }
        }
    }

    /** What the interface may ask of the phone (window.CableClubAndroid). */
    private final class Bridge {

        /** Opens Android's file picker for ROM files; they are then copied and added (event "rom-import"). */
        @JavascriptInterface
        public void pickRoms() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(i, PICK_ROMS);
                } catch (RuntimeException e) {
                    startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), PICK_ROMS);
                }
            });
        }

        @JavascriptInterface
        public void setPlaying(boolean on) {
            runOnUiThread(() -> MainActivity.this.setPlaying(on));
        }

        /** A short vibration under the finger (touch buttons). */
        @JavascriptInterface
        public void haptic() {
            runOnUiThread(() -> {
                if (web != null) {
                    web.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                }
            });
        }

        @JavascriptInterface
        public String version() {
            return slt.Version.VERSION;
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && playing) {
            setPlaying(true); // the bars hide again after a swipe
        }
    }
}
