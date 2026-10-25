package br.tvvibe.imdbbadge;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Watches which tile has focus in the Apple TV app (accessibility focus) or in Saimo TV (plain view
 * focus, a native app), looks its title up in the bundled IMDb file and shows a small rating badge
 * in the top-right corner. The badge goes away when focus lands on something that is not a title,
 * when another app comes to the front, or after HIDE_AFTER_MS without a new title (so it never sits
 * over a film that has started playing).
 */
public class BadgeService extends AccessibilityService {
    private static final String TAG = "ImdbBadge";
    private static final String APPLE_TV = "com.apple.atve.androidtv.appletv";
    private static final String SAIMO = "br.com.saimo.tv";
    /** Saimo's clock and tab bar fill the top right, so its badge sits lower. */
    private static final int APPLE_TV_Y_DP = 28;
    private static final int SAIMO_Y_DP = 84;
    private static final long HIDE_AFTER_MS = 10_000;
    private static final long WATCH_MS = 1_500;
    private static final long WAKE_MS = 5_000;
    private static final int REARM_TRIES = 4;
    private static final long REARM_GAP_MS = 8_000;
    private static final long REARM_WINDOW_MS = 10 * 60_000;
    private static final long SILENT_MS = 1_200;

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler worker;
    private volatile ImdbDb db;
    /** Only used on the worker thread. */
    private final Resolver resolver = new Resolver();

    private WindowManager wm;
    private View badge;
    private WindowManager.LayoutParams lp;
    private TextView ratingView;
    private TextView subtitleView;
    private boolean shown;
    private long lastFocusEvent;
    private boolean awake;

    private final Runnable autoHide = this::hide;
    /** The focus event can trail the announcement a little; only no focus event at all means silent. */
    private final Runnable checkSilent = () -> {
        long now = SystemClock.elapsedRealtime();
        if (now - lastFocusEvent > WAKE_MS) rearm(now);
    };
    private final Runnable watch = new Runnable() {
        @Override
        public void run() {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            String pkg = root == null ? null : String.valueOf(root.getPackageName());
            if (root != null) root.recycle();
            if (!APPLE_TV.equals(pkg) && !SAIMO.equals(pkg)) hide();
            else if (shown) main.postDelayed(this, WATCH_MS);
        }
    };

    @Override
    protected void onServiceConnected() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        badge = buildBadge();
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = dp(36);
        thread = new HandlerThread("imdb");
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.post(this::openDb);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Off by default; on with: adb shell setprop log.tag.ImdbBadge VERBOSE
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            AccessibilityNodeInfo s = event.getSource();
            Log.v(TAG, AccessibilityEvent.eventTypeToString(event.getEventType()) + " " + event.getPackageName()
                    + " source=" + (s == null ? "null" : s.getContentDescription())
                    + " text=" + event.getText() + " cd=" + event.getContentDescription());
            if (s != null) s.recycle();
        }
        String pkg = String.valueOf(event.getPackageName());
        if (SAIMO.equals(pkg)) {
            onSaimoEvent(event);
            return;
        }
        if (!APPLE_TV.equals(pkg)) return;
        long now = SystemClock.elapsedRealtime();
        if (event.getEventType() != AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) {
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) hide();
            // A key press in the app announces something; with no focus event around it, the app
            // started with accessibility already on and stays silent until it is switched off and on.
            if (event.getEventType() == AccessibilityEvent.TYPE_ANNOUNCEMENT && now - lastFocusEvent > WAKE_MS) {
                main.removeCallbacks(checkSilent);
                main.postDelayed(checkSilent, SILENT_MS);
            }
            return;
        }
        lastFocusEvent = now;
        if (!awake) {
            // Awake: the next silent start gets all its tries again.
            awake = true;
            getSharedPreferences("db", MODE_PRIVATE).edit().putInt("rearmTries", 0).apply();
        }
        AccessibilityNodeInfo src = event.getSource();
        if (src == null) return;
        CharSequence cd = src.getContentDescription();
        if (cd == null) cd = src.getText();
        src.recycle();
        String desc = cd == null ? null : cd.toString();
        worker.post(() -> lookup(desc));
    }

    /** Saimo's tiles are views whose children hold "Street Fighter (2026)"; read the texts under the focus. */
    private void onSaimoEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            hide();
            return;
        }
        if (event.getEventType() != AccessibilityEvent.TYPE_VIEW_FOCUSED) return;
        AccessibilityNodeInfo src = event.getSource();
        if (src == null) return;
        List<String> texts = new ArrayList<>(4);
        collectTexts(src, texts, 0);
        worker.post(() -> lookupSaimo(texts));
    }

    private static void collectTexts(AccessibilityNodeInfo node, List<String> out, int depth) {
        CharSequence t = node.getText();
        if (t != null && t.length() > 0) out.add(t.toString());
        for (int i = 0; i < node.getChildCount() && depth < 4 && out.size() < 8; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collectTexts(child, out, depth + 1);
        }
        node.recycle();
    }

    private void lookupSaimo(List<String> texts) {
        ImdbDb d = db;
        ImdbDb.Entry e = d == null ? null : Resolver.resolveTitleYear(d, texts);
        Log.d(TAG, "saimo " + texts + " -> " + (e == null ? "-" : e.title + " " + e.year + " " + e.rating));
        main.post(() -> {
            if (e == null) hide();
            else show(e, SAIMO_Y_DP);
        });
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        main.removeCallbacks(checkSilent);
        hide();
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    /**
     * Asks Rearm to switch accessibility off and on. One switch does not always wake the app (on the
     * TCL it took one to three), so up to REARM_TRIES, REARM_GAP_MS apart, then nothing for
     * REARM_WINDOW_MS. Kept in preferences: the switch itself rebinds this service as a new instance.
     * elapsedRealtime restarts at boot, so a stored time in the future is from before a reboot.
     */
    private void rearm(long now) {
        SharedPreferences prefs = getSharedPreferences("db", MODE_PRIVATE);
        long last = prefs.getLong("rearm", 0);
        long windowStart = prefs.getLong("rearmWindow", 0);
        int tries = prefs.getInt("rearmTries", 0);
        if (last > now || windowStart > now || now - windowStart > REARM_WINDOW_MS) {
            windowStart = now;
            tries = 0;
        }
        if (tries >= REARM_TRIES || (tries > 0 && now - last < REARM_GAP_MS)) return;
        prefs.edit().putLong("rearm", now).putLong("rearmWindow", windowStart).putInt("rearmTries", tries + 1).apply();
        Log.i(TAG, "Apple TV app is silent, switching accessibility off and on (" + (tries + 1) + ")");
        sendBroadcast(new android.content.Intent(this, Rearm.class));
    }

    private void lookup(String desc) {
        ImdbDb d = db;
        if (desc == null || d == null) {
            main.post(this::hide);
            return;
        }
        ImdbDb.Entry found = resolver.resolve(d, desc);
        Log.d(TAG, "\"" + desc + "\" -> " + (found == null ? "-" : found.title + " " + found.rating));
        ImdbDb.Entry e = found;
        main.post(() -> {
            if (e == null) hide();
            else show(e, APPLE_TV_Y_DP);
        });
    }

    /** Copies the bundled file to app storage once per version, so it can be memory-mapped. */
    private void openDb() {
        File file = new File(getFilesDir(), "imdb.tsv");
        SharedPreferences prefs = getSharedPreferences("db", MODE_PRIVATE);
        long version;
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            version = -1;
        }
        try {
            if (!file.exists() || prefs.getLong("version", -2) != version) {
                File tmp = new File(getFilesDir(), "imdb.tsv.tmp");
                try (InputStream in = getAssets().open("imdb.tsv"); OutputStream out = new FileOutputStream(tmp)) {
                    byte[] b = new byte[64 * 1024];
                    for (int r; (r = in.read(b)) > 0; ) out.write(b, 0, r);
                }
                if (!tmp.renameTo(file)) throw new IOException("rename failed");
                prefs.edit().putLong("version", version).apply();
            }
            db = new ImdbDb(file);
            Log.i(TAG, "database ready, " + file.length() + " bytes");
        } catch (IOException e) {
            Log.e(TAG, "cannot open the database", e);
        }
    }

    private void show(ImdbDb.Entry e, int yDp) {
        boolean pt = "pt".equals(java.util.Locale.getDefault().getLanguage());
        ratingView.setText(pt ? e.rating.replace('.', ',') : e.rating);
        String year = e.year.isEmpty() ? "" : " (" + e.year + ")";
        subtitleView.setText(e.title + year + " · " + votes(e.votes, pt));
        if (shown && lp.y != dp(yDp)) {
            lp.y = dp(yDp);
            wm.updateViewLayout(badge, lp);
        }
        if (!shown) {
            lp.y = dp(yDp);
            badge.setAlpha(0f);
            wm.addView(badge, lp);
            badge.animate().alpha(1f).setDuration(150).start();
            shown = true;
        }
        main.removeCallbacks(autoHide);
        main.postDelayed(autoHide, HIDE_AFTER_MS);
        main.removeCallbacks(watch);
        main.postDelayed(watch, WATCH_MS);
    }

    private void hide() {
        main.removeCallbacks(autoHide);
        main.removeCallbacks(watch);
        if (!shown) return;
        badge.animate().cancel();
        wm.removeView(badge);
        shown = false;
    }

    private View buildBadge() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6141414);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), 0x33FFFFFF);
        box.setBackground(bg);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView logo = new TextView(this);
        logo.setText("IMDb");
        logo.setTextColor(Color.BLACK);
        logo.setTypeface(Typeface.DEFAULT_BOLD);
        logo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        logo.setPadding(dp(6), dp(1), dp(6), dp(1));
        GradientDrawable yellow = new GradientDrawable();
        yellow.setColor(0xFFF5C518);
        yellow.setCornerRadius(dp(4));
        logo.setBackground(yellow);
        row.addView(logo);

        ratingView = new TextView(this);
        ratingView.setTextColor(Color.WHITE);
        ratingView.setTypeface(Typeface.DEFAULT_BOLD);
        ratingView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        ratingView.setPadding(dp(10), 0, 0, 0);
        row.addView(ratingView);

        TextView outOf = new TextView(this);
        outOf.setText("/10");
        outOf.setTextColor(0x99FFFFFF);
        outOf.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        outOf.setPadding(dp(2), dp(4), 0, 0);
        row.addView(outOf);
        box.addView(row);

        subtitleView = new TextView(this);
        subtitleView.setTextColor(0xB3FFFFFF);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subtitleView.setMaxLines(1);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        subtitleView.setMaxWidth(dp(280));
        subtitleView.setPadding(0, dp(4), 0, 0);
        box.addView(subtitleView);
        return box;
    }

    /** "412 mil votos" on a Portuguese TV, "412K votes" on any other. */
    static String votes(int v, boolean pt) {
        if (v < 1_000) return v + (pt ? " votos" : " votes");
        if (v < 1_000_000) return (v / 1_000) + (pt ? " mil votos" : "K votes");
        String m = String.format(java.util.Locale.ROOT, "%.1f", v / 1_000_000.0);
        return pt ? m.replace('.', ',') + " mi votos" : m + "M votes";
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
