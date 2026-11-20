package uk.freeviewhub.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/**
 * Shows the TV-style channel list / guide / on-demand UI (assets/index.html) and, when a
 * channel or programme is chosen, hands off to the broadcaster's own installed app.
 */
public class MainActivity extends Activity {

    /** A broadcaster app: names we match against installed app labels, plus known package ids. */
    private static final class Provider {
        final String host, displayName; final String[] labels, packages;
        Provider(String host, String displayName, String[] labels, String[] packages) {
            this.host = host; this.displayName = displayName; this.labels = labels; this.packages = packages;
        }
    }

    private static final Provider[] PROVIDERS = {
        new Provider("bbc.co.uk", "BBC iPlayer", new String[]{"iplayer"},
                new String[]{"bbc.iplayer.android", "uk.co.bbc.iplayer"}),
        new Provider("itv.com", "ITVX", new String[]{"itvx", "itv hub", "itv"},
                new String[]{"air.ITVMobilePlayer"}),
        new Provider("channel4.com", "Channel 4", new String[]{"channel 4", "all 4", "all4"},
                new String[]{"com.channel4.ondemand"}),
        new Provider("channel5.com", "My5", new String[]{"my5", "channel 5"},
                new String[]{"com.channel5.my5"}),
        new Provider("u.co.uk", "U", new String[]{"uktv", "u"},
                new String[]{"uk.co.uktv.dave"}),
    };

    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0A0F1C"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(Uri.parse(url));
            }
        });
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
        web.requestFocus(View.FOCUS_DOWN);
    }

    /** Returns true when we handled the link ourselves (anything that isn't the bundled page). */
    private boolean handleUrl(Uri uri) {
        String scheme = uri.getScheme();
        if (scheme == null || scheme.equals("file") || scheme.equals("about")) return false;
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        Provider p = null;
        for (Provider candidate : PROVIDERS) {
            if (host.equals(candidate.host) || host.endsWith("." + candidate.host)) { p = candidate; break; }
        }
        if (p == null) { openInBrowser(uri, null); return true; }

        String pkg = findInstalledPackage(p);
        if (pkg != null) {
            // 1. Ask the app to open this exact live channel / page.
            Intent deep = new Intent(Intent.ACTION_VIEW, uri).setPackage(pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (tryStart(deep)) return true;
            // 2. Otherwise just open the app.
            PackageManager pm = getPackageManager();
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
            if (launch != null && tryStart(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
                Toast.makeText(this, "Opening " + p.displayName + " – pick the channel from its Live section",
                        Toast.LENGTH_LONG).show();
                return true;
            }
        }
        openInBrowser(uri, p);
        return true;
    }

    private String findInstalledPackage(Provider p) {
        PackageManager pm = getPackageManager();
        for (String id : p.packages) {
            if (pm.getLaunchIntentForPackage(id) != null || pm.getLeanbackLaunchIntentForPackage(id) != null) return id;
        }
        // Fall back to matching the app's name, since TV editions sometimes use different package ids.
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
            List<ResolveInfo> apps = pm.queryIntentActivities(q, 0);
            for (ResolveInfo ri : apps) {
                String label = String.valueOf(ri.loadLabel(pm)).toLowerCase(Locale.ROOT).trim();
                for (String want : p.labels) {
                    boolean match = want.length() <= 2 ? label.equals(want) : label.contains(want);
                    if (match) return ri.activityInfo.packageName;
                }
            }
        }
        return null;
    }

    private void openInBrowser(Uri uri, Provider p) {
        Intent view = new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (tryStart(view)) return;
        String name = p != null ? p.displayName : "the right app";
        Toast.makeText(this, "Install " + name + " from the Amazon Appstore to watch this.", Toast.LENGTH_LONG).show();
    }

    private boolean tryStart(Intent intent) {
        try {
            if (intent.resolveActivity(getPackageManager()) == null) return false;
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // Let the page step back to Live TV first; exit only from there.
            web.evaluateJavascript("window.hubBack ? hubBack() : false", result -> {
                if (!"true".equals(result)) finish();
            });
            return true;
        }
        // Fire TV remote rewind / fast-forward act as channel down / up.
        if (keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || keyCode == KeyEvent.KEYCODE_CHANNEL_UP) {
            web.evaluateJavascript("dispatchEvent(new KeyboardEvent('keydown',{key:'PageDown'}))", null);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_REWIND || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
            web.evaluateJavascript("dispatchEvent(new KeyboardEvent('keydown',{key:'PageUp'}))", null);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.requestFocus();
    }
}
