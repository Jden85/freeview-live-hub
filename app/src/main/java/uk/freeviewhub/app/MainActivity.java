package uk.freeviewhub.app;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Color;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shows the TV-style channel list / guide / on-demand UI (assets/index.html) and, when a
 * channel or programme is chosen, hands off to the broadcaster's own installed app.
 *
 * Channel launching tries, in order:
 *  1. The channel's entry in Fire TV's live channel list (the same entries Fire TV's own
 *     Live tab uses), which carries the broadcaster app's "play this channel" link.
 *  2. The broadcaster's web link, sent to its app.
 *  3. Just opening the broadcaster's app.
 */
public class MainActivity extends Activity {

    private static final String READ_EPG = "com.android.providers.tv.permission.READ_EPG_DATA";
    private static final int REQ_EPG = 7;

    /** A broadcaster app: names we match against installed app labels, plus known package ids. */
    private static final class Provider {
        final String key, host, displayName; final String[] labels, packages, pkgHints;
        Provider(String key, String host, String displayName, String[] labels, String[] packages, String[] pkgHints) {
            this.key = key; this.host = host; this.displayName = displayName;
            this.labels = labels; this.packages = packages; this.pkgHints = pkgHints;
        }
    }

    private static final Provider[] PROVIDERS = {
        new Provider("bbc", "bbc.co.uk", "BBC iPlayer", new String[]{"iplayer"},
                new String[]{"bbc.iplayer.android", "uk.co.bbc.iplayer"}, new String[]{"bbc"}),
        new Provider("itv", "itv.com", "ITVX", new String[]{"itvx", "itv hub", "itv"},
                new String[]{"air.ITVMobilePlayer"}, new String[]{"itv"}),
        new Provider("c4", "channel4.com", "Channel 4", new String[]{"channel 4", "all 4", "all4"},
                new String[]{"com.channel4.ondemand"}, new String[]{"channel4", "c4", "all4"}),
        new Provider("c5", "channel5.com", "My5", new String[]{"my5", "my 5", "channel 5", "5"},
                new String[]{"com.channel5.my5", "com.channel5.my5.tv", "uk.co.channel5.my5"},
                new String[]{"channel5", "my5", "five"}),
        new Provider("u", "u.co.uk", "U", new String[]{"uktv", "u"},
                new String[]{"uk.co.uktv.dave"}, new String[]{"uktv"}),
    };

    /** One row from Fire TV's channel list. */
    private static final class TvChannel {
        long id; String pkg, name, number, appLink, deepLink, providerData; String norm;
    }

    private WebView web;
    private List<TvChannel> tvChannels;
    private String tvError;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0A0F1C"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new Bridge(), "HubAndroid");
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

        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(READ_EPG) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{READ_EPG}, REQ_EPG);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_EPG) {
            tvChannels = null; // re-read with the new permission
            web.evaluateJavascript("window.hubNativeReady && hubNativeReady()", null);
        }
    }

    // ------------------------------------------------------------------ Fire TV channel list

    private boolean hasEpgPermission() {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(READ_EPG) == PackageManager.PERMISSION_GRANTED;
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private synchronized List<TvChannel> channels() {
        if (tvChannels != null) return tvChannels;
        List<TvChannel> out = new ArrayList<>();
        tvError = null;
        try (Cursor c = getContentResolver().query(TvContract.Channels.CONTENT_URI, null, null, null, null)) {
            if (c == null) { tvError = "Fire TV channel list not available"; }
            else {
                while (c.moveToNext()) {
                    TvChannel ch = new TvChannel();
                    ch.id = getLong(c, "_id");
                    ch.pkg = getString(c, "package_name");
                    ch.name = getString(c, "display_name");
                    ch.number = getString(c, "display_number");
                    ch.appLink = getString(c, "app_link_intent_uri");
                    int i = c.getColumnIndex("internal_provider_data");
                    if (i >= 0 && !c.isNull(i)) {
                        try {
                            byte[] b = c.getBlob(i);
                            ch.providerData = new String(b, StandardCharsets.UTF_8);
                        } catch (Exception e) {
                            ch.providerData = c.getString(i);
                        }
                        ch.deepLink = findDeepLink(ch.providerData);
                    }
                    ch.norm = norm(ch.name);
                    out.add(ch);
                }
            }
        } catch (SecurityException e) {
            tvError = "Permission to read Fire TV channels was refused";
        } catch (Exception e) {
            tvError = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        tvChannels = out;
        return out;
    }

    /** Amazon's live-TV integration stores a playback deep link inside the provider data JSON. */
    private static String findDeepLink(String data) {
        if (data == null) return null;
        int start = data.indexOf('{');
        if (start < 0) return null;
        try {
            JSONObject o = new JSONObject(data.substring(start));
            for (String k : new String[]{"playbackDeepLinkUri", "playbackDeepLink", "deepLinkUri", "deeplink", "deepLink"}) {
                if (o.has(k)) return o.optString(k, null);
            }
        } catch (JSONException ignored) { }
        return null;
    }

    private static String getString(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return (i >= 0 && !c.isNull(i)) ? c.getString(i) : null;
    }

    private static long getLong(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return (i >= 0 && !c.isNull(i)) ? c.getLong(i) : -1;
    }

    private static Provider provider(String key) {
        for (Provider p : PROVIDERS) if (p.key.equals(key)) return p;
        return null;
    }

    private static final String[] REGION_WORDS = {"northwest", "granada", "england", "hd", "uk", "nw"};

    /** Finds the best Fire TV channel entry for one of our channels, or null. */
    private TvChannel match(JSONArray aliases, String providerKey) {
        Provider p = provider(providerKey);
        TvChannel best = null; int bestScore = 0;
        for (TvChannel ch : channels()) {
            for (int a = 0; a < aliases.length(); a++) {
                String alias = aliases.optString(a);
                boolean exact = alias.endsWith("$"); // "itv$" must not match "itvbe"
                if (exact) alias = alias.substring(0, alias.length() - 1);
                if (alias.isEmpty() || !ch.norm.startsWith(alias)) continue;
                String rest = ch.norm.substring(alias.length());
                if (rest.matches(".*[0-9].*") || rest.contains("extra") || rest.contains("plus")) continue;
                String stripped = rest;
                for (String w : REGION_WORDS) stripped = stripped.replace(w, "");
                if (exact && !stripped.isEmpty()) continue;
                int score = stripped.isEmpty() ? 100 : 50;
                if (rest.contains("northwest") || rest.contains("granada")) score += 15;
                if (rest.contains("hd")) score += 5;
                if (p != null && ch.pkg != null) {
                    String pkg = ch.pkg.toLowerCase(Locale.ROOT);
                    for (String h : p.pkgHints) if (pkg.contains(h)) { score += 20; break; }
                }
                if (ch.deepLink != null || ch.appLink != null) score += 3;
                if (score > bestScore) { bestScore = score; best = ch; }
            }
        }
        return best;
    }

    /** Tries one launch method for a Fire TV channel entry. method: deep, channel, app. */
    private boolean launchTvChannel(TvChannel ch, String method) {
        try {
            if ("deep".equals(method) && ch.deepLink != null) return startSafely(Intent.parseUri(ch.deepLink, Intent.URI_INTENT_SCHEME), ch.pkg);
            if ("app".equals(method) && ch.appLink != null) return startSafely(Intent.parseUri(ch.appLink, Intent.URI_INTENT_SCHEME), ch.pkg);
            if ("channel".equals(method) && ch.id >= 0) {
                return tryStart(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUri(ch.id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        } catch (Exception ignored) { }
        return false;
    }

    /** Starts an intent read from another app's channel data, keeping it pointed at that app. */
    private boolean startSafely(Intent intent, String pkg) {
        intent.setSelector(null);
        intent.setFlags(intent.getFlags() & ~(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION));
        if (pkg != null) {
            if (intent.getComponent() != null && !pkg.equals(intent.getComponent().getPackageName())) return false;
            if (intent.getComponent() == null && intent.getPackage() == null) intent.setPackage(pkg);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return tryStart(intent);
    }

    // ------------------------------------------------------------------ Web-link fallback

    /** Returns true when we handled the link ourselves (anything that isn't the bundled page). */
    private boolean handleUrl(Uri uri) {
        String scheme = uri.getScheme();
        if (scheme == null || scheme.equals("file") || scheme.equals("about")) return false;
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        Provider p = null;
        for (Provider candidate : PROVIDERS) {
            if (host.equals(candidate.host) || host.endsWith("." + candidate.host)) { p = candidate; break; }
        }
        if (p == null) { toast("This link can't be opened on the Firestick."); return true; }
        openWithProvider(p, uri);
        return true;
    }

    private void openWithProvider(Provider p, Uri uri) {
        String pkg = findInstalledPackage(p);
        if (pkg == null) {
            toast(p.displayName + " isn't installed. Get it from the Amazon Appstore.");
            return;
        }
        if (uri != null && tryStart(new Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return;
        if (openApp(pkg)) {
            toast("Opening " + p.displayName + ". Pick the channel from its Live section.");
        } else {
            toast("Couldn't open " + p.displayName + ".");
        }
    }

    private boolean openApp(String pkg) {
        PackageManager pm = getPackageManager();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
        return launch != null && tryStart(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private String findInstalledPackage(Provider p) {
        PackageManager pm = getPackageManager();
        for (String id : p.packages) {
            if (pm.getLaunchIntentForPackage(id) != null || pm.getLeanbackLaunchIntentForPackage(id) != null) return id;
        }
        // Match the app's package or name, since TV editions often use different package ids.
        for (ResolveInfo ri : launcherApps()) {
            String pkg = ri.activityInfo.packageName.toLowerCase(Locale.ROOT);
            String label = String.valueOf(ri.loadLabel(pm)).toLowerCase(Locale.ROOT).trim();
            for (String want : p.labels) {
                boolean match = want.length() <= 2 ? label.equals(want) : label.contains(want);
                if (match) return ri.activityInfo.packageName;
            }
            for (String h : p.pkgHints) {
                if (pkg.contains(h) && !pkg.startsWith("uk.freeviewhub")) return ri.activityInfo.packageName;
            }
        }
        return null;
    }

    private List<ResolveInfo> launcherApps() {
        PackageManager pm = getPackageManager();
        List<ResolveInfo> all = new ArrayList<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            all.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0));
        }
        return all;
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

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }

    // ------------------------------------------------------------------ JavaScript bridge

    private class Bridge {
        /**
         * Plays one of our channels. json: {aliases:[..], provider:"bbc", url:"https://..", method?:"deep|channel|app|web|open"}
         */
        @JavascriptInterface
        public String play(String json) {
            try {
                JSONObject o = new JSONObject(json);
                Provider p = provider(o.optString("provider"));
                String method = o.optString("method", "");
                Uri url = o.has("url") ? Uri.parse(o.getString("url")) : null;
                TvChannel ch = match(o.getJSONArray("aliases"), o.optString("provider"));

                if (!method.isEmpty()) { // forced method, used by the App check screen
                    boolean ok;
                    if (method.equals("web")) {
                        String pkg = p == null ? null : findInstalledPackage(p);
                        ok = pkg != null && url != null && tryStart(new Intent(Intent.ACTION_VIEW, url).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } else if (method.equals("open")) {
                        String pkg = p == null ? null : findInstalledPackage(p);
                        ok = pkg != null && openApp(pkg);
                    } else {
                        ok = ch != null && launchTvChannel(ch, method);
                    }
                    if (!ok) toast("That method didn't work for this channel.");
                    return ok ? "ok" : "failed";
                }

                // ITVX already plays from its web links, so use those first for ITV channels.
                if (p != null && p.key.equals("itv") && url != null) {
                    String pkg = findInstalledPackage(p);
                    if (pkg != null && tryStart(new Intent(Intent.ACTION_VIEW, url).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return "web";
                }
                if (ch != null) {
                    for (String m : new String[]{"deep", "channel", "app"}) if (launchTvChannel(ch, m)) return m;
                }
                if (p != null) runOnUiThread(() -> openWithProvider(p, url));
                return "fallback";
            } catch (Exception e) {
                toast("Couldn't play: " + e.getMessage());
                return "error";
            }
        }

        /** Real listings from Fire TV for our channels. json: [{n, aliases, provider}], returns {n:[{t,d,s,e}]} */
        @JavascriptInterface
        public String guide(String json, double fromMs, double toMs) {
            JSONObject out = new JSONObject();
            if (!hasEpgPermission()) return out.toString();
            try {
                JSONArray list = new JSONArray(json);
                for (int i = 0; i < list.length(); i++) {
                    JSONObject o = list.getJSONObject(i);
                    TvChannel ch = match(o.getJSONArray("aliases"), o.optString("provider"));
                    if (ch == null || ch.id < 0) continue;
                    JSONArray progs = new JSONArray();
                    Uri uri = TvContract.buildProgramsUriForChannel(ch.id, (long) fromMs, (long) toMs);
                    try (Cursor c = getContentResolver().query(uri, null, null, null, "start_time_utc_millis")) {
                        while (c != null && c.moveToNext()) {
                            JSONObject pr = new JSONObject();
                            pr.put("t", getString(c, "title"));
                            String ep = getString(c, "episode_title");
                            String d = getString(c, "short_description");
                            pr.put("d", d != null ? d : (ep != null ? ep : ""));
                            pr.put("s", getLong(c, "start_time_utc_millis"));
                            pr.put("e", getLong(c, "end_time_utc_millis"));
                            progs.put(pr);
                        }
                    } catch (Exception ignored) { }
                    if (progs.length() > 0) out.put(o.optString("n"), progs);
                }
            } catch (Exception ignored) { }
            return out.toString();
        }

        /** Everything the App check screen shows. */
        @JavascriptInterface
        public String diagnostics(String json) {
            JSONObject out = new JSONObject();
            try {
                PackageManager pm = getPackageManager();
                out.put("version", pm.getPackageInfo(getPackageName(), 0).versionName);
                out.put("device", Build.MANUFACTURER + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ")");
                out.put("epgPermission", hasEpgPermission());
                tvChannels = null;
                List<TvChannel> all = channels();
                out.put("tvChannelCount", all.size());
                if (tvError != null) out.put("tvError", tvError);

                JSONObject apps = new JSONObject();
                for (Provider p : PROVIDERS) {
                    String pkg = findInstalledPackage(p);
                    apps.put(p.displayName, pkg == null ? JSONObject.NULL : pkg);
                }
                out.put("apps", apps);

                JSONArray ours = new JSONArray(json), rows = new JSONArray();
                for (int i = 0; i < ours.length(); i++) {
                    JSONObject o = ours.getJSONObject(i);
                    TvChannel ch = match(o.getJSONArray("aliases"), o.optString("provider"));
                    JSONObject r = new JSONObject();
                    r.put("n", o.optString("n"));
                    if (ch != null) {
                        r.put("name", ch.name); r.put("pkg", ch.pkg);
                        r.put("deep", ch.deepLink != null); r.put("app", ch.appLink != null);
                    }
                    rows.put(r);
                }
                out.put("matches", rows);

                JSONArray sample = new JSONArray();
                for (TvChannel ch : all) {
                    if (sample.length() >= 60) break;
                    sample.put((ch.number == null ? "" : ch.number + " ") + ch.name + " · " + ch.pkg
                            + (ch.deepLink != null ? " · deep" : "") + (ch.appLink != null ? " · app" : ""));
                }
                out.put("tvChannels", sample);

                JSONArray launcher = new JSONArray();
                for (ResolveInfo ri : launcherApps()) {
                    String line = ri.loadLabel(pm) + " · " + ri.activityInfo.packageName;
                    boolean dup = false;
                    for (int i = 0; i < launcher.length(); i++) if (launcher.optString(i).equals(line)) { dup = true; break; }
                    if (!dup) launcher.put(line);
                }
                out.put("installedApps", launcher);
            } catch (Exception e) {
                try { out.put("error", e.toString()); } catch (JSONException ignored) { }
            }
            return out.toString();
        }

        @JavascriptInterface
        public void requestPermission() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 23) requestPermissions(new String[]{READ_EPG}, REQ_EPG);
            });
        }
    }

    // ------------------------------------------------------------------ Remote keys

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
