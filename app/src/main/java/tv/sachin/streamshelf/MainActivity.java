package tv.sachin.streamshelf;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.graphics.drawable.shapes.RectShape;
import android.graphics.drawable.shapes.RoundRectShape;
import android.graphics.Outline;
import android.os.*;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.util.LruCache;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import android.view.Gravity;
import android.text.InputType;
import android.text.format.DateFormat;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import androidx.core.content.ContextCompat;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

public class MainActivity extends Activity {
    private static final String TAG = "StreamShelf";
    private static final int BG=0xff101114, PANEL=0xff202126, RED=0xffe50914, WHITE=0xfff5f5f5, MUTED=0xffa7a7ad;
    private static final String DEFAULT_PLAYLIST_NAME="MyList";
    private static final String DEFAULT_PLAYLIST_URL="https://raw.githubusercontent.com/SachiSagar5/MyList-IPTV/main/MyList.m3u";
    private static final java.util.regex.Pattern GROUP_TITLE_ATTR=java.util.regex.Pattern.compile("(?:^|\\s)group-title=\"([^\"]*)\"",java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern TVG_LOGO_ATTR=java.util.regex.Pattern.compile("(?:^|\\s)tvg-logo=\"([^\"]*)\"",java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern SERIES_HINT=java.util.regex.Pattern.compile("\\b(series|tv series|season|episode|shows)\\b");
    private static final java.util.regex.Pattern MOVIE_HINT=java.util.regex.Pattern.compile("\\b(movie|movies|film|films|vod)\\b");
    private final ArrayList<Source> sources=new ArrayList<>();
    private final ArrayList<Entry> entries=new ArrayList<>();
    private final HashSet<String> favoriteKeys=new HashSet<>();
    /**
     * Snapshot of everything saved to My List, keyed by a stable identity.
     * Favourite URLs are not dependable: IPTV providers rotate the token in the
     * URL, and a title disappears from My List when the playlist no longer
     * carries the exact URL it was saved with. The snapshot also lets the list
     * render before (or without) the playlist finishing its download.
     */
    private final LinkedHashMap<String,Entry> favoriteEntries=new LinkedHashMap<>();
    private static final String FAVORITES_JSON="favorites_json";
    private LinearLayout root, content, nav, sidebar, sidebarBrand; private ScrollView mainScroll; private TextView status, sourceLabel, brandWordmark, brandProduct; private TextView searchControl; private String category="Home"; private int activeSource=0; private boolean sidebarExpanded;
    private final ArrayList<TextView> categoryTabs=new ArrayList<>(); private final ArrayList<HorizontalScrollView> shelfRows=new ArrayList<>(); private View firstContentItem;
    private final LruCache<String,Bitmap> thumbnailMemory=new LruCache<String,Bitmap>((int)(Runtime.getRuntime().maxMemory()/1024/8)){@Override protected int sizeOf(String key,Bitmap bitmap){return Math.max(1,bitmap.getByteCount()/1024);}};
    private final ThreadPoolExecutor thumbnailWorkers = new ThreadPoolExecutor(
        4, 8, 30, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(64),
        new ThreadPoolExecutor.DiscardOldestPolicy()
    );
    private ExoPlayer player; private PlayerView playerView; private FrameLayout playerOverlay; private View previousHomeFocus; private boolean playing=false;
    private boolean favoritesChangedWhilePlaying=false;
    private long pendingResumeMs = 0;
    // See All grid focus state. The grid is a full-screen replacement content
    // view, so it needs its own focus anchor: the home screen's firstContentItem
    // points at a view that is detached while the grid is open, and focusing a
    // detached view silently fails.
    private FrameLayout gridOverlayView; private View gridBackButton; private View firstGridItem; private View previousHomeFirstContent;
    private int gridFocusIndex;
    private Entry currentEntry; private LinearLayout playerLoadingPanel,playerErrorPanel; private TextView playerLoadingText,playerErrorText; private Button retryPlaybackButton, browseBtn;
    private final ExecutorService playlistWorkers=Executors.newFixedThreadPool(2);
    private final AtomicInteger playlistRequestId=new AtomicInteger();
    private PowerManager.WakeLock wakeLock;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean networkAvailable = true;

    // Live TV auto-recovery. A dead channel is remembered for this playback
    // session so the auto-skipper walks forward instead of bouncing between the
    // same broken URL forever. The set is cleared when playback starts or the
    // user retries.
    private final HashSet<String> failedLiveUrls = new HashSet<>();
    private boolean liveSkipScheduled = false;
    private Runnable liveSkipRunnable;

    // In-app splash overlay (no extra Activity, so the playlist request keeps
    // running while it is visible).
    private View splashView;
    private android.animation.ValueAnimator splashPulse;
    private long splashShownAt = 0L;
    private boolean splashDismissed = true;
    private boolean splashDismissPending = false;
    private static final long SPLASH_MIN_MS = 1000L;
    private static final long SPLASH_TIMEOUT_MS = 12000L;

    // Thumbnail scans are coalesced while scrolling instead of walking every
    // shelf on every scroll pixel.
    private Runnable thumbnailScanRunnable;
    private final Rect reusableRect = new Rect();
    private String playlistError;

    private static class Source { String name,url; Source(String n,String u){name=n;url=u;} }
    private static class Entry { String name,url,group,logo,type; Entry(String n,String u,String g,String l,String t){name=n;url=u;group=g;logo=l;type=t;} }

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        setupExceptionHandler();
        installRelaxedTls();
        getWindow().setFlags(1024,1024);
        applyImmersiveFullscreen();
        initWakeLock();
        initNetworkMonitoring();
        loadSources();
        loadFavorites();
        boolean seededDefault=sources.isEmpty();
        if(seededDefault){
            sources.add(new Source(DEFAULT_PLAYLIST_NAME,DEFAULT_PLAYLIST_URL));
            activeSource=0;
        }
        buildHome();
        if(seededDefault)saveSources();
        if(!sources.isEmpty())loadPlaylist();else showWelcome();
    }

    private void setupExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread thread, Throwable ex) {
                Log.e(TAG, "UNCAUGHT EXCEPTION", ex);
                saveCrashLog(ex);
                // Try to show error UI instead of crashing silently
                runOnUiThread(() -> {
                    try {
                        if (playing) {
                            showPlaybackError();
                            return;
                        }
                    } catch (Exception ignored) {}
                });
                // Give UI time to show error before killing process
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        });
    }

    private void saveCrashLog(Throwable ex) {
        try {
            File dir = new File(getCacheDir(), "crash_logs");
            if (!dir.exists()) dir.mkdirs();
            File logFile = new File(dir, "crash_" + System.currentTimeMillis() + ".txt");
            try (FileWriter fw = new FileWriter(logFile)) {
                fw.write("StreamShelf Crash Report\n");
                fw.write("Time: " + new Date() + "\n");
                fw.write("Version: 1.6.5\n");
                fw.write("Android: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")\n");
                fw.write("Device: " + Build.MANUFACTURER + " " + Build.MODEL + "\n");
                fw.write("Playing: " + playing + "\n");
                if (currentEntry != null) {
                    fw.write("Entry: " + currentEntry.name + "\n");
                    fw.write("URL: " + currentEntry.url + "\n");
                }
                fw.write("\nStack Trace:\n");
                ex.printStackTrace(new PrintWriter(fw));
            }
        } catch (Exception ignored) {}
    }

    // Many IPTV providers serve playlists over HTTPS with expired, self-signed
    // or incomplete certificate chains. Validate with the platform trust store
    // first, but fall back to accepting the chain so the playlist still loads.
    private static boolean relaxedTlsInstalled = false;

    private void installRelaxedTls() {
        if (relaxedTlsInstalled) return;
        try {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init((java.security.KeyStore) null);
            X509TrustManager platformTrust = null;
            for (TrustManager tm : factory.getTrustManagers()) {
                if (tm instanceof X509TrustManager) { platformTrust = (X509TrustManager) tm; break; }
            }
            final X509TrustManager systemTrust = platformTrust;
            X509TrustManager relaxed = new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    if (systemTrust != null) systemTrust.checkClientTrusted(chain, authType);
                }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    if (systemTrust == null) return;
                    try {
                        systemTrust.checkServerTrusted(chain, authType);
                    } catch (CertificateException e) {
                        Log.w(TAG, "Accepting untrusted server certificate: " + e.getMessage());
                    }
                }
                @Override public X509Certificate[] getAcceptedIssuers() {
                    return systemTrust == null ? new X509Certificate[0] : systemTrust.getAcceptedIssuers();
                }
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{relaxed}, null);
            SSLContext.setDefault(context);
            HttpsURLConnection.setDefaultSSLSocketFactory(context.getSocketFactory());

            final HostnameVerifier originalVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
            HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
                @Override public boolean verify(String hostname, javax.net.ssl.SSLSession session) {
                    if (originalVerifier != null && originalVerifier != this && originalVerifier.verify(hostname, session)) return true;
                    Log.w(TAG, "Accepting certificate hostname mismatch for " + hostname);
                    return true;
                }
            });
            relaxedTlsInstalled = true;
        } catch (Exception e) {
            Log.e(TAG, "Could not install relaxed TLS policy", e);
        }
    }

    private void initWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, "StreamShelf::WakeLock");
        wakeLock.setReferenceCounted(false);
    }

    private void initNetworkMonitoring() {
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                networkAvailable = true;
                runOnUiThread(() -> {
                    if (playing && player != null && player.getPlaybackState() == Player.STATE_IDLE) {
                        // Network restored, try to resume if we were buffering
                        Log.d(TAG, "Network restored, player state: " + player.getPlaybackState());
                    }
                });
            }
            @Override public void onLost(Network network) {
                networkAvailable = false;
                runOnUiThread(() -> {
                    if (playing && playerLoadingPanel != null) {
                        playerLoadingText.setText("Network lost. Waiting for connection…");
                        playerLoadingPanel.setVisibility(View.VISIBLE);
                    }
                });
            }
        };
        NetworkRequest request = new NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build();
        connectivityManager.registerNetworkCallback(request, networkCallback);
    }

    private void applyImmersiveFullscreen(){getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);}
    @Override public void onWindowFocusChanged(boolean hasFocus){super.onWindowFocusChanged(hasFocus);if(hasFocus)applyImmersiveFullscreen();}
    private int dp(float x){return (int)(x*getResources().getDisplayMetrics().density+.5f);}
    private GradientDrawable rounded(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);return t;}

    // ... rest of the methods remain the same until play() method
    private void buildHome(){
        // Performance optimizations
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        );
        
        categoryTabs.clear();
        root=new LinearLayout(this);root.setOrientation(LinearLayout.HORIZONTAL);root.setBackgroundColor(0xff05090f);root.setFocusableInTouchMode(true);root.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);setContentView(root);
        sidebar=new LinearLayout(this);sidebar.setOrientation(LinearLayout.VERTICAL);sidebar.setPadding(dp(8),dp(8),dp(8),dp(8));sidebar.setBackgroundColor(0xff080c12);sidebar.setClipChildren(false);sidebar.setClipToPadding(false);root.addView(sidebar,new LinearLayout.LayoutParams(dp(64),-1));
        sidebarBrand=new LinearLayout(this);sidebarBrand.setGravity(Gravity.CENTER_VERTICAL);sidebarBrand.setPadding(dp(4),0,0,0);sidebar.addView(sidebarBrand,new LinearLayout.LayoutParams(-1,dp(68)));TextView mark=text("▶",20,RED);sidebarBrand.addView(mark);brandWordmark=text(" IPTV ",17,WHITE);brandWordmark.setTypeface(null,Typeface.BOLD);brandWordmark.setSingleLine(true);sidebarBrand.addView(brandWordmark);brandProduct=text("Player",17,RED);brandProduct.setTypeface(null,Typeface.BOLD);brandProduct.setSingleLine(true);sidebarBrand.addView(brandProduct);
        nav=new LinearLayout(this);nav.setOrientation(LinearLayout.VERTICAL);nav.setClipChildren(false);sidebar.addView(nav,new LinearLayout.LayoutParams(-1,0,1));String[][] tabs={{"Home","⌂"},{"Movies","▣"},{"Series","▤"},{"Live TV","▣"},{"My List","♡"},{"Settings","⚙"}};for(String[] item:tabs){String tab=item[0];String icon=item[1];TextView v=text(icon,21,WHITE);v.setTag(tab);v.setContentDescription(tab);v.setSingleLine(true);v.setEllipsize(android.text.TextUtils.TruncateAt.END);v.setFocusable(true);v.setClickable(true);v.setGravity(Gravity.CENTER);v.setId(View.generateViewId());v.setPadding(dp(5),0,dp(5),0);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(47));p.setMargins(0,0,0,dp(5));nav.addView(v,p);setFocusStyle(v,Color.TRANSPARENT,7);v.setOnClickListener(w->{if(tab.equals("Settings")){sourceDialog();return;}category=tab;updateCategoryTabs();if(mainScroll!=null)mainScroll.scrollTo(0,0);refreshRows();focusFirstContent();});categoryTabs.add(v);}updateCategoryTabs();
        sourceLabel=text("SOURCE  ·  "+(sources.isEmpty()?"Add playlist":sources.get(Math.min(activeSource,sources.size()-1)).name),12,MUTED);sourceLabel.setSingleLine(true);sourceLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);sourceLabel.setFocusable(true);sourceLabel.setClickable(true);sourceLabel.setId(View.generateViewId());sourceLabel.setPadding(dp(9),dp(12),dp(6),dp(7));setFocusStyle(sourceLabel,Color.TRANSPARENT,6);sourceLabel.setOnClickListener(v->{if(sources.isEmpty())sourceDialog();else manageSources();});sidebar.addView(sourceLabel,new LinearLayout.LayoutParams(-1,dp(46)));
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus,newFocus)->{setSidebarExpanded(isInsideView(newFocus,nav));scrollShelfToFocused(newFocus);scrollMainToFocused(newFocus);});
        setSidebarExpanded(false);
        LinearLayout main=new LinearLayout(this);main.setOrientation(LinearLayout.VERTICAL);root.addView(main,new LinearLayout.LayoutParams(0,-1,1));LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);top.setPadding(dp(16),dp(3),dp(25),0);main.addView(top,new LinearLayout.LayoutParams(-1,dp(54)));searchControl=text("⌕",29,WHITE);searchControl.setGravity(Gravity.CENTER);searchControl.setFocusable(true);searchControl.setClickable(true);searchControl.setContentDescription("Search playlist");searchControl.setId(View.generateViewId());searchControl.setPadding(dp(12),0,dp(12),0);setFocusStyle(searchControl,Color.TRANSPARENT,7);searchControl.setOnClickListener(v->searchDialog());top.addView(searchControl,new LinearLayout.LayoutParams(dp(48),dp(44)));TextView settings=text("⚙",22,WHITE);settings.setGravity(Gravity.CENTER);settings.setFocusable(true);settings.setClickable(true);settings.setContentDescription("Settings and playlist sources");settings.setPadding(dp(8),0,dp(8),0);setFocusStyle(settings,Color.TRANSPARENT,7);settings.setOnClickListener(v->sourceDialog());top.addView(settings,new LinearLayout.LayoutParams(dp(48),dp(44)));TextView clock=text(new java.text.SimpleDateFormat("hh:mm a",Locale.getDefault()).format(new Date()),14,WHITE);clock.setGravity(Gravity.CENTER);top.addView(clock,new LinearLayout.LayoutParams(dp(94),dp(44)));
        mainScroll=new SmoothScrollView(this);mainScroll.setFillViewport(true);mainScroll.setVerticalScrollBarEnabled(false);mainScroll.setClipChildren(false);mainScroll.setClipToPadding(false);mainScroll.setOnScrollChangeListener((View v,int x,int y,int oldX,int oldY)->scheduleThumbnailScan(90));main.addView(mainScroll,new LinearLayout.LayoutParams(-1,0,1));content=new LinearLayout(this);content.setPadding(dp(14),dp(1),dp(22),dp(16));content.setOrientation(LinearLayout.VERTICAL);content.setClipChildren(false);mainScroll.addView(content);
        status=text("",12,MUTED);status.setPadding(dp(15),0,0,0);LinearLayout footer=new LinearLayout(this);footer.setGravity(Gravity.CENTER_VERTICAL|Gravity.RIGHT);footer.setPadding(dp(12),0,dp(24),dp(7));TextView hints=text("✥  Navigate     ⓞ  Select     ↶  Back     MENU  My List",12,0xffc5c8d0);hints.setGravity(Gravity.CENTER);hints.setPadding(dp(16),dp(7),dp(16),dp(7));hints.setBackground(rounded(0xff10151d,24));footer.addView(status,new LinearLayout.LayoutParams(0,dp(32),1));footer.addView(hints);main.addView(footer,new LinearLayout.LayoutParams(-1,dp(43)));
        requestInitialFocus();
        showSplash();
    }
    private void showWelcome(){content.removeAllViews();TextView t=text("Your screen for live TV, movies and series.",27,WHITE);t.setTypeface(null,Typeface.BOLD);t.setPadding(0,dp(30),0,dp(12));content.addView(t);TextView sub=text("Connect a playlist to start building your home screen.",16,MUTED);content.addView(sub);Button b=button("Add playlist");b.setId(View.generateViewId());b.setOnClickListener(v->sourceDialog());LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(54));p.setMargins(0,dp(18),0,0);content.addView(b,p);firstContentItem=b;dismissSplash();}
    private Button button(String label){Button b=new Button(this);b.setText(label);b.setTextColor(WHITE);b.setBackground(rounded(RED,6));b.setFocusable(true);b.setClickable(true);b.setFocusableInTouchMode(true);setFocusStyle(b,RED,6);return b;}
    private void refreshRows(){content.animate().cancel();content.setAlpha(0f);content.removeAllViews();shelfRows.clear();firstContentItem=null;sourceLabel.setText("SOURCE  ·  "+(sources.isEmpty()?"Add playlist":sources.get(Math.min(activeSource,sources.size()-1)).name));
        if(sources.isEmpty()){showWelcome();wireMainFocus();animateContentIn();return;}if(category.equals("Home")){renderHome();wireMainFocus();animateContentIn();return;}
        // Series are grouped by show, not listed episode by episode.
        if(category.equals("Series")){renderSeriesCategory();wireMainFocus();animateContentIn();return;}
        ArrayList<Entry> shown=new ArrayList<>();
        if(category.equals("My List")){
            shown.addAll(myListEntries());
        }else{
            for(Entry e:entries)if(e.type.equals(category.equals("Live TV")?"Live":category))shown.add(e);
        }
        // Sort Live TV: Kannada channels first
        if (category.equals("Live TV")) {
            shown.sort((a, b) -> {
                boolean aKannada = isKannadaChannel(a);
                boolean bKannada = isKannadaChannel(b);
                if (aKannada && !bKannada) return -1;
                if (!aKannada && bKannada) return 1;
                return 0;
            });
        }
        if(shown.isEmpty()){
            if(entries.isEmpty()&&playlistError==null){View loading=loadingBlock("Loading your playlist");content.addView(loading);firstContentItem=loading;wireMainFocus();animateContentIn();return;}
            String emptyMessage=entries.isEmpty()?(playlistError!=null?"Could not load this playlist.\n"+playlistError:"Loading your playlist…"):category.equals("My List")?"Your list is empty. Focus a title and press MENU to save it.":"No "+category.toLowerCase(Locale.ROOT)+" found in this playlist.";
            TextView empty=text(emptyMessage,20,MUTED);empty.setPadding(0,dp(22),0,0);empty.setFocusable(true);empty.setId(View.generateViewId());content.addView(empty);firstContentItem=empty;wireMainFocus();animateContentIn();return;
        }
        LinkedHashMap<String,ArrayList<Entry>> groups=new LinkedHashMap<>();for(Entry e:shown){String g=e.group==null||e.group.isEmpty()?category:e.group;groups.computeIfAbsent(g,k->new ArrayList<>()).add(e);}

        // Category icons for group headers - use vector drawables
        int catIconRes = 0;
        int catIconColor = WHITE;
        if (category.equals("Movies") || (shown.size() > 0 && shown.get(0).type.equals("Movies"))) { catIconRes = R.drawable.ic_movies; catIconColor = 0xFFE50914; }
        else if (category.equals("Series") || (shown.size() > 0 && shown.get(0).type.equals("Series"))) { catIconRes = R.drawable.ic_series; catIconColor = 0xFF9C27B0; }
        else if (category.equals("Live TV") || (shown.size() > 0 && shown.get(0).type.equals("Live"))) { catIconRes = R.drawable.ic_live_tv; catIconColor = 0xFF4CAF50; }
        else if (category.equals("My List")) { catIconRes = R.drawable.ic_my_list; catIconColor = 0xFFE91E63; }

        boolean portrait=category.equals("Movies")||category.equals("Series");
        for(String group:groups.keySet()){
            LinearLayout heading = new LinearLayout(this);
            heading.setGravity(Gravity.CENTER_VERTICAL);
            heading.setPadding(0, dp(8), 0, dp(4));

            ImageView iconView = new ImageView(this);
            if (catIconRes != 0) {
                iconView.setImageResource(catIconRes);
                iconView.setColorFilter(catIconColor);
            }
            iconView.setLayoutParams(new LinearLayout.LayoutParams(dp(20), dp(20)));
            heading.addView(iconView);

            TextView name = text(group + "  ›", 17, WHITE);name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setTypeface(null, Typeface.BOLD);
            heading.addView(name, new LinearLayout.LayoutParams(0, dp(30), 1));

            TextView count = text(groups.get(group).size() + " titles", 13, 0xFF888888);
            count.setPadding(0, 0, dp(12), 0);
            heading.addView(count);

            TextView seeAll = text("See All  ›", 13, 0xffc7cad1);
            seeAll.setFocusable(true);
            seeAll.setClickable(true);
            seeAll.setGravity(Gravity.CENTER);
            seeAll.setPadding(dp(10), dp(5), dp(10), dp(5));
            setFocusStyle(seeAll, Color.TRANSPARENT, 6);
            seeAll.setTag(groups.get(group));
            seeAll.setOnClickListener(v -> {
                ArrayList<Entry> allItems = (ArrayList<Entry>) v.getTag();
                showAllGrid(group, allItems);
            });
            heading.addView(seeAll);

            content.addView(heading);

            // Subtle divider
            View divider = new View(this);
            divider.setBackgroundColor(0xFF1A1A2E);
            LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(-1, dp(1));
            divParams.setMargins(0, dp(4), 0, dp(8));
            content.addView(divider);

            HorizontalScrollView hsv = createShelfScroll();
            LinearLayout row = (LinearLayout) hsv.getChildAt(0);
            content.addView(hsv);
            shelfRows.add(hsv);
            addShelfTiles(hsv, row, groups.get(group), 0, portrait);
        }
        wireMainFocus();animateContentIn();}
    // ==================== SERIES GROUPING ====================
    // Providers publish one M3U entry per episode. Listing them individually
    // turns a 10-show playlist into hundreds of near-identical "Show S01E07"
    // cards. These helpers collapse episodes into a single show, order them, and
    // remember the episode that was being watched so a card can resume there.

    private static final java.util.regex.Pattern EP_SXXEXX =
            java.util.regex.Pattern.compile("(?i)\\bS(\\d{1,2})\\s*[._-]?\\s*E(\\d{1,3})\\b");
    private static final java.util.regex.Pattern EP_NXNN =
            java.util.regex.Pattern.compile("(?i)\\b(\\d{1,2})\\s*x\\s*(\\d{2,3})\\b");
    private static final java.util.regex.Pattern EP_SEASON_EP =
            java.util.regex.Pattern.compile("(?i)\\bseason\\s*(\\d{1,2})\\s*(?:episode|ep)\\s*(\\d{1,3})\\b");
    private static final java.util.regex.Pattern EP_ONLY =
            java.util.regex.Pattern.compile("(?i)\\b(?:episode|ep)\\s*(\\d{1,3})\\b");

    private static class Episode {
        final Entry entry;
        final int season;
        final int episode;
        Episode(Entry entry, int season, int episode) {
            this.entry = entry;
            this.season = season;
            this.episode = episode;
        }
    }

    private ArrayList<Series> seriesIndexCache;
    private int seriesIndexCacheSize = -1;

    private static class Series {
        final String name;
        String group = "";
        String logo = "";
        final ArrayList<Episode> episodes = new ArrayList<>();
        Series(String name) { this.name = name; }
    }

    /** {season, episode, matchStart} for a title, or null when it carries none. */
    private int[] episodeMarkers(String title) {
        java.util.regex.Matcher m;
        if ((m = EP_SXXEXX.matcher(title)).find())
            return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), m.start()};
        if ((m = EP_NXNN.matcher(title)).find())
            return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), m.start()};
        if ((m = EP_SEASON_EP.matcher(title)).find())
            return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), m.start()};
        if ((m = EP_ONLY.matcher(title)).find())
            return new int[]{0, Integer.parseInt(m.group(1)), m.start()};
        return null;
    }

    /** "Breaking Bad S01E02 1080p" -> "Breaking Bad". */
    private String seriesName(String title, int markerStart) {
        String cut = title.substring(0, markerStart)
                .replaceAll("[\\s\\-\u2013\u2014|:_.]+$", "")
                .trim();
        return cut.isEmpty() ? title : cut;
    }

    private String episodeLabel(Episode ep) {
        if (ep.season > 0 && ep.episode > 0)
            return String.format(java.util.Locale.ROOT, "S%02dE%02d", ep.season, ep.episode);
        if (ep.episode > 0)
            return String.format(java.util.Locale.ROOT, "E%02d", ep.episode);
        return "EP";
    }

    /**
     * Grouped shows, cached until the playlist changes. Home is rebuilt on every
     * My List toggle, and re-parsing a few thousand titles each time is wasted
     * main-thread work on a TV box.
     */
    private ArrayList<Series> seriesIndex() {
        if (seriesIndexCache == null || seriesIndexCacheSize != entries.size()) {
            seriesIndexCache = buildSeriesIndex();
            seriesIndexCacheSize = entries.size();
        }
        return seriesIndexCache;
    }

    /** Every show in the playlist, episodes ordered by season and episode. */
    private ArrayList<Series> buildSeriesIndex() {
        LinkedHashMap<String, Series> byName = new LinkedHashMap<>();
        for (Entry e : entries) {
            if (!"Series".equals(e.type)) continue;
            int[] markers = episodeMarkers(e.name);
            String show = markers == null ? e.name : seriesName(e.name, markers[2]);
            String key = show.toLowerCase(java.util.Locale.ROOT).trim();
            Series series = byName.get(key);
            if (series == null) {
                series = new Series(show);
                byName.put(key, series);
            }
            series.episodes.add(new Episode(e,
                    markers == null ? 0 : markers[0],
                    markers == null ? 0 : markers[1]));
            if (series.group.isEmpty() && e.group != null) series.group = e.group;
            if (series.logo.isEmpty() && e.logo != null && !e.logo.isEmpty()) series.logo = e.logo;
        }
        for (Series series : byName.values()) {
            series.episodes.sort((a, b) -> {
                if (a.season != b.season) return Integer.compare(a.season, b.season);
                if (a.episode != b.episode) return Integer.compare(a.episode, b.episode);
                return a.entry.name.compareToIgnoreCase(b.entry.name);
            });
        }
        return new ArrayList<>(byName.values());
    }

    /**
     * Where to pick up a show: the most recently watched unfinished episode, or
     * the first episode when nothing has been watched yet.
     */
    private Episode resumeEpisode(Series series) {
        Episode lastWatched = null;
        long lastStamp = -1;
        for (Episode ep : series.episodes) {
            JSONObject progress = getPlaybackProgress(ep.entry);
            if (progress == null || progress.optBoolean("completed")) continue;
            long stamp = progress.optLong("timestamp", 0);
            if (stamp > lastStamp) {
                lastStamp = stamp;
                lastWatched = ep;
            }
        }
        if (lastWatched != null) return lastWatched;
        return series.episodes.isEmpty() ? null : series.episodes.get(0);
    }

    /** Open a show: its episodes as cards, focused on the resume episode. */
    private void openSeries(Series series) {
        ArrayList<Entry> episodes = new ArrayList<>();
        for (Episode ep : series.episodes) episodes.add(ep.entry);
        if (episodes.isEmpty()) return;
        Episode resume = resumeEpisode(series);
        int focusIndex = 0;
        if (resume != null) {
            for (int i = 0; i < episodes.size(); i++) {
                if (episodes.get(i) == resume.entry) { focusIndex = i; break; }
            }
        }
        showAllGrid(series.name, episodes, focusIndex);
    }

    private void addSeriesTiles(HorizontalScrollView hsv, LinearLayout row, ArrayList<Series> shows, int start) {
        int batchSize = start == 0 ? 8 : 12;
        int end = Math.min(start + batchSize, shows.size());
        int width = dp(CARD_W_DP), artHeight = dp(CARD_ART_H_DP);
        for (int i = start; i < end; i++) {
            Series series = shows.get(i);
            Episode resume = resumeEpisode(series);

            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setBackground(rounded(PANEL, 9));
            tile.setClipChildren(false);
            tile.setFocusable(true);
            tile.setClickable(true);
            tile.setTag(series);
            tile.setId(View.generateViewId());
            setFocusStyle(tile, PANEL, 9);
            tile.setOnClickListener(v -> openSeries(series));
            if (firstContentItem == null) firstContentItem = tile;

            FrameLayout art = new FrameLayout(this);
            art.setBackground(rounded(0xff17191e, 8));
            art.setClipToOutline(true);
            LinearLayout.LayoutParams artParams = new LinearLayout.LayoutParams(-1, artHeight);
            artParams.setMargins(dp(2), dp(2), dp(2), dp(2));
            tile.addView(art, artParams);

            TextView placeholder = text("", 32, WHITE);
            styleArtworkPlaceholder(placeholder, series.episodes.get(0).entry);
            art.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));

            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setAdjustViewBounds(false);
            image.setContentDescription(series.name + " artwork");
            image.setTag(resume != null ? resume.entry : series.episodes.get(0).entry);
            art.addView(image, new FrameLayout.LayoutParams(-1, -1));
            placeholder.bringToFront();

            // Episode count, or the episode a remote should resume from.
            String badgeText = resume != null && getPlaybackProgress(resume.entry) != null
                    ? "RESUME " + episodeLabel(resume)
                    : series.episodes.size() + (series.episodes.size() == 1 ? " EPISODE" : " EPISODES");
            TextView badge = text(badgeText, 10, WHITE);
            badge.setTypeface(null, Typeface.BOLD);
            badge.setPadding(dp(7), dp(3), dp(7), dp(3));
            badge.setBackground(rounded(0xE0000000, 4));
            FrameLayout.LayoutParams badgeParams =
                    new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
            badgeParams.setMargins(dp(7), dp(7), 0, 0);
            art.addView(badge, badgeParams);

            addFavoriteBadge(art, resume != null ? resume.entry : series.episodes.get(0).entry);

            TextView title = text(series.name, 13, WHITE);
            title.setTypeface(null, Typeface.BOLD);
            title.setMaxLines(1);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setPadding(dp(5), dp(2), dp(5), dp(3));
            tile.addView(title, new LinearLayout.LayoutParams(-1, -2));

            LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(width, dp(CARD_H_DP));
            tileParams.setMargins(0, 0, dp(CARD_GAP_DP), dp(4));
            row.addView(tile, tileParams);
        }
        if (end < shows.size()) {
            LinearLayout more = new LinearLayout(this);
            more.setGravity(Gravity.CENTER);
            more.setOrientation(LinearLayout.VERTICAL);
            more.setBackground(rounded(0xff292b31, 9));
            more.setFocusable(true);
            more.setClickable(true);
            setFocusStyle(more, 0xff292b31, 9);
            TextView arrow = text("\u203a", 34, RED);
            arrow.setGravity(Gravity.CENTER);
            more.addView(arrow);
            TextView label = text("LOAD MORE", 12, WHITE);
            label.setTypeface(null, Typeface.BOLD);
            label.setGravity(Gravity.CENTER);
            more.addView(label);
            more.setOnClickListener(v -> {
                row.removeView(more);
                int firstAdded = row.getChildCount();
                addSeriesTiles(hsv, row, shows, end);
                if (firstAdded < row.getChildCount()) row.getChildAt(firstAdded).requestFocus();
            });
            LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(width, dp(CARD_H_DP));
            moreParams.setMargins(0, 0, dp(CARD_GAP_DP), dp(4));
            row.addView(more, moreParams);
        }
        hsv.postDelayed(() -> loadVisibleThumbnails(hsv), 100);
    }

    /** Home shelf: one card per show instead of one per episode. */
    private void appendSeriesShelf() {
        ArrayList<Series> shows = seriesIndex();
        if (shows.isEmpty()) return;

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(0, dp(8), 0, dp(4));

        ImageView iconView = new ImageView(this);
        iconView.setImageResource(R.drawable.ic_series);
        iconView.setColorFilter(0xFF9C27B0);
        iconView.setPadding(0, 0, dp(8), 0);
        iconView.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));
        heading.addView(iconView);

        TextView name = text("Series  \u203a", 17, WHITE);name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setTypeface(null, Typeface.BOLD);
        heading.addView(name, new LinearLayout.LayoutParams(0, dp(30), 1));

        TextView count = text(shows.size() == 1 ? "1 show" : shows.size() + " shows", 13, 0xFF888888);
        count.setPadding(0, 0, dp(12), 0);
        heading.addView(count);

        TextView seeAll = text("See All  \u203a", 13, 0xffc7cad1);
        seeAll.setFocusable(true);
        seeAll.setClickable(true);
        seeAll.setGravity(Gravity.CENTER);
        seeAll.setPadding(dp(10), dp(5), dp(10), dp(5));
        setFocusStyle(seeAll, Color.TRANSPARENT, 6);
        seeAll.setOnClickListener(v -> {
            category = "Series";
            updateCategoryTabs();
            refreshRows();
            focusFirstContent();
        });
        heading.addView(seeAll);

        content.addView(heading);

        View divider = new View(this);
        divider.setBackgroundColor(0xFF1A1A2E);
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(-1, dp(1));
        divParams.setMargins(0, dp(4), 0, dp(8));
        content.addView(divider);

        HorizontalScrollView hsv = createShelfScroll();
        LinearLayout row = (LinearLayout) hsv.getChildAt(0);
        content.addView(hsv);
        shelfRows.add(hsv);
        addSeriesTiles(hsv, row, shows, 0);
    }

    /** Series tab: shows grouped under their provider group, one card per show. */
    private void renderSeriesCategory() {
        ArrayList<Series> shows = seriesIndex();
        if (shows.isEmpty()) {
            if (entries.isEmpty() && playlistError == null) {
                View loading = loadingBlock("Loading your playlist");
                content.addView(loading);
                firstContentItem = loading;
                return;
            }
            TextView empty = text(entries.isEmpty()
                    ? "Could not load this playlist.\n" + playlistError
                    : "No series found in this playlist.", 20, MUTED);
            empty.setPadding(0, dp(22), 0, 0);
            empty.setFocusable(true);
            empty.setId(View.generateViewId());
            content.addView(empty);
            firstContentItem = empty;
            return;
        }

        LinkedHashMap<String, ArrayList<Series>> groups = new LinkedHashMap<>();
        for (Series series : shows) {
            String group = series.group == null || series.group.isEmpty() ? "All Series" : series.group;
            groups.computeIfAbsent(group, k -> new ArrayList<>()).add(series);
        }

        for (String group : groups.keySet()) {
            LinearLayout heading = new LinearLayout(this);
            heading.setGravity(Gravity.CENTER_VERTICAL);
            heading.setPadding(0, dp(8), 0, dp(4));

            ImageView iconView = new ImageView(this);
            iconView.setImageResource(R.drawable.ic_series);
            iconView.setColorFilter(0xFF9C27B0);
            iconView.setLayoutParams(new LinearLayout.LayoutParams(dp(20), dp(20)));
            heading.addView(iconView);

            TextView name = text(group + "  \u203a", 17, WHITE);name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setTypeface(null, Typeface.BOLD);
            heading.addView(name, new LinearLayout.LayoutParams(0, dp(30), 1));

            TextView count = text(groups.get(group).size() == 1 ? "1 show" : groups.get(group).size() + " shows", 13, 0xFF888888);
            count.setPadding(0, 0, dp(12), 0);
            heading.addView(count);

            content.addView(heading);

            View divider = new View(this);
            divider.setBackgroundColor(0xFF1A1A2E);
            LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(-1, dp(1));
            divParams.setMargins(0, dp(4), 0, dp(8));
            content.addView(divider);

            HorizontalScrollView hsv = createShelfScroll();
            LinearLayout row = (LinearLayout) hsv.getChildAt(0);
            content.addView(hsv);
            shelfRows.add(hsv);
            addSeriesTiles(hsv, row, groups.get(group), 0);
        }
    }

    private void renderHome(){
        Entry hero = featuredEntry();
        content.addView(buildHero(hero));
        
        // Add Continue Watching row if there's progress
        addContinueWatchingRow();
        
        appendHomeShelf("Movies","Movies",false);
        appendSeriesShelf();
        appendHomeShelf("Live TV","Live",true); // Live TV shares the dashboard shelf size
        if(entries.isEmpty()){View loading=playlistError!=null?text("Could not load this playlist.\n"+playlistError,16,MUTED):loadingBlock("Loading your playlist");if(loading instanceof TextView)((TextView)loading).setPadding(dp(4),dp(12),0,dp(10));content.addView(loading);}
    }

    private Entry featuredEntry(){
        // Priority: 1) Recently watched with progress, 2) Movies, 3) Series, 4) First entry
        ArrayList<JSONObject> recent = getRecentProgress(1);
        if (!recent.isEmpty()) {
            try {
                String name = recent.get(0).getString("name");
                for (Entry e : entries) {
                    if (e.name.equals(name)) return e;
                }
            } catch (Exception ignored) {}
        }
        for(Entry e:entries)if(e.type.equals("Movies"))return e;
        for(Entry e:entries)if(e.type.equals("Series"))return e;
        return entries.isEmpty()?null:entries.get(0);
    }

    private void addContinueWatchingRow() {
        ArrayList<JSONObject> recent = getRecentProgress(6);
        if (recent.isEmpty()) return;
        
        ArrayList<Entry> continueEntries = new ArrayList<>();
        for (JSONObject obj : recent) {
            try {
                String name = obj.getString("name");
                for (Entry e : entries) {
                    if (e.name.equals(name)) {
                        continueEntries.add(e);
                        break;
                    }
                }
            } catch (Exception ignored) {}
        }
        if (continueEntries.isEmpty()) return;
        
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text("Continue Watching  ›", 17, WHITE);name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setTypeface(null, Typeface.BOLD);
        heading.addView(name, new LinearLayout.LayoutParams(0, dp(30), 1));
        TextView seeAll = text("See All  ›", 13, 0xffc7cad1);
        seeAll.setFocusable(true);
        seeAll.setClickable(true);
        seeAll.setGravity(Gravity.CENTER);
        seeAll.setPadding(dp(10), dp(5), dp(10), dp(5));
        setFocusStyle(seeAll, Color.TRANSPARENT, 6);
        seeAll.setOnClickListener(v -> showAllGrid("Continue Watching", continueEntries));
        heading.addView(seeAll);
        content.addView(heading);
        
        HorizontalScrollView hsv = createShelfScroll();
        LinearLayout row = (LinearLayout) hsv.getChildAt(0);
        content.addView(hsv);
        shelfRows.add(hsv);
        addContinueTiles(hsv, row, continueEntries);
    }

    // ViewHolder for grid items (performance optimization)
    private static class GridItemViewHolder {
        LinearLayout tile;
        FrameLayout art;
        ImageView image;
        TextView placeholder;
        TextView titleText;
        TextView liveBadge;
        Entry entry;
        
        GridItemViewHolder(LinearLayout tile) {
            this.tile = tile;
        }
    }
    
    // View pool for recycling grid items
    private final ArrayList<GridItemViewHolder> viewPool = new ArrayList<>();
    private final ArrayList<GridItemViewHolder> activeGridViewHolders = new ArrayList<>();

    private boolean hasArtwork(String logo) {
        return logo != null && !logo.trim().isEmpty();
    }

    private void styleArtworkPlaceholder(TextView placeholder, Entry entry) {
        String type = entry == null || entry.type == null ? "Movies" : entry.type;
        boolean isLive = "Live".equalsIgnoreCase(type) || "Live TV".equalsIgnoreCase(type);
        boolean isSeries = "Series".equalsIgnoreCase(type);
        String label = isLive ? "LIVE TV" : isSeries ? "SERIES" : "MOVIE";
        String symbol = isLive ? "◉" : isSeries ? "▤" : "▶";
        int[][] palettes = isLive
                ? new int[][]{{0xFF092B2A, 0xFF16735D}, {0xFF102B33, 0xFF286D80}, {0xFF172D22, 0xFF467A37}}
                : isSeries
                    ? new int[][]{{0xFF12152D, 0xFF374A91}, {0xFF21152F, 0xFF70428A}, {0xFF102538, 0xFF287E9A}}
                    : new int[][]{{0xFF200F18, 0xFF7A1F32}, {0xFF24170E, 0xFF9A5124}, {0xFF1A1428, 0xFF55418D}};
        int paletteIndex = entry == null || entry.name == null ? 0
                : Math.floorMod(entry.name.hashCode(), palettes.length);
        GradientDrawable backdrop = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, palettes[paletteIndex]);
        backdrop.setCornerRadius(dp(7));
        placeholder.setBackground(backdrop);
        placeholder.setVisibility(View.VISIBLE);
        placeholder.setTextColor(0xFFF5F5F5);
        placeholder.setTextSize(15);
        placeholder.setGravity(Gravity.CENTER);
        placeholder.setTypeface(null, Typeface.BOLD);
        placeholder.setLetterSpacing(0.08f);
        String text = symbol + "\n" + label;
        android.text.SpannableString styledText = new android.text.SpannableString(text);
        styledText.setSpan(new android.text.style.RelativeSizeSpan(2.5f), 0, symbol.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        styledText.setSpan(new android.text.style.RelativeSizeSpan(0.72f), symbol.length() + 1,
                text.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        placeholder.setText(styledText);
    }

    private void recycleGridViews(FrameLayout gridOverlay) {
        for (GridItemViewHolder holder : activeGridViewHolders) {
            holder.tile.setOnClickListener(null);
            holder.image.setTag(null);
            holder.image.setImageDrawable(null);
            holder.art.removeView(holder.liveBadge);
            holder.liveBadge = null;
            // Pooled cards are reused for different titles. Leaving a heart
            // behind made unsaved movies/channels look saved and hid the heart
            // on the title that was actually just added.
            removeFavoriteBadges(holder.art);
            holder.tile.setTag(null);
            holder.tile.setFocusable(false);
            holder.tile.setPressed(false);
            holder.tile.setAlpha(1f);
            holder.tile.setScaleX(1f);
            holder.tile.setScaleY(1f);
            if (holder.tile.getParent() instanceof ViewGroup) {
                ((ViewGroup) holder.tile.getParent()).removeView(holder.tile);
            }
            viewPool.add(holder);
        }
        activeGridViewHolders.clear();
        if (gridContainer != null) gridContainer.removeAllViews();
        gridContainer = null;
        gridScroll = null;
        gridOverlayView = null;
        gridBackButton = null;
        firstGridItem = null;
        // Hand the home screen its anchor back so its Left-to-nav shortcut and
        // wireMainFocus() still hold after the overlay is dismissed. If refreshRows()
        // rebuilt the home screen while the grid was open, its fresh anchor wins.
        if (firstContentItem == null || firstContentItem.getParent() == null) {
            firstContentItem = previousHomeFirstContent;
        }
        previousHomeFirstContent = null;
    }

    /** Dismiss the See All grid and restore the screen it was opened from. */
    private void closeAllGrid() { closeAllGrid(null); }

    private void closeAllGrid(Runnable afterClose) {
        final FrameLayout overlay = gridOverlayView;
        if (overlay == null) {
            if (afterClose != null) afterClose.run();
            return;
        }
        overlay.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            recycleGridViews(overlay);
            setContentView(root);
            if (previousHomeFocus != null && previousHomeFocus.isShown() && previousHomeFocus.isFocusable()) {
                previousHomeFocus.requestFocus();
            } else {
                requestInitialFocus();
            }
            if (afterClose != null) afterClose.run();
        }).start();
    }

    private GridItemViewHolder createGridItemViewHolder(int tileWidth, int tileHeight, int artHeight, Entry e) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setBackground(rounded(0xFF0D141D, 8));
        tile.setClipChildren(false);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setId(View.generateViewId());
        setFocusStyle(tile, 0xFF0D141D, 8);
        
        FrameLayout art = new FrameLayout(this);
        art.setBackground(rounded(0xFF131A23, 8));
        art.setClipToOutline(true);
        LinearLayout.LayoutParams artParams = new LinearLayout.LayoutParams(tileWidth, artHeight);
        artParams.setMargins(dp(2), dp(2), dp(2), dp(2));
        tile.addView(art, artParams);
        
        TextView placeholder = new TextView(this);
        styleArtworkPlaceholder(placeholder, e);
        art.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
        
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.addView(image, new FrameLayout.LayoutParams(-1, -1));
        placeholder.bringToFront();
        
        TextView titleText = new TextView(this);
        titleText.setTextColor(WHITE);
        titleText.setTextSize(13);
        titleText.setTypeface(null, Typeface.BOLD);
        titleText.setMaxLines(2);
        titleText.setEllipsize(TextUtils.TruncateAt.END);
        titleText.setPadding(dp(10), dp(6), dp(10), dp(8));
        
        tile.addView(titleText, new LinearLayout.LayoutParams(-1, -2));
        
        GridItemViewHolder holder = new GridItemViewHolder(tile);
        holder.art = art;
        holder.image = image;
        holder.placeholder = placeholder;
        holder.titleText = titleText;
        
        return holder;
    }

    // ==================== SHOW ALL GRID VIEW (WITH PAGINATION) ====================
    // Pagination constants
    private static final int ITEMS_PER_PAGE = 24; // 4 rows x 6 columns
    private static final int LOAD_THRESHOLD_DP = 300; // Load more when 300dp from bottom
    
    // Pagination state
    private ArrayList<Entry> allGridItems = new ArrayList<>();
    private int currentGridPage = 0;
    private boolean isLoadingMore = false;
    private LinearLayout gridContainer;
    private ScrollView gridScroll;
    private int gridTileWidth, gridTileHeight, gridArtHeight;
    private int gridColumns = 6;
    private String gridTitle;

    private void showAllGrid(String title, ArrayList<Entry> items) {
        showAllGrid(title, items, 0);
    }

    /**
     * @param focusIndex item to focus once the overlay is up — a show opens on
     *                   the episode being watched rather than on season one.
     */
    private void showAllGrid(String title, ArrayList<Entry> items, int focusIndex) {
        try {
            if (items == null || items.isEmpty()) return;
            gridFocusIndex = Math.max(0, focusIndex);

            // The grid replaces the whole content view, so keep the home screen's
            // focus anchor to restore on close and start with none of our own.
            previousHomeFirstContent = firstContentItem;
            firstGridItem = null;
            
            // Save current home state
            previousHomeFocus = getCurrentFocus();
            
            // Check if root is available
            if (root == null) {
                Toast.makeText(this, "Unable to open grid", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // Store items for pagination
            allGridItems = new ArrayList<>(items);
            currentGridPage = 0;
            isLoadingMore = false;
            gridTitle = title;
            
            // Save current home state
            previousHomeFocus = getCurrentFocus();
            
            // Check if root is available
            if (root == null) {
                Toast.makeText(this, "Unable to open grid", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // Create full-screen grid overlay
            final FrameLayout gridOverlay = new FrameLayout(this);
            gridOverlay.setBackgroundColor(0xFF05090F);
            gridOverlay.setAlpha(0f);
            
            // Top bar
            LinearLayout topBar = new LinearLayout(this);
            topBar.setOrientation(LinearLayout.HORIZONTAL);
            topBar.setGravity(Gravity.CENTER_VERTICAL);
            topBar.setPadding(dp(24), dp(16), dp(24), dp(16));
            GradientDrawable topGradient = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xCC000000, 0x88000000, 0x00000000}
            );
            topBar.setBackground(topGradient);
            
            // Back button
            ImageView backBtn = new ImageView(this);
            backBtn.setImageResource(R.drawable.ic_back);
            backBtn.setColorFilter(WHITE);
            backBtn.setPadding(dp(12), dp(8), dp(12), dp(8));
            backBtn.setFocusable(true);
            backBtn.setClickable(true);
            backBtn.setContentDescription("Back");
            backBtn.setOnClickListener(v -> closeAllGrid());
            setFocusStyleView(backBtn, dp(40));
            gridBackButton = backBtn;
            topBar.addView(backBtn, new LinearLayout.LayoutParams(-2, -2));
            
            // Title
            TextView titleText = new TextView(this);
            titleText.setText(title);
            titleText.setTextColor(WHITE);
            titleText.setTextSize(20);
            titleText.setTypeface(null, Typeface.BOLD);
            titleText.setMaxLines(1);
            titleText.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1);
            titleParams.setMargins(dp(16), 0, dp(16), 0);
            topBar.addView(titleText, titleParams);
            
            // Count
            TextView countText = new TextView(this);
            countText.setText(items.size() + " items");
            countText.setTextColor(0xFFB0B0B0);
            countText.setTextSize(14);
            countText.setPadding(dp(16), 0, 0, 0);
            topBar.addView(countText);
            
            FrameLayout.LayoutParams topBarParams = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
            gridOverlay.addView(topBar, topBarParams);
            
            // Grid container
            gridScroll = new ScrollView(this);
            gridScroll.setFillViewport(true);
            gridScroll.setVerticalScrollBarEnabled(false);
            FrameLayout.LayoutParams scrollParams = new FrameLayout.LayoutParams(-1, -1);
            scrollParams.setMargins(0, dp(70), 0, 0); // Below top bar
            gridOverlay.addView(gridScroll, scrollParams);
            
            // Grid layout (6 columns for TV)
            gridContainer = new LinearLayout(this);
            gridContainer.setOrientation(LinearLayout.VERTICAL);
            gridContainer.setPadding(dp(24), dp(16), dp(24), dp(24));
            gridContainer.setClipChildren(false);
            gridScroll.addView(gridContainer);
            
            // Grid dimensions
            gridColumns = 6;
            final int screenWidth = getResources().getDisplayMetrics().widthPixels;
            gridTileWidth = (screenWidth - dp(24) * 2 - dp(8) * (gridColumns - 1)) / gridColumns;
            gridTileHeight = gridTileWidth * 3 / 2; // 2:3 aspect for posters
            gridArtHeight = gridTileHeight - dp(52); // two-line title + padding
            
            // Load first page
            loadNextGridPage();
            
            // Add scroll listener for pagination
            gridScroll.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                if (!isLoadingMore && gridContainer != null) {
                    int scrollRange = gridContainer.getHeight() - gridScroll.getHeight();
                    if (scrollRange > 0 && scrollY >= scrollRange - dp(LOAD_THRESHOLD_DP)) {
                        loadNextGridPage();
                    }
                }
            });
            
            // Handle focus navigation for grid
            gridScroll.setOnFocusChangeListener((v, focused) -> {
                if (focused) {
                    if (firstGridItem != null) firstGridItem.requestFocus();
                    else if (gridBackButton != null) gridBackButton.requestFocus();
                }
            });
            
            // Override key handling for grid
            gridOverlay.setFocusableInTouchMode(true);
            gridOverlay.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            gridOverlay.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BACK) {
                    closeAllGrid();
                    return true;
                }
                return false;
            });
            
            // Only publish the overlay as "open" once it is the content view, so a
            // failure while building it cannot leave Back pointing at a grid that
            // was never shown.
            gridOverlayView = gridOverlay;
            setContentView(gridOverlay);
            gridOverlay.animate().alpha(1f).setDuration(200).start();
            
            // Focus first item
            gridOverlay.post(() -> {
                // The requested item may sit on a page that has not been built
                // yet (a 30-episode show), so pull pages until it exists.
                while (gridFocusIndex > 0 && gridFocusIndex >= activeGridViewHolders.size()
                        && currentGridPage * ITEMS_PER_PAGE < allGridItems.size()) {
                    loadNextGridPage();
                }
                View target = null;
                if (gridFocusIndex > 0 && gridFocusIndex < activeGridViewHolders.size()) {
                    target = activeGridViewHolders.get(gridFocusIndex).tile;
                } else if (firstGridItem != null) {
                    target = firstGridItem;
                }
                // Never leave the screen without focus: a D-pad key with no
                // focused view is swallowed by dispatchKeyEvent, which is the
                // "remote does nothing" report.
                if (target != null) target.requestFocus();
                if (getCurrentFocus() == null) backBtn.requestFocus();
            });
            
        } catch (Exception e) {
            Log.e("showAllGrid", "Error showing grid", e);
            Toast.makeText(this, "Error opening grid: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    private void loadNextGridPage() {
        if (isLoadingMore || currentGridPage * ITEMS_PER_PAGE >= allGridItems.size()) {
            return;
        }
        
        isLoadingMore = true;
        
        int startIndex = currentGridPage * ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, allGridItems.size());
        
        if (startIndex >= endIndex) {
            isLoadingMore = false;
            return;
        }
        
        // Load items for this page
        for (int i = startIndex; i < endIndex; i += gridColumns) {
            int rowStart = i;
            int rowEnd = Math.min(i + gridColumns, endIndex);
            
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            rowParams.setMargins(0, 0, 0, dp(16));
            gridContainer.addView(row, rowParams);
            
            for (int j = rowStart; j < rowEnd; j++) {
                Entry e = allGridItems.get(j);
                
                // Reuse view from pool if available
                GridItemViewHolder holder;
                if (!viewPool.isEmpty()) {
                    holder = viewPool.remove(viewPool.size() - 1);
                    holder.tile.setTag(allGridItems.get(j));
                    holder.entry = allGridItems.get(j);
                } else {
                    holder = createGridItemViewHolder(gridTileWidth, gridTileHeight, gridArtHeight, e);
                }
                activeGridViewHolders.add(holder);
                holder.tile.setFocusable(true);
                holder.tile.setClickable(true);
                
                holder.entry = e;
                holder.tile.setTag(e);
                holder.tile.setOnClickListener(v -> closeAllGrid(() -> play(e)));
                
                // Update content
                holder.entry = e;
                holder.titleText.setText(e.name);
                styleArtworkPlaceholder(holder.placeholder, e);
                
                // Load thumbnail
                holder.art.post(() -> loadThumbnail(holder.image, e.logo, holder.art.getWidth(), holder.art.getHeight(), holder.placeholder));
                
                // Live badge
                if (holder.liveBadge != null) {
                    holder.art.removeView(holder.liveBadge);
                    holder.liveBadge = null;
                }
                addFavoriteBadge(holder.art, e);
                if (e.type.equals("Live")) {
                    TextView badge = text("LIVE", 10, WHITE);
                    badge.setTypeface(null, Typeface.BOLD);
                    badge.setPadding(dp(7), dp(3), dp(7), dp(3));
                    badge.setBackground(rounded(RED, 4));
                    FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
                    bp.setMargins(dp(7), dp(7), 0, 0);
                    holder.art.addView(badge, bp);
                    holder.liveBadge = badge;
                }
                
                LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(gridTileWidth, gridTileHeight);
                tileParams.setMargins(0, 0, j < rowEnd - 1 ? dp(8) : 0, 0);
                row.addView(holder.tile, tileParams);
                
                // Track first focusable item
                if (firstGridItem == null) firstGridItem = holder.tile;
            }
        }
        
        currentGridPage++;
        isLoadingMore = false;
        
        // Load thumbnails for this page
        for (int i = startIndex; i < endIndex; i++) {
            Entry e = allGridItems.get(i);
            if (e.logo != null && !e.logo.isEmpty()) {
                thumbnailWorkers.execute(() -> {
                    Bitmap bitmap = readThumbnail(e.logo, gridTileWidth, gridArtHeight);
                    if (bitmap != null) {
                        thumbnailMemory.put(e.logo, bitmap);
                    }
                });
            }
        }
    }
    
    private void addContinueTiles(HorizontalScrollView hsv, LinearLayout row, ArrayList<Entry> entries) {
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            JSONObject progress = getPlaybackProgress(e);
            long position = 0, duration = 0;
            boolean completed = false;
            if (progress != null) {
                try {
                    position = progress.getLong("position");
                    duration = progress.getLong("duration");
                    completed = progress.getBoolean("completed");
                } catch (Exception ignored) {}
            }
            
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setBackground(rounded(0xff0d141d, 8));
            tile.setClipChildren(false);
            tile.setFocusable(true);
            tile.setClickable(true);
            tile.setTag(e);
            tile.setId(View.generateViewId());
            setFocusStyle(tile, 0xff0d141d, 8);
            tile.setOnClickListener(v -> play(e));
            
            FrameLayout art = new FrameLayout(this);
            art.setBackground(rounded(0xff131a23, 8));
            art.setClipToOutline(true);
            LinearLayout.LayoutParams artParams = new LinearLayout.LayoutParams(-1, dp(CARD_ART_H_DP));
            artParams.setMargins(dp(2), dp(2), dp(2), dp(2));
            tile.addView(art, artParams);
            
            TextView placeholder = text("", 25, WHITE);
            styleArtworkPlaceholder(placeholder, e);
            art.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
            
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setTag(e);
            art.addView(image, new FrameLayout.LayoutParams(-1, -1));
            placeholder.bringToFront();
            addFavoriteBadge(art, e);
            art.post(() -> loadThumbnail(image, e.logo, art.getWidth(), art.getHeight(), placeholder));
            
            // Progress bar overlay
            if (duration > 0) {
                View progressBg = new View(this);
                progressBg.setBackgroundColor(0x88000000);
                FrameLayout.LayoutParams bgParams = new FrameLayout.LayoutParams(-1, dp(4), Gravity.BOTTOM);
                art.addView(progressBg, bgParams);
                
                View progressFill = new View(this);
                progressFill.setBackgroundColor(RED);
                final float pct = Math.min(1f, (float) position / duration);
                FrameLayout.LayoutParams fillParams = new FrameLayout.LayoutParams(0, dp(4), Gravity.BOTTOM);
                art.addView(progressFill, fillParams);
                art.post(() -> {
                    FrameLayout.LayoutParams fp = (FrameLayout.LayoutParams) progressFill.getLayoutParams();
                    fp.width = Math.max(0, (int) (art.getWidth() * pct));
                    progressFill.setLayoutParams(fp);
                });
                
                // Progress text
                TextView progressText = new TextView(this);
                progressText.setText(completed ? "Completed" : String.format(Locale.ROOT, "%d%%", (int)(pct * 100)));
                progressText.setTextColor(WHITE);
                progressText.setTextSize(10);
                progressText.setTypeface(null, Typeface.BOLD);
                progressText.setPadding(dp(6), dp(2), dp(6), dp(2));
                progressText.setBackgroundColor(0xCC000000);
                FrameLayout.LayoutParams ptParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.RIGHT);
                ptParams.setMargins(0, 0, dp(6), dp(6));
                art.addView(progressText, ptParams);
                
                if (completed) {
                    TextView badge = text("✓ DONE", 10, WHITE);
                    badge.setTypeface(null, Typeface.BOLD);
                    badge.setPadding(dp(7), dp(3), dp(7), dp(3));
                    badge.setBackground(rounded(0xFF4CAF50, 4));
                    FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
                    bp.setMargins(dp(7), dp(7), 0, 0);
                    art.addView(badge, bp);
                }
            }
            
            TextView titleText = text(e.name, 13, WHITE);
            titleText.setMaxLines(1);
            titleText.setEllipsize(TextUtils.TruncateAt.END);
            titleText.setPadding(dp(5), dp(2), dp(5), dp(3));
            tile.addView(titleText);
            
            LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(dp(CARD_W_DP), dp(CARD_H_DP));
            tileParams.setMargins(0, 0, dp(CARD_GAP_DP), dp(4));
            row.addView(tile, tileParams);
            
            if (firstContentItem == null) firstContentItem = tile;
        }
        
        if (entries.size() < 6) {
            TextView more = text("See All  ›", 15, WHITE);
            more.setGravity(Gravity.CENTER);
            more.setFocusable(true);
            more.setClickable(true);
            setFocusStyle(more, 0xff131a23, 8);
            more.setOnClickListener(v -> showAllGrid("Continue Watching", entries));
            row.addView(more, new LinearLayout.LayoutParams(dp(CARD_W_DP), dp(CARD_H_DP)));
        }
        hsv.postDelayed(() -> loadVisibleThumbnails(hsv), 100);
    }
    private FrameLayout buildHero(Entry e){
        FrameLayout hero=new FrameLayout(this);
        hero.setBackground(rounded(0xff10151d,12));
        hero.setClipToOutline(true);
        hero.setClipChildren(true);
        LinearLayout.LayoutParams heroParams=new LinearLayout.LayoutParams(-1,dp(254));
        heroParams.setMargins(0,0,0,dp(4));
        hero.setLayoutParams(heroParams);

        JSONObject progress = (e != null) ? getPlaybackProgress(e) : null;
        long savedPosition = 0, savedDuration = 0;
        boolean completed = false;
        if (progress != null) {
            try {
                savedPosition = progress.getLong("position");
                savedDuration = progress.getLong("duration");
                completed = progress.getBoolean("completed");
            } catch (Exception ignored) {}
        }

        if (e != null) {
            TextView placeholder = text("", 44, WHITE);
            styleArtworkPlaceholder(placeholder, e);
            hero.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
        }
        if (e != null && hasArtwork(e.logo)) {
            ImageView image=new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setTag(e);
            hero.addView(image,new FrameLayout.LayoutParams(-1,-1));
            View fallback=(e != null && hero.getChildCount()>0) ? hero.getChildAt(0) : null;
            if(fallback instanceof TextView)fallback.bringToFront();
            hero.post(()->loadThumbnail(image,e.logo,hero.getWidth(),hero.getHeight(),fallback instanceof TextView?(TextView)fallback:null));
        }

        View shade=new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{0xFF05090F, 0xEE05090F, 0xAA05090F, 0x3305090F}));
        hero.addView(shade,new FrameLayout.LayoutParams(-1,-1));

        LinearLayout details=new LinearLayout(this);
        details.setGravity(Gravity.CENTER_VERTICAL);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(24),dp(12),dp(24),dp(12));
        FrameLayout.LayoutParams detailParams=new FrameLayout.LayoutParams(Math.min(dp(450),(int)(getResources().getDisplayMetrics().widthPixels*0.62f)),-1,Gravity.LEFT|Gravity.CENTER_VERTICAL);
        hero.addView(details,detailParams);

        String badgeText = "WELCOME";
        int badgeColor = RED;
        if (e != null) {
            if (completed) {
                badgeText = "✓  COMPLETED";
                badgeColor = 0xFF4CAF50;
            } else if (savedPosition > 0) {
                badgeText = "▶  CONTINUE WATCHING";
                badgeColor = 0xFFFFB300;
            } else {
                badgeText = e.type.equals("Live") ? "LIVE TV" : e.type.toUpperCase(Locale.ROOT);
                badgeColor = RED;
            }
        }
        TextView eyebrow=text(badgeText,13,badgeColor);
        eyebrow.setTypeface(null,Typeface.BOLD);
        details.addView(eyebrow);

        TextView title=text(e==null?"StreamShelf":e.name,34,WHITE);
        title.setTypeface(null,Typeface.BOLD);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(0,dp(2),0,dp(6));
        details.addView(title);

        if (e != null && savedPosition > 0 && savedDuration > 0) {
            LinearLayout progressContainer = new LinearLayout(this);
            progressContainer.setOrientation(LinearLayout.VERTICAL);
            progressContainer.setPadding(0, dp(4), 0, dp(8));

            LinearLayout progressRow = new LinearLayout(this);
            progressRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView progressLabel = text(completed ? "Completed" : "Resume", 13, 0xFFB0B0B0);
            progressLabel.setPadding(0, 0, dp(12), 0);
            progressRow.addView(progressLabel);

            View progressBg = new View(this);
            progressBg.setBackgroundColor(0xFF333333);
            LinearLayout.LayoutParams bgParams = new LinearLayout.LayoutParams(0, dp(6), 1);
            progressRow.addView(progressBg, bgParams);

            View progressFill = new View(this);
            progressFill.setBackgroundColor(badgeColor);
            float pct = Math.min(1f, (float) savedPosition / savedDuration);
            LinearLayout.LayoutParams fillParams = new LinearLayout.LayoutParams((int)(dp(200) * pct), dp(6));
            progressRow.addView(progressFill, fillParams);

            TextView progressPct = text(String.format(Locale.ROOT, "%d%%", (int)(pct * 100)), 13, WHITE);
            progressPct.setTypeface(null, Typeface.BOLD);
            progressPct.setPadding(dp(12), 0, 0, 0);
            progressRow.addView(progressPct);

            progressContainer.addView(progressRow);

            long remaining = savedDuration - savedPosition;
            String timeStr = completed ? "Finished" : formatTime(remaining) + " remaining";
            TextView timeText = text(timeStr, 12, 0xFF999999);
            progressContainer.addView(timeText);

            details.addView(progressContainer);
        } else {
            TextView summary=text(e==null?"Live TV, movies and series in one place.":e.group.isEmpty()?"Watch now on StreamShelf.":"Featured from "+e.group,15,0xffd0d2d7);
            summary.setMaxLines(2);
            summary.setEllipsize(TextUtils.TruncateAt.END);
            summary.setPadding(0,dp(4),0,dp(8));
            details.addView(summary);
        }

        LinearLayout actions=new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0,dp(16),0,0);
        details.addView(actions);

        String btnText = (e != null && savedPosition > 0) ? (completed ? "▶  Watch Again" : "▶  Resume") : "▶   Watch Now";
        Button watch=button(btnText);
        watch.setId(View.generateViewId());
        watch.setTag(e);
        watch.setOnClickListener(v->{if(e!=null)play(e);else sourceDialog();});
        actions.addView(watch,new LinearLayout.LayoutParams(dp(160),dp(50)));

        Button info=button("More Info");
        info.setId(View.generateViewId());
        info.setTag(e);
        info.setBackground(rounded(0xff262d38,7));
        info.setOnClickListener(v->{if(e!=null)showEntryInfo(e);else sourceDialog();});
        LinearLayout.LayoutParams infoParams=new LinearLayout.LayoutParams(dp(120),dp(50));
        infoParams.setMargins(dp(12),0,0,0);
        actions.addView(info,infoParams);

        if(firstContentItem==null)firstContentItem=watch;
        return hero;
    }
    private void appendHomeShelf(String title,String type,boolean live){ArrayList<Entry> shelf=new ArrayList<>();for(Entry e:entries)if(e.type.equals(type)&&(!type.equals("My List")||isFavorite(e)))shelf.add(e);if(shelf.isEmpty())return;
        if(type.equals("Live")){
            shelf.sort((a,b)->{boolean aK=isKannadaChannel(a),bK=isKannadaChannel(b);if(aK&&!bK)return -1;if(!aK&&bK)return 1;return 0;});
        }

        // Category icon mapping - use vector drawables
        int iconRes = 0;
        int iconColor = WHITE;
        switch(type) {
            case "Movies": iconRes = R.drawable.ic_movies; iconColor = 0xFFE50914; break;
            case "Series": iconRes = R.drawable.ic_series; iconColor = 0xFF9C27B0; break;
            case "Live": iconRes = R.drawable.ic_live_tv; iconColor = 0xFF4CAF50; break;
            case "My List": iconRes = R.drawable.ic_my_list; iconColor = 0xFFE91E63; break;
        }

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(0, dp(8), 0, dp(4));

        ImageView iconView = new ImageView(this);
        if (iconRes != 0) {
            iconView.setImageResource(iconRes);
            iconView.setColorFilter(iconColor);
        }
        iconView.setPadding(0, 0, dp(8), 0);
        iconView.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));
        heading.addView(iconView);

        TextView name = text(title + "  ›", 17, WHITE);name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setTypeface(null, Typeface.BOLD);
        heading.addView(name, new LinearLayout.LayoutParams(0, dp(30), 1));

        TextView count = text(shelf.size() + " titles", 13, 0xFF888888);
        count.setPadding(0, 0, dp(12), 0);
        heading.addView(count);

        TextView seeAll = text("See All  ›", 13, 0xffc7cad1);
        seeAll.setFocusable(true);
        seeAll.setClickable(true);
        seeAll.setGravity(Gravity.CENTER);
        seeAll.setPadding(dp(10), dp(5), dp(10), dp(5));
        setFocusStyle(seeAll, Color.TRANSPARENT, 6);
        seeAll.setTag(shelf);
        seeAll.setOnClickListener(v -> {
            ArrayList<Entry> allItems = (ArrayList<Entry>) v.getTag();
            showAllGrid(title, allItems);
        });
        heading.addView(seeAll);

        content.addView(heading);

        // Subtle divider
        View divider = new View(this);
        divider.setBackgroundColor(0xFF1A1A2E);
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(-1, dp(1));
        divParams.setMargins(0, dp(4), 0, dp(8));
        content.addView(divider);

        HorizontalScrollView hsv = createShelfScroll();
        LinearLayout row = (LinearLayout) hsv.getChildAt(0);
        content.addView(hsv);
        shelfRows.add(hsv);
        addHomeTiles(hsv, row, shelf, 0, live);}

    private HorizontalScrollView createShelfScroll(){
        ShelfScrollView hsv=new ShelfScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);hsv.setClipToPadding(false);hsv.setClipChildren(false);
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setClipChildren(false);row.setClipToPadding(false);hsv.addView(row);
        hsv.setOnScrollChangeListener((View v,int x,int y,int oldX,int oldY)->scheduleThumbnailScan(60));
        return hsv;
    }
    private void addHomeTiles(HorizontalScrollView hsv,LinearLayout row,ArrayList<Entry> shelf,int start,boolean live){int batchSize=start==0?6:12;int end=Math.min(start+batchSize,shelf.size());int width=dp(CARD_W_DP),height=dp(CARD_ART_H_DP);for(int i=start;i<end;i++){Entry e=shelf.get(i);LinearLayout tile=new LinearLayout(this);tile.setOrientation(LinearLayout.VERTICAL);tile.setBackground(rounded(0xff0d141d,8));tile.setClipChildren(false);tile.setFocusable(true);tile.setClickable(true);tile.setTag(e);tile.setId(View.generateViewId());setFocusStyle(tile,0xff0d141d,8);tile.setOnClickListener(v->play(e));FrameLayout art=new FrameLayout(this);art.setBackground(rounded(0xff131a23,8));art.setClipToOutline(true);LinearLayout.LayoutParams artParams=new LinearLayout.LayoutParams(-1,height);artParams.setMargins(dp(2),dp(2),dp(2),dp(2));tile.addView(art,artParams);TextView placeholder=text("",25,WHITE);styleArtworkPlaceholder(placeholder,e);art.addView(placeholder,new FrameLayout.LayoutParams(-1,-1));ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setAdjustViewBounds(false);image.setTag(e);art.addView(image,new FrameLayout.LayoutParams(-1,-1));placeholder.bringToFront();addFavoriteBadge(art,e);if(e.type.equals("Live")){TextView liveBadge=text("LIVE",10,WHITE);liveBadge.setTypeface(null,Typeface.BOLD);liveBadge.setPadding(dp(7),dp(3),dp(7),dp(3));liveBadge.setBackground(rounded(RED,4));FrameLayout.LayoutParams liveParams=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.LEFT);liveParams.setMargins(dp(7),dp(7),0,0);art.addView(liveBadge,liveParams);}TextView titleText=text(e.name,13,WHITE);titleText.setMaxLines(1);titleText.setEllipsize(android.text.TextUtils.TruncateAt.END);titleText.setPadding(dp(5),dp(2),dp(5),dp(3));tile.addView(titleText);LinearLayout.LayoutParams tileParams=new LinearLayout.LayoutParams(width,dp(CARD_H_DP));tileParams.setMargins(0,0,dp(CARD_GAP_DP),dp(4));row.addView(tile,tileParams);if(firstContentItem==null)firstContentItem=tile;}if(end<shelf.size()){TextView more=text("See All  ›",15,WHITE);more.setGravity(Gravity.CENTER);more.setFocusable(true);more.setClickable(true);setFocusStyle(more,0xff131a23,8);more.setTag(shelf);more.setOnClickListener(v->{ArrayList<Entry> allItems=(ArrayList<Entry>)v.getTag();if(allItems==null||allItems.isEmpty())return;String shelfTitle=live?"Live TV":allItems.get(0).type;showAllGrid(shelfTitle,allItems);});row.addView(more,new LinearLayout.LayoutParams(dp(CARD_W_DP),dp(CARD_H_DP)));}hsv.postDelayed(()->loadVisibleThumbnails(hsv),100);}
    private void wireMainFocus(){if(firstContentItem==null)return;TextView selected=currentCategoryTab();if(selected!=null){selected.setNextFocusRightId(firstContentItem.getId());firstContentItem.setNextFocusLeftId(selected.getId());}if(searchControl!=null)searchControl.setNextFocusDownId(firstContentItem.getId());}
    private static final String FAVORITE_BADGE = "favorite-badge";
    // One shared landscape card size for every Home shelf (Movies, Series,
    // Continue Watching and Live TV) so the dashboard stays on a single grid.
    private static final int CARD_W_DP = 144;
    private static final int CARD_ART_H_DP = 80;
    private static final int CARD_H_DP = 114;
    private static final int CARD_GAP_DP = 10;

    /** The Entry behind the focused view, walking through its card container. */
    private Entry focusedEntry() {
        View view = getCurrentFocus();
        while (view != null) {
            Object tag = view.getTag();
            if (tag instanceof Entry) return (Entry) tag;
            android.view.ViewParent parent = view.getParent();
            view = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    /** The Series behind the focused view, walking through its card container. */
    private Series focusedSeries() {
        View view = getCurrentFocus();
        while (view != null) {
            Object tag = view.getTag();
            if (tag instanceof Series) return (Series) tag;
            android.view.ViewParent parent = view.getParent();
            view = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    /**
     * My List is entry-based, so saving a show means saving its episodes. Doing
     * that as one action keeps the card's single "option" press meaningful.
     */
    private void toggleSeriesFavorite(Series series) {
        if (series == null || series.episodes.isEmpty()) return;
        boolean anySaved = false;
        for (Episode ep : series.episodes) {
            if (isFavorite(ep.entry)) { anySaved = true; break; }
        }
        boolean add = !anySaved;
        int changed = 0;
        for (Episode ep : series.episodes) {
            if (setFavorite(ep.entry, add, false)) changed++;
        }
        if (changed > 0) saveFavorites();
        String noun = changed == 1 ? "episode" : "episodes";
        Toast.makeText(this, add
                ? "Added " + changed + " " + noun + " to My List"
                : "Removed " + changed + " " + noun + " from My List",
                Toast.LENGTH_SHORT).show();
        if (!playing) {
            Episode resume = resumeEpisode(series);
            Entry resumeEntry = resume != null ? resume.entry : series.episodes.get(0).entry;
            if (gridOverlayView != null) refreshFocusedFavoriteBadge(resumeEntry);
            // Rebuild whichever shelf/tab is visible so the heart appears (or
            // disappears) right away, then put focus back on the same show.
            refreshRows();
            if (gridOverlayView == null) focusTileAfterRefresh(series);
        }
    }

    /** Small heart on the artwork so "saved to My List" is visible on the card. */
    private void addFavoriteBadge(FrameLayout art, Entry entry) {
        if (art == null) return;
        removeFavoriteBadges(art);
        if (entry == null || !isFavorite(entry)) return;
        TextView heart = text("\u2665", 13, 0xFFFF5A78);
        heart.setTag(FAVORITE_BADGE);
        heart.setPadding(dp(6), dp(1), dp(6), dp(2));
        heart.setBackground(rounded(0xCC000000, 6));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT);
        params.setMargins(0, dp(7), dp(7), 0);
        art.addView(heart, params);
    }

    private void removeFavoriteBadges(FrameLayout art) {
        if (art == null) return;
        for (int i = art.getChildCount() - 1; i >= 0; i--) {
            if (FAVORITE_BADGE.equals(art.getChildAt(i).getTag())) art.removeViewAt(i);
        }
    }

    /** Re-draw the heart on the focused card after a My List toggle. */
    private void refreshFocusedFavoriteBadge(Entry entry) {
        View focused = getCurrentFocus();
        if (!(focused instanceof LinearLayout)) return;
        LinearLayout tile = (LinearLayout) focused;
        if (tile.getChildCount() == 0) return;
        View first = tile.getChildAt(0);
        if (!(first instanceof FrameLayout)) return;
        FrameLayout art = (FrameLayout) first;
        addFavoriteBadge(art, entry);
    }

    private void showEntryInfo(Entry e){new AlertDialog.Builder(this).setTitle(e.name).setMessage(e.type+(e.group.isEmpty()?"":"\n"+e.group)).setPositiveButton("Watch Now",(d,w)->play(e)).setNegativeButton("Close",null).setNeutralButton(isFavorite(e)?"Remove from My List":"Add to My List",(d,w)->{toggleFavorite(e);}).show();}
    private void addShelfTiles(HorizontalScrollView hsv,LinearLayout row,ArrayList<Entry> shelf,int start,boolean portrait){
        int batchSize=start==0?8:12;int end=Math.min(start+batchSize,shelf.size());int width=portrait?dp(132):dp(188),artHeight=portrait?dp(164):dp(92);
        for(int i=start;i<end;i++){
            Entry e=shelf.get(i);LinearLayout tile=new LinearLayout(this);tile.setOrientation(LinearLayout.VERTICAL);tile.setBackground(rounded(PANEL,9));tile.setClipChildren(false);tile.setFocusable(true);tile.setClickable(true);tile.setTag(e);tile.setId(View.generateViewId());setFocusStyle(tile,PANEL,9);tile.setOnClickListener(v->play(e));
            if(firstContentItem==null)firstContentItem=tile;
            FrameLayout art=new FrameLayout(this);art.setBackground(rounded(0xff17191e,8));art.setClipToOutline(true);LinearLayout.LayoutParams artParams=new LinearLayout.LayoutParams(-1,artHeight);artParams.setMargins(dp(5),dp(5),dp(5),dp(4));tile.addView(art,artParams);
            TextView placeholder=text("",32,WHITE);styleArtworkPlaceholder(placeholder,e);art.addView(placeholder,new FrameLayout.LayoutParams(-1,-1));
            ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setAdjustViewBounds(false);image.setContentDescription(e.name+" artwork");image.setTag(e);art.addView(image,new FrameLayout.LayoutParams(-1,-1));
            placeholder.bringToFront();
            if(e.type.equals("Live")){TextView badge=text("LIVE",10,WHITE);badge.setTypeface(null,Typeface.BOLD);badge.setPadding(dp(7),dp(3),dp(7),dp(3));badge.setBackground(rounded(RED,4));FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.LEFT);bp.setMargins(dp(7),dp(7),0,0);art.addView(badge,bp);}
            addFavoriteBadge(art,e);
            TextView title=text(e.name,14,WHITE);title.setTypeface(null,Typeface.BOLD);title.setMaxLines(2);title.setEllipsize(android.text.TextUtils.TruncateAt.END);title.setPadding(dp(10),dp(4),dp(10),dp(8));tile.addView(title,new LinearLayout.LayoutParams(-1,-2));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(width,portrait?dp(222):dp(152));p.setMargins(0,0,dp(10),dp(8));row.addView(tile,p);
        }
        if(end<shelf.size()){
            LinearLayout more=new LinearLayout(this);more.setGravity(Gravity.CENTER);more.setOrientation(LinearLayout.VERTICAL);more.setBackground(rounded(0xff292b31,9));more.setFocusable(true);more.setClickable(true);setFocusStyle(more,0xff292b31,9);
            TextView arrow=text("›",42,RED);arrow.setGravity(Gravity.CENTER);more.addView(arrow);TextView label=text("LOAD MORE",12,WHITE);label.setTypeface(null,Typeface.BOLD);label.setGravity(Gravity.CENTER);more.addView(label);
            more.setOnClickListener(v->{row.removeView(more);int firstAdded=row.getChildCount();addShelfTiles(hsv,row,shelf,end,portrait);if(firstAdded<row.getChildCount())row.getChildAt(firstAdded).requestFocus();});LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(width,portrait?dp(222):dp(152));mp.setMargins(0,0,dp(10),dp(8));row.addView(more,mp);
        }
        hsv.postDelayed(()->loadVisibleThumbnails(hsv),100);
    }
    private void loadVisibleThumbnails(HorizontalScrollView shelf) {
        if (shelf.getVisibility() != View.VISIBLE || shelf.getChildCount() == 0
                || !(shelf.getChildAt(0) instanceof ViewGroup)) return;
        // Skip shelves that are entirely off-screen instead of walking them.
        if (!shelf.getGlobalVisibleRect(reusableRect)) return;
        ViewGroup row = (ViewGroup) shelf.getChildAt(0);
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (!(child instanceof LinearLayout)) continue;
            LinearLayout tile = (LinearLayout) child;
            if (tile.getChildCount() == 0 || !(tile.getChildAt(0) instanceof FrameLayout)) continue;
            FrameLayout art = (FrameLayout) tile.getChildAt(0);
            if (!art.getGlobalVisibleRect(reusableRect)) continue;
            TextView fallback = null;
            ImageView image = null;
            for (int j = 0; j < art.getChildCount(); j++) {
                View item = art.getChildAt(j);
                if (item instanceof TextView && fallback == null) fallback = (TextView) item;
                if (item instanceof ImageView) {
                    image = (ImageView) item;
                    break;
                }
            }
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) continue;
            Object tag = image.getTag();
            if (tag instanceof Entry) {
                Entry entry = (Entry) tag;
                loadThumbnail(image, entry.logo, image.getWidth(), image.getHeight(), fallback);
            }
        }
    }
    private void loadThumbnail(ImageView target,String url,int width,int height){
        loadThumbnail(target, url, width, height, null);
    }

    private void loadThumbnail(ImageView target, String url, int width, int height, TextView fallback) {
        if (url == null || url.trim().isEmpty()) {
            if (fallback != null) fallback.setVisibility(View.VISIBLE);
            return;
        }
        if (url.equals(target.getTag())) {
            if (fallback != null) fallback.setVisibility(target.getDrawable() == null ? View.VISIBLE : View.GONE);
            return;
        }
        if (fallback != null) fallback.setVisibility(View.VISIBLE);
        target.setTag(url);
        
        // Check memory cache first
        Bitmap cached = thumbnailMemory.get(url);
        if(cached != null) {
            target.setImageBitmap(cached);
            if (fallback != null) fallback.setVisibility(View.GONE);
            return;
        }
        
        // Check disk cache in background
        thumbnailWorkers.execute(() -> {
            Bitmap bitmap = readThumbnail(url, width, height);
            if(bitmap != null) {
                thumbnailMemory.put(url, bitmap);
                target.post(() -> {
                    if (url.equals(target.getTag())) {
                        // Fade in animation
                        if (target.getDrawable() == null) {
                            target.setImageBitmap(bitmap);
                        } else {
                            // Cross-fade animation
                            android.graphics.drawable.TransitionDrawable td = new android.graphics.drawable.TransitionDrawable(new android.graphics.drawable.Drawable[]{
                                target.getDrawable(),
                                new android.graphics.drawable.BitmapDrawable(getResources(), bitmap)
                            });
                            target.setImageDrawable(td);
                            td.startTransition(150);
                        }
                        if (fallback != null) fallback.setVisibility(View.GONE);
                    }
                });
            }
        });
    }
    private Bitmap readThumbnail(String url,int width,int height){
        HttpURLConnection connection=null;File temp=null;try{
            File dir=new File(getCacheDir(),"thumbnails");if(!dir.exists()&&!dir.mkdirs())return null;String key=sha256(url);File cached=new File(dir,key+".img");Bitmap bitmap=cached.isFile()?decodeThumbnail(cached,width,height):null;if(bitmap!=null)return bitmap;if(cached.exists())cached.delete();
            connection=(HttpURLConnection)new URL(url).openConnection();connection.setConnectTimeout(3500);connection.setReadTimeout(5000);connection.setInstanceFollowRedirects(true);connection.setRequestProperty("User-Agent","StreamShelf/1.1");connection.connect();int code=connection.getResponseCode();if(code<200||code>=300)return null;
            temp=new File(dir,key+"."+Thread.currentThread().getId()+".tmp");try(InputStream in=new BufferedInputStream(connection.getInputStream());FileOutputStream out=new FileOutputStream(temp)){byte[] buffer=new byte[8192];int count;long total=0;while((count=in.read(buffer))!=-1){total+=count;if(total>8L*1024*1024)throw new IOException("Artwork exceeds 8 MB");out.write(buffer,0,count);}}
            if(cached.exists()){temp.delete();}else if(!temp.renameTo(cached)){temp.delete();if(!cached.exists())return null;}trimThumbnailDiskCache(dir);return decodeThumbnail(cached,width,height);
        }catch(Exception ignored){if(temp!=null)temp.delete();return null;}finally{if(connection!=null)connection.disconnect();}
    }
    private Bitmap decodeThumbnail(File file,int width,int height){BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getAbsolutePath(),bounds);if(bounds.outWidth<=0||bounds.outHeight<=0)return null;BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;while(bounds.outWidth/(options.inSampleSize*2)>=width&&bounds.outHeight/(options.inSampleSize*2)>=height)options.inSampleSize*=2;options.inPreferredConfig=Bitmap.Config.RGB_565;return BitmapFactory.decodeFile(file.getAbsolutePath(),options);}
    private String sha256(String value)throws Exception{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    private void trimThumbnailDiskCache(File dir){File[] files=dir.listFiles((d,name)->name.endsWith(".img"));if(files==null)return;Arrays.sort(files,Comparator.comparingLong(File::lastModified));long size=0;for(File f:files)size+=f.length();int count=files.length;for(File f:files){if(count<=140&&size<=36L*1024*1024)break;long bytes=f.length();if(f.delete()){size-=bytes;count--;}}}
    private void animateContentIn(){
        if(content==null)return;
        content.animate().cancel();
        content.setAlpha(0f);
        content.setTranslationY(dp(12));
        content.animate().alpha(1f).translationY(0f)
            .setDuration(230)
            .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f))
            .start();
    }

    // ==================== LOADING / SPLASH ====================

    /** Animated three-dot loader used by the splash and playlist placeholders. */
    private class LoadingDotsView extends View {
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private android.animation.ValueAnimator animator;
        private float phase = 0f;
        private int dotColor = RED;

        LoadingDotsView(Context context) {
            super(context);
            dotPaint.setStyle(Paint.Style.FILL);
            dotPaint.setColor(dotColor);
        }

        void setDotColor(int color) {
            dotColor = color;
            dotPaint.setColor(color);
            invalidate();
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (animator != null) return;
            animator = android.animation.ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(1000);
            animator.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            animator.setInterpolator(new android.view.animation.LinearInterpolator());
            animator.addUpdateListener(a -> {
                phase = (float) a.getAnimatedValue() * (float) (Math.PI * 2);
                invalidate();
            });
            animator.start();
        }

        @Override protected void onDetachedFromWindow() {
            if (animator != null) { animator.cancel(); animator = null; }
            super.onDetachedFromWindow();
        }

        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(resolveSize(dp(64), widthMeasureSpec),
                    resolveSize(dp(22), heightMeasureSpec));
        }

        @Override protected void onDraw(Canvas canvas) {
            float radius = dp(3.4f);
            float gap = dp(12f);
            float centerY = getHeight() / 2f;
            float startX = getWidth() / 2f - gap;
            for (int i = 0; i < 3; i++) {
                float wave = (float) Math.sin(phase - i * 0.9f);
                float scale = 0.62f + 0.55f * ((wave + 1f) / 2f);
                dotPaint.setColor(dotColor);
                dotPaint.setAlpha(i == 1 ? 255 : 185);
                canvas.drawCircle(startX + i * gap, centerY, radius * scale, dotPaint);
            }
        }
    }

    /** Focusable "Loading…" placeholder with the animated dots. */
    private View loadingBlock(String message) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setGravity(Gravity.CENTER_HORIZONTAL);
        block.setPadding(0, dp(28), 0, dp(16));
        block.setFocusable(true);
        block.setId(View.generateViewId());
        TextView label = text(message, 18, MUTED);
        label.setGravity(Gravity.CENTER);
        block.addView(label, new LinearLayout.LayoutParams(-2, -2));
        LoadingDotsView dots = new LoadingDotsView(this);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(64), dp(22));
        dotParams.setMargins(0, dp(12), 0, 0);
        block.addView(dots, dotParams);
        return block;
    }

    private void showSplash() {
        if (splashView != null) return;
        splashShownAt = SystemClock.uptimeMillis();
        splashDismissed = false;
        splashDismissPending = false;

        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xff161a23, 0xff05080c}));
        // Swallow touches while the splash is up; the remote still works on the
        // screen underneath once the overlay fades.
        overlay.setClickable(true);

        LinearLayout stack = new LinearLayout(this);
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.setGravity(Gravity.CENTER_HORIZONTAL);

        final TextView mark = text("▶", 40, WHITE);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(rounded(RED, 22));
        stack.addView(mark, new LinearLayout.LayoutParams(dp(92), dp(92)));

        TextView brand = text("StreamShelf", 30, WHITE);
        brand.setTypeface(null, Typeface.BOLD);
        brand.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(-2, -2);
        brandParams.setMargins(0, dp(20), 0, 0);
        stack.addView(brand, brandParams);

        TextView tagline = text("Live TV  ·  Movies  ·  Series", 14, MUTED);
        tagline.setGravity(Gravity.CENTER);
        tagline.setPadding(0, dp(6), 0, 0);
        stack.addView(tagline);

        LoadingDotsView dots = new LoadingDotsView(this);
        dots.setDotColor(RED);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(64), dp(22));
        dotParams.setMargins(0, dp(24), 0, 0);
        stack.addView(dots, dotParams);

        overlay.addView(stack, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        addContentView(overlay, new FrameLayout.LayoutParams(-1, -1));
        splashView = overlay;

        splashPulse = android.animation.ValueAnimator.ofFloat(1f, 1.08f);
        splashPulse.setDuration(860);
        splashPulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        splashPulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        splashPulse.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        splashPulse.addUpdateListener(a -> {
            float scale = (float) a.getAnimatedValue();
            mark.setScaleX(scale);
            mark.setScaleY(scale);
        });
        splashPulse.start();

        // Never let a stuck request pin the splash forever.
        if (handler != null) handler.postDelayed(this::dismissSplash, SPLASH_TIMEOUT_MS);
    }

    private void dismissSplash() {
        if (splashView == null || splashDismissPending) return;
        long elapsed = SystemClock.uptimeMillis() - splashShownAt;
        if (elapsed < SPLASH_MIN_MS) {
            splashDismissPending = true;
            if (handler != null) {
                handler.postDelayed(() -> {
                    splashDismissPending = false;
                    dismissSplash();
                }, SPLASH_MIN_MS - elapsed);
            }
            return;
        }
        dismissSplashNow();
    }

    private void dismissSplashNow() {
        if (splashView == null) return;
        final View view = splashView;
        splashView = null;
        splashDismissed = true;
        splashDismissPending = false;
        if (splashPulse != null) {
            splashPulse.cancel();
            splashPulse = null;
        }
        view.animate().alpha(0f).setDuration(300)
            .setInterpolator(new android.view.animation.AccelerateInterpolator())
            .withEndAction(() -> {
                ViewParent parent = view.getParent();
                if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(view);
            })
            .start();
    }

    // ==================== SMOOTH NAVIGATION ====================

    /** ScrollView that leaves smooth, Netflix-style centring to the focus listener. */
    private class SmoothScrollView extends ScrollView {
        SmoothScrollView(Context context) { super(context); }
        @Override public boolean requestChildRectangleOnScreen(View child, Rect rectangle, boolean immediate) {
            return true; // handled by scrollMainToFocused()
        }
    }

    /** Horizontal shelf that does not snap-jump when a card receives focus. */
    private class ShelfScrollView extends HorizontalScrollView {
        ShelfScrollView(Context context) { super(context); }
        @Override public boolean requestChildRectangleOnScreen(View child, Rect rectangle, boolean immediate) {
            return true; // handled by scrollShelfToFocused()
        }
    }

    /** Keep the focused card centred in its shelf with a smooth animation. */
    private void scrollShelfToFocused(View focused) {
        if (focused == null || shelfRows.isEmpty()) return;
        for (HorizontalScrollView shelf : shelfRows) {
            if (shelf == null || !isInsideView(focused, shelf)) continue;
            if (shelf.getChildCount() == 0 || !(shelf.getChildAt(0) instanceof ViewGroup)) return;
            final ViewGroup row = (ViewGroup) shelf.getChildAt(0);
            View child = focused;
            while (child != null && child.getParent() != row) {
                ViewParent parent = child.getParent();
                if (!(parent instanceof View)) return;
                child = (View) parent;
            }
            if (child == null) return;
            final View target = child;
            shelf.post(() -> {
                int shelfWidth = shelf.getWidth();
                int targetWidth = target.getWidth();
                if (shelfWidth <= 0 || targetWidth <= 0) return;
                int desired = target.getLeft() - (shelfWidth - targetWidth) / 2;
                int max = Math.max(0, row.getWidth() - shelfWidth);
                // Snap to whole cards. Centring alone can leave the first visible
                // card cut in half at the left edge of the shelf.
                int step = targetWidth + dp(CARD_GAP_DP);
                if (step > 0) {
                    desired = (desired / step) * step;
                    max = (max / step) * step;
                }
                desired = Math.max(0, Math.min(max, desired));
                if (Math.abs(shelf.getScrollX() - desired) > dp(4)) {
                    shelf.smoothScrollTo(desired, 0);
                }
            });
            return;
        }
    }

    /** Keep the focused shelf comfortably inside the vertical viewport. */
    private void scrollMainToFocused(View focused) {
        if (focused == null || content == null || mainScroll == null) return;
        if (!isInsideView(focused, content)) return;
        View block = focused;
        while (block != null && block.getParent() != content) {
            ViewParent parent = block.getParent();
            if (!(parent instanceof View)) return;
            block = (View) parent;
        }
        if (block == null) return;
        final View target = block;
        mainScroll.post(() -> {
            int viewport = mainScroll.getHeight();
            if (viewport <= 0) return;
            int scrollY = mainScroll.getScrollY();
            int top = target.getTop();
            int bottom = target.getBottom();
            int margin = dp(14);
            int desired = scrollY;
            if (top - margin < scrollY) {
                desired = Math.max(0, top - margin);
            } else if (bottom + margin > scrollY + viewport) {
                desired = Math.max(0, bottom + margin - viewport);
            }
            if (desired != scrollY) mainScroll.smoothScrollTo(0, desired);
        });
    }

    /** Coalesce thumbnail work triggered by rapid scroll events. */
    private void scheduleThumbnailScan(long delayMs) {
        if (handler == null) return;
        if (thumbnailScanRunnable != null) handler.removeCallbacks(thumbnailScanRunnable);
        thumbnailScanRunnable = () -> {
            thumbnailScanRunnable = null;
            for (HorizontalScrollView shelf : shelfRows) loadVisibleThumbnails(shelf);
        };
        handler.postDelayed(thumbnailScanRunnable, Math.max(16, delayMs));
    }
    private void updateCategoryTabs(){for(TextView tab:categoryTabs){boolean selected=category.equals(String.valueOf(tab.getTag()));tab.setTextColor(selected?WHITE:0xffd0d2d7);tab.setTypeface(null,selected?Typeface.BOLD:Typeface.NORMAL);tab.setBackground(rounded(selected?0xffa90d19:Color.TRANSPARENT,7));}}
    private boolean isInsideView(View child, ViewGroup parent){for(View view=child;view!=null;){if(view==parent)return true;android.view.ViewParent next=view.getParent();view=next instanceof View?(View)next:null;}return false;}
    private void setSidebarExpanded(boolean expanded){
        if(sidebar==null)return;
        sidebarExpanded=expanded;
        ViewGroup.LayoutParams params=sidebar.getLayoutParams();
        params.width=dp(expanded?186:64);
        sidebar.setLayoutParams(params);
        sidebarBrand.setGravity(expanded?Gravity.CENTER_VERTICAL:Gravity.CENTER);
        sidebarBrand.setPadding(dp(expanded?14:4),0,0,0);
        brandWordmark.setVisibility(expanded?View.VISIBLE:View.GONE);
        brandProduct.setVisibility(expanded?View.VISIBLE:View.GONE);
        sourceLabel.setVisibility(expanded?View.VISIBLE:View.GONE);
        for(TextView tab:categoryTabs){
            String name=String.valueOf(tab.getTag());
            String icon="Home".equals(name)?"⌂":"Movies".equals(name)||"Live TV".equals(name)?"▣":"Series".equals(name)?"▤":"My List".equals(name)?"♡":"⚙";
            tab.setText(expanded?icon+"   "+name:icon);
            tab.setTextSize(expanded?18:21);
            tab.setSingleLine(true);
            tab.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tab.setGravity(expanded?Gravity.CENTER_VERTICAL:Gravity.CENTER);
            tab.setPadding(dp(expanded?14:5),0,dp(expanded?7:5),0);
        }
    }
    private void setFocusStyle(View view,int normalColor,int radius){
        // Check if this is a menu/sidebar item (transparent background) - don't show white border
        final boolean isMenuItem = (normalColor == Color.TRANSPARENT);
        
        view.setOnFocusChangeListener((v,focused)->{
            GradientDrawable bg=rounded(focused?0xff292f38:normalColor,radius);
            if(focused){
                bg.setStroke(dp(3), RED);
                // Use View elevation + outline for glow effect instead of GradientDrawable shadow
                v.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(radius));
                    }
                });
                v.setClipToOutline(true);
                v.setElevation(dp(12));
            } else {
                // Don't show white border for menu items or content cards - use transparent stroke
                if (!isMenuItem) {
                    bg.setStroke(dp(1), 0xFF1A1A2E); // Subtle dark border matching divider color
                } else {
                    bg.setStroke(dp(1), 0x00FFFFFF); // Transparent stroke
                }
                v.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
                v.setElevation(0);
            }
            v.setBackground(bg);
            v.animate().cancel();
            if(focused){
                // Hardware layer only while the card is transforming. Leaving it
                // on permanently cost a full-size offscreen buffer per card.
                v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                float scale = isMenuItem ? 1.05f : 1.08f;
                v.animate()
                    .scaleX(scale).scaleY(scale)
                    .translationZ(dp(12))
                    .setDuration(190)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.12f))
                    .start();
            } else {
                final View animated = v;
                v.animate()
                    .scaleX(1f).scaleY(1f)
                    .translationZ(0)
                    .setDuration(190)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                    .withEndAction(() -> {
                        if (!animated.isFocused()) animated.setLayerType(View.LAYER_TYPE_NONE, null);
                    })
                    .start();
            }
        });
    }
    private TextView currentCategoryTab(){for(TextView tab:categoryTabs)if(category.equals(String.valueOf(tab.getTag())))return tab;return categoryTabs.isEmpty()?sourceLabel:categoryTabs.get(0);}
    private void requestInitialFocus(){
        // On the See All grid there is no nav rail: the fallback has to land on
        // the grid itself, or every D-pad key is swallowed with nothing focused.
        if (gridOverlayView != null) {
            View target = firstGridItem != null ? firstGridItem : gridBackButton;
            if (target != null) target.requestFocus();
            return;
        }
        if(nav==null)return;nav.post(()->{if(getCurrentFocus()==null){TextView target=currentCategoryTab();if(target!=null)target.requestFocus();}});
    }
    private void focusFirstContent(){
        if (gridOverlayView != null) {
            // Only step in when nothing is focused. Toggling My List refreshes
            // the hidden home screen and calls this; jumping back to the first
            // tile made the remote appear to lose the item that was just saved.
            if (getCurrentFocus() == null && firstGridItem != null) firstGridItem.requestFocus();
            return;
        }
        content.post(()->{if(firstContentItem!=null)firstContentItem.requestFocus();else if(searchControl!=null)searchControl.requestFocus();});
    }

    /** Find the freshly rebuilt card for a saved item/show so focus is kept. */
    private View findTileByTag(ViewGroup parent, Object tag) {
        if (parent == null) return null;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child.getTag() == tag && child.isFocusable()) return child;
            if (child instanceof ViewGroup) {
                View found = findTileByTag((ViewGroup) child, tag);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void focusTileAfterRefresh(Object tag) {
        if (content == null) return;
        content.post(() -> {
            if (playing || gridOverlayView != null) return;
            View tile = findTileByTag(content, tag);
            if (tile != null && tile.isShown()) tile.requestFocus();
            else focusFirstContent();
        });
    }
    @Override public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
            int key = event.getKeyCode();

            if (playing) {
                // Back key: dismiss error panel, then hide controls, then exit
                if (key == android.view.KeyEvent.KEYCODE_BACK) {
                    if (playerErrorPanel != null && playerErrorPanel.getVisibility() == View.VISIBLE) {
                        playerErrorPanel.setVisibility(View.GONE);
                        showControls(true);
                        return true;
                    }
                    if (controlsVisible) {
                        showControls(false);
                        return true;
                    } else {
                        onBackPressed();
                        return true;
                    }
                }

                // While watching, MENU/INFO saves the playing movie, episode or
                // channel to My List. It used to fall through to "show controls",
                // so the remote's option button appeared to do nothing here.
                if (key == android.view.KeyEvent.KEYCODE_MENU
                        || key == android.view.KeyEvent.KEYCODE_INFO) {
                    // Repeats from a held button must not add then remove again.
                    if (event.getRepeatCount() == 0 && currentEntry != null) toggleFavorite(currentEntry);
                    showControls(true);
                    return true;
                }

                // Media keys
                if (player != null && (key == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                        || key == android.view.KeyEvent.KEYCODE_MEDIA_PLAY
                        || key == android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)) {
                    if (key == android.view.KeyEvent.KEYCODE_MEDIA_PLAY) player.play();
                    else if (key == android.view.KeyEvent.KEYCODE_MEDIA_PAUSE) player.pause();
                    else if (player.isPlaying()) player.pause();
                    else player.play();
                    return true;
                }

                if (key == android.view.KeyEvent.KEYCODE_MEDIA_REWIND
                        || key == android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                    seekRelative(key == android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ? 10000 : -10000);
                    return true;
                }

                // Dedicated channel keys still zap live TV while the on-screen
                // controls are visible, so Up/Down can navigate every control.
                if ((key == android.view.KeyEvent.KEYCODE_CHANNEL_UP
                        || key == android.view.KeyEvent.KEYCODE_CHANNEL_DOWN)
                        && currentEntry != null && "Live".equals(currentEntry.type)) {
                    switchChannel(key == android.view.KeyEvent.KEYCODE_CHANNEL_DOWN);
                    return true;
                }

                // D-pad navigation for custom player controls
                if ((key == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                        || key == android.view.KeyEvent.KEYCODE_ENTER
                        || key == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)
                        && event.getRepeatCount() == 0) {
                    if (!controlsVisible) {
                        showControls(true);
                    } else {
                        View focused = getCurrentFocus();
                        if (focused == progressBar && player != null) {
                            // OK on the timeline is play/pause, like most TV
                            // players, instead of a click the SeekBar ignores.
                            togglePlayPause();
                        } else if (focused != null && focused.isShown() && focused.isClickable()) {
                            focused.performClick();
                        } else if (centerPlayPauseBtn != null) {
                            // Focus can be dropped when the controls finish hiding;
                            // give the remote something to click again.
                            centerPlayPauseBtn.requestFocus();
                        }
                        scheduleHideControls();
                    }
                    return true;
                }

                // D-pad Up/Down. Live TV changes channel with Up/Down whenever the
                // focus is on the video (or the controls are hidden), which is the
                // behaviour remote users expect. Once focus is on a player control,
                // Up/Down moves between the control rows instead.
                if (key == android.view.KeyEvent.KEYCODE_DPAD_UP || key == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                    // The error panel only has two buttons; keep the remote on them
                    // instead of switching live channels behind the dialog.
                    if (playerErrorPanel != null && playerErrorPanel.getVisibility() == View.VISIBLE) {
                        if (getCurrentFocus() == retryPlaybackButton) browseBtn.requestFocus();
                        else retryPlaybackButton.requestFocus();
                        return true;
                    }
                    boolean live = currentEntry != null && "Live".equals(currentEntry.type);
                    boolean onControl = isPlayerControlFocused(getCurrentFocus());
                    if (live && (!controlsVisible || !onControl)) {
                        switchChannel(key == android.view.KeyEvent.KEYCODE_DPAD_DOWN);
                        return true;
                    }
                    if (!controlsVisible) {
                        showControls(true);
                        return true;
                    }
                    handleVerticalNavigation(key == android.view.KeyEvent.KEYCODE_DPAD_DOWN);
                    scheduleHideControls();
                    return true;
                }

                // D-pad Left/Right: seek when timeline focused, otherwise navigate between controls
                if (key == android.view.KeyEvent.KEYCODE_DPAD_LEFT || key == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                    boolean forward = key == android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
                    if (!controlsVisible) {
                        // With the controls hidden, treat Left/Right as a direct
                        // 10-second skip for movies/episodes. It used to only
                        // reveal the controls, so Forward looked broken.
                        if (currentEntry != null && !"Live".equals(currentEntry.type)) {
                            seekRelative(forward ? 10000 : -10000);
                            // Keep focus on the timeline, not the center button,
                            // so tapping Right again keeps skipping instead of
                            // only moving the highlight to Forward.
                            if (progressBar != null && progressBar.isShown()) {
                                progressBar.post(progressBar::requestFocus);
                            }
                        } else {
                            showControls(true);
                        }
                        return true;
                    }
                    View focused = getCurrentFocus();
                    // Handle error panel navigation
                    if (playerErrorPanel != null && playerErrorPanel.getVisibility() == View.VISIBLE) {
                        if (focused == retryPlaybackButton) {
                            browseBtn.requestFocus();
                        } else if (focused == browseBtn) {
                            retryPlaybackButton.requestFocus();
                        }
                        return true;
                    }
                    if (focused == progressBar) {
                        // SeekBar's own key handling only moves the thumb and never
                        // calls seekTo(), so D-pad seeking on the timeline did
                        // nothing. Seek 10s per press like the on-screen buttons.
                        seekRelative(key == android.view.KeyEvent.KEYCODE_DPAD_RIGHT ? 10000 : -10000);
                        return true;
                    }
                    handleHorizontalNavigation(key == android.view.KeyEvent.KEYCODE_DPAD_RIGHT);
                    scheduleHideControls();
                    return true;
                }

                // Any other key while playing: show controls
                showControls(true);
                return true;
            }

            // BACK must dismiss the See All grid. The overlay's OnKeyListener
            // only receives keys while the overlay itself is focused, which
            // never happens with FOCUS_AFTER_DESCENDANTS, so Back fell through
            // to super.onBackPressed() and closed the whole app instead.
            if (!playing && key == android.view.KeyEvent.KEYCODE_BACK && gridOverlayView != null) {
                closeAllGrid();
                return true;
            }
            // Non-playing keys (existing logic)
            if (!playing && key == android.view.KeyEvent.KEYCODE_SEARCH) {
                searchDialog();
                return true;
            }
            if (!playing && (key == android.view.KeyEvent.KEYCODE_MENU
                    || key == android.view.KeyEvent.KEYCODE_INFO)) {
                // A held button sends repeated DOWN events; toggling on each one
                // would add and then instantly remove the title.
                if (event.getRepeatCount() > 0) return true;
                // Only the card itself carries the tag, and the remote can land
                // focus on any child view of it, so walk outwards instead of
                // reading one level. Getting this wrong sent every other press
                // to the playlist dialog instead of saving the title.
                Series series = focusedSeries();
                if (series != null) {
                    toggleSeriesFavorite(series);
                    return true;
                }
                Entry entry = focusedEntry();
                if (entry != null) {
                    toggleFavorite(entry);
                    return true;
                }
                sourceDialog();
                return true;
            }
            if (!playing && key == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && categoryTabs.contains(getCurrentFocus())) {
                focusFirstContent();
                return true;
            }
            if (!playing && key == android.view.KeyEvent.KEYCODE_DPAD_LEFT && getCurrentFocus() == firstContentItem) {
                TextView target = currentCategoryTab();
                if (target != null) {
                    target.requestFocus();
                    return true;
                }
            }
            // Handle DPAD_CENTER/ENTER for clickable views in non-playing state (e.g., "See All" buttons)
            if (!playing && (key == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                    || key == android.view.KeyEvent.KEYCODE_ENTER
                    || key == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)
                    && event.getRepeatCount() == 0) {
                View focused = getCurrentFocus();
                if (focused != null && focused.isClickable()) {
                    focused.performClick();
                    return true;
                }
            }
            if (!playing && getCurrentFocus() == null && event.getRepeatCount() == 0
                    && (key == android.view.KeyEvent.KEYCODE_DPAD_UP
                    || key == android.view.KeyEvent.KEYCODE_DPAD_DOWN
                    || key == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                    || key == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                    || key == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                    || key == android.view.KeyEvent.KEYCODE_ENTER
                    || key == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                requestInitialFocus();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void handleVerticalNavigation(boolean down) {
        View focused = getCurrentFocus();
        if (focused == null) {
            // Default to center play/pause
            if (centerPlayPauseBtn != null) centerPlayPauseBtn.requestFocus();
            return;
        }

        // Define focus groups vertically
        // Group 1: Top bar (back, cc, settings)
        // Group 2: Timeline (progress bar)
        // Group 3: Bottom controls
        // Group 4: Center play/pause (when visible)

        boolean isTopBar = focused == backBtn || focused == ccBtn || focused == settingsBtn;
        boolean isTimeline = focused == progressBar;
        boolean isBottom = isBottomControl(focused);
        boolean isCenter = focused == centerPlayPauseBtn;

        if (down) {
            if (isTopBar) {
                progressBar.requestFocus();
            } else if (isTimeline) {
                if (bottomControls.getVisibility() == View.VISIBLE && controlOrder.length > 0) {
                    controlOrder[0].requestFocus(); // First bottom control
                }
            } else if (isBottom) {
                // Stay on bottom, or go to center if visible
                if (centerControls.getVisibility() == View.VISIBLE) {
                    centerPlayPauseBtn.requestFocus();
                }
            } else if (isCenter) {
                // From center, go to bottom
                if (bottomControls.getVisibility() == View.VISIBLE && controlOrder.length > 0) {
                    controlOrder[0].requestFocus();
                }
            }
        } else { // UP
            if (isBottom) {
                progressBar.requestFocus();
            } else if (isTimeline) {
                // Go to top bar - focus back button
                backBtn.requestFocus();
            } else if (isTopBar) {
                // From top, go to center if visible, else bottom
                if (centerControls.getVisibility() == View.VISIBLE) {
                    centerPlayPauseBtn.requestFocus();
                } else if (bottomControls.getVisibility() == View.VISIBLE && controlOrder.length > 0) {
                    controlOrder[0].requestFocus();
                }
            } else if (isCenter) {
                progressBar.requestFocus();
            }
        }
    }

    private boolean isBottomControl(View v) {
        if (v == null || controlOrder == null) return false;
        for (View ctrl : controlOrder) {
            if (ctrl == v) return true;
        }
        return false;
    }

    /** True when focus is on the top bar, timeline or bottom control row. */
    private boolean isPlayerControlFocused(View v) {
        if (v == null) return false;
        return v == backBtn || v == ccBtn || v == settingsBtn || v == progressBar || isBottomControl(v);
    }

    private void handleHorizontalNavigation(boolean right) {
        View focused = getCurrentFocus();
        if (focused == null) {
            if (centerControls.getVisibility() == View.VISIBLE) {
                centerPlayPauseBtn.requestFocus();
            } else if (controlOrder.length > 0) {
                controlOrder[0].requestFocus();
            }
            return;
        }

        // If on bottom controls, navigate between them
        if (isBottomControl(focused)) {
            int idx = -1;
            for (int i = 0; i < controlOrder.length; i++) {
                if (controlOrder[i] == focused) {
                    idx = i;
                    break;
                }
            }
            if (idx >= 0) {
                int newIdx = right ? Math.min(idx + 1, controlOrder.length - 1) : Math.max(idx - 1, 0);
                controlOrder[newIdx].requestFocus();
            }
        } else if (focused == backBtn) {
            if (right && ccBtn != null) ccBtn.requestFocus();
        } else if (focused == ccBtn) {
            if (right && settingsBtn != null) settingsBtn.requestFocus();
            else if (!right) backBtn.requestFocus();
        } else if (focused == settingsBtn) {
            if (!right) ccBtn.requestFocus();
        } else if (focused == centerPlayPauseBtn) {
            // From the big center button, Right should land on Forward and Left
            // on Rewind, so seeking is two presses away instead of four.
            if (right && forwardBtn != null) forwardBtn.requestFocus();
            else if (!right && rewindBtn != null) rewindBtn.requestFocus();
            else if (controlOrder.length > 0) controlOrder[0].requestFocus();
        }
    }
    private void loadPlaylist(){if(sources.isEmpty())return;final Source s=sources.get(activeSource);final int request=playlistRequestId.incrementAndGet();playlistError=null;status.setText("Loading "+s.name+"…");entries.clear();refreshRows();playlistWorkers.execute(()->{ArrayList<Entry> parsed=new ArrayList<>();String error=null;HttpURLConnection c=null;try{URL u=new URL(s.url);c=(HttpURLConnection)u.openConnection();c.setConnectTimeout(12000);c.setReadTimeout(18000);c.setRequestProperty("User-Agent","StreamShelf/1.2");c.connect();if(c.getResponseCode()<200||c.getResponseCode()>=300)throw new IOException("HTTP "+c.getResponseCode());try(BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8),32768)){String line,info=null;while((line=r.readLine())!=null){line=line.trim();if(line.startsWith("#EXTINF")){info=line;}else if(!line.isEmpty()&&!line.startsWith("#")&&info!=null){String name=afterComma(info);String group=attr(info,GROUP_TITLE_ATTR);String logo=attr(info,TVG_LOGO_ATTR);String type=classify(group,name);parsed.add(new Entry(name,line,group,logo,type));info=null;}}}}catch(Exception e){error=e.getMessage()==null?"Could not load playlist":e.getMessage();}finally{if(c!=null)c.disconnect();}final String err=error;runOnUiThread(()->{if(request!=playlistRequestId.get()||isFinishing()||isDestroyed())return;entries.clear();entries.addAll(parsed);seriesIndexCache=null;seriesIndexCacheSize=-1;playlistError=err;status.setText(err==null?parsed.size()+" items loaded":"Playlist error: "+err);if(parsed.isEmpty()&&err==null)playlistError="No playable entries found. Check the M3U format and URLs.";dismissSplash();refreshRows();});});}
    private String afterComma(String s){int i=s.indexOf(',');String v=i>=0?s.substring(i+1).trim():"Untitled";return v.isEmpty()?"Untitled":v;}
    private String attr(String s,java.util.regex.Pattern pattern){java.util.regex.Matcher m=pattern.matcher(s);return m.find()?m.group(1):"";}
    private String classify(String g,String n){String s=(g+" "+n).toLowerCase(Locale.ROOT);if(SERIES_HINT.matcher(s).find())return "Series";if(MOVIE_HINT.matcher(s).find())return "Movies";return "Live";}
    private void sourceDialog(){LinearLayout box=new LinearLayout(this);box.setPadding(dp(20),dp(10),dp(20),0);box.setOrientation(LinearLayout.VERTICAL);EditText name=new EditText(this);name.setHint("Playlist name");box.addView(name);EditText url=new EditText(this);url.setHint("M3U URL (http:// or https://)");url.setSingleLine(true);url.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);box.addView(url);String[] opts=sources.stream().map(x->x.name).toArray(String[]::new);if(opts.length>0){Spinner sp=new Spinner(this);ArrayList<String> labels=new ArrayList<>();labels.add("Save as new playlist");labels.addAll(Arrays.asList(opts));sp.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));box.addView(sp);new AlertDialog.Builder(this).setTitle("Playlist sources").setView(box).setPositiveButton("Save",(d,w)->{String n=name.getText().toString().trim(),u=url.getText().toString().trim();if(!u.isEmpty()){if(n.isEmpty())n="Playlist "+(sources.size()+1);if(sp.getSelectedItemPosition()>0){activeSource=sp.getSelectedItemPosition()-1;}else sources.add(new Source(n,u));saveSources();loadPlaylist();}}).setNeutralButton("Switch / remove",(d,w)->manageSources()).setNegativeButton("Cancel",null).show();}else new AlertDialog.Builder(this).setTitle("Add playlist source").setView(box).setPositiveButton("Add",(d,w)->{String n=name.getText().toString().trim(),u=url.getText().toString().trim();if(!u.isEmpty()){sources.add(new Source(n.isEmpty()?"Playlist 1":n,u));activeSource=sources.size()-1;saveSources();loadPlaylist();}}).setNegativeButton("Cancel",null).show();}
    private void manageSources(){if(sources.isEmpty())return;String[] names=sources.stream().map(x->x.name).toArray(String[]::new);new AlertDialog.Builder(this).setTitle("Switch playlist").setItems(names,(d,w)->{activeSource=w;loadPlaylist();new AlertDialog.Builder(this).setMessage("Remove "+sources.get(w).name+"?").setPositiveButton("Remove",(a,b)->{sources.remove(w);activeSource=Math.max(0,Math.min(activeSource,sources.size()-1));saveSources();entries.clear();if(sources.isEmpty()){showWelcome();sourceLabel.setText("Choose a playlist source");}else loadPlaylist();}).setNegativeButton("Keep",null).show();}).setNegativeButton("Close",null).show();}
    private void searchDialog() {
        EditText query = new EditText(this);
        query.setSingleLine(true);
        query.setHint("Search this playlist");
        new AlertDialog.Builder(this)
                .setTitle("Search")
                .setView(query)
                .setPositiveButton("Search", (dialog, which) -> {
                    String needle = query.getText().toString().trim().toLowerCase(Locale.ROOT);
                    ArrayList<Entry> matches = new ArrayList<>();
                    for (Entry entry : entries) {
                        if (entry != null && entry.name != null
                                && entry.name.toLowerCase(Locale.ROOT).contains(needle)) {
                            matches.add(entry);
                        }
                    }
                    if (matches.isEmpty()) {
                        Toast.makeText(this, "No matches found", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    showAllGrid("Search Results", matches);
                    previousHomeFocus = searchControl;
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
    private void play(Entry e) {
        try {
            previousHomeFocus = getCurrentFocus();
            currentEntry = e;
            failedLiveUrls.clear();
            cancelPendingLiveSkip();
            pendingResumeMs = resumePositionFor(e);
            playerOverlay = new FrameLayout(this);
            playerOverlay.setBackgroundColor(Color.BLACK);
            playerOverlay.setAlpha(0f);
            // Acts as the focus holder while the custom controls are hidden.
            playerOverlay.setFocusable(true);
            playerOverlay.setFocusableInTouchMode(true);

            playerView = new PlayerView(this);
            playerView.setUseController(false); // Disable default controller - we use custom
            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
            playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER);
            playerView.setFocusable(false);
            playerOverlay.addView(playerView, new FrameLayout.LayoutParams(-1, -1));

            buildCustomPlayerUI();
            setContentView(playerOverlay);
            playerOverlay.setScaleX(0.985f);
            playerOverlay.setScaleY(0.985f);
            playerOverlay.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(230)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f))
                .start();
            playing = true;
            isPlaying = true; // Start in playing state
            acquireWakeLock();
            startPlayback();
            showControls(true);
            // Focus the center play/pause button initially
            if (centerPlayPauseBtn != null) {
                centerPlayPauseBtn.requestFocus();
            }
        } catch (Exception ex) {
            Log.e(TAG, "Error initializing playback", ex);
            showPlaybackError();
        }
    }

    private void acquireWakeLock() {
        if (wakeLock != null && !wakeLock.isHeld()) {
            try { wakeLock.acquire(); } catch (Exception ignored) {}
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception ignored) {}
        }
    }

    // ==================== CUSTOM PLAYER UI (NETFLIX-STYLE) ====================

    // Top bar views
    private LinearLayout topBar;
    private ImageView backBtn;
    private TextView titleText, metadataText, clockText;
    private LinearLayout topBadges;
    private TextView qualityBadge, audioBadge;
    private ImageView ccBtn, settingsBtn;

    // Center controls
    private FrameLayout centerControls;
    private ImageView centerPlayPauseBtn;
    private boolean isPlaying = true;

    // Timeline
    private LinearLayout timelineContainer;
    private TextView currentTimeText, totalTimeText;
    private SeekBar progressBar;
    private Handler timelineHandler = new Handler(Looper.getMainLooper());
    private Runnable timelineUpdater;

    // Bottom controls
    private LinearLayout bottomControls;
    private TextView rewindBtn, playPauseBtn, forwardBtn, subtitlesBtn, audioBtn, fullscreenBtn;
    private TextView rewindLabel, playPauseLabel, forwardLabel, subtitlesLabel, audioLabel, fullscreenLabel;
    private View focusedControl = null;
    private int focusedControlIndex = 0;
    private View[] controlOrder; // For D-pad navigation order

    // Control visibility
    private boolean controlsVisible = false;
    private Handler hideControlsHandler = new Handler(Looper.getMainLooper());
    private Runnable hideControlsRunnable;
    private static final int CONTROLS_HIDE_DELAY_MS = 5000;

    private void buildCustomPlayerUI() {
        // --- TOP BAR ---
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(24), dp(16), dp(24), dp(16));
        // Gradient overlay for readability
        GradientDrawable topGradient = new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0xCC000000, 0x88000000, 0x00000000}
        );
        topBar.setBackground(topGradient);

        // Back button
        backBtn = new ImageView(this);
        backBtn.setImageResource(android.R.drawable.ic_menu_revert); // Use system back icon
        backBtn.setColorFilter(WHITE);
        backBtn.setPadding(dp(8), dp(8), dp(8), dp(8));
        backBtn.setFocusable(true);
        backBtn.setClickable(true);
        backBtn.setContentDescription("Back");
        backBtn.setOnClickListener(v -> onBackPressed());
        setFocusStyleView(backBtn, dp(28));
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        topBar.addView(backBtn, backParams);

        // Title, format badges, and metadata sit together on the left.
        LinearLayout metaContainer = new LinearLayout(this);
        metaContainer.setOrientation(LinearLayout.VERTICAL);
        metaContainer.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleText = new TextView(this);
        titleText.setText(currentEntry != null ? currentEntry.name : "Stream");
        titleText.setTextColor(WHITE);
        titleText.setTextSize(20);
        titleText.setTypeface(null, Typeface.BOLD);
        titleText.setMaxLines(1);
        titleText.setEllipsize(TextUtils.TruncateAt.END);
        titleRow.addView(titleText, new LinearLayout.LayoutParams(-2, -2));
        qualityBadge = createBadge("HD");
        audioBadge = createBadge("5.1");
        titleRow.addView(qualityBadge);
        titleRow.addView(audioBadge);
        metaContainer.addView(titleRow);

        metadataText = new TextView(this);
        metadataText.setText(buildMetadataString());
        metadataText.setTextColor(0xFFB0B0B0);
        metadataText.setTextSize(13);
        metadataText.setMaxLines(1);
        metadataText.setEllipsize(TextUtils.TruncateAt.END);
        metaContainer.addView(metadataText);

        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(0, -2, 1);
        metaParams.setMargins(dp(16), 0, dp(16), 0);
        topBar.addView(metaContainer, metaParams);

        // Right side: CC, Settings, Clock
        LinearLayout rightContainer = new LinearLayout(this);
        rightContainer.setOrientation(LinearLayout.HORIZONTAL);
        rightContainer.setGravity(Gravity.CENTER_VERTICAL);

        ccBtn = createIconButton(R.drawable.ic_subtitles, "Subtitles");
        ccBtn.setOnClickListener(v -> showSubtitleDialog());
        settingsBtn = createIconButton(R.drawable.ic_settings, "Settings");
        settingsBtn.setOnClickListener(v -> showPlayerSettingsDialog());
        clockText = new TextView(this);
        clockText.setText(new SimpleDateFormat("hh:mm a", Locale.getDefault()).format(new Date()));
        clockText.setTextColor(WHITE);
        clockText.setTextSize(14);
        clockText.setTypeface(null, Typeface.BOLD);
        clockText.setPadding(dp(16), 0, 0, 0);

        rightContainer.addView(ccBtn);
        rightContainer.addView(settingsBtn);
        rightContainer.addView(clockText);

        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(-2, -2);
        topBar.addView(rightContainer, rightParams);

        FrameLayout.LayoutParams topBarParams = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        playerOverlay.addView(topBar, topBarParams);

        // --- CENTER PLAY/PAUSE ---
        centerControls = new FrameLayout(this);
        centerControls.setLayoutParams(new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        centerPlayPauseBtn = new ImageView(this);
        centerPlayPauseBtn.setImageDrawable(createPlayPauseDrawable(true));
        centerPlayPauseBtn.setColorFilter(WHITE);
        centerPlayPauseBtn.setBackground(createCircleBackground(0xAA000000, 0xFFFFFFFF));
        centerPlayPauseBtn.setPadding(dp(24), dp(24), dp(24), dp(24));
        centerPlayPauseBtn.setFocusable(true);
        centerPlayPauseBtn.setClickable(true);
        centerPlayPauseBtn.setContentDescription("Play/Pause");
        centerPlayPauseBtn.setOnClickListener(v -> togglePlayPause());
        setFocusStyleView(centerPlayPauseBtn, dp(88));
        FrameLayout.LayoutParams centerParams = new FrameLayout.LayoutParams(dp(88), dp(88), Gravity.CENTER);
        centerControls.addView(centerPlayPauseBtn, centerParams);

        playerOverlay.addView(centerControls);

        // --- TIMELINE ---
        timelineContainer = new LinearLayout(this);
        timelineContainer.setOrientation(LinearLayout.HORIZONTAL);
        timelineContainer.setGravity(Gravity.CENTER_VERTICAL);
        timelineContainer.setPadding(dp(48), 0, dp(48), 0);
        // Bottom gradient overlay
        GradientDrawable bottomGradient = new GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            new int[]{0xCC000000, 0x88000000, 0x00000000}
        );
        timelineContainer.setBackground(bottomGradient);

        currentTimeText = new TextView(this);
        currentTimeText.setText("0:00");
        currentTimeText.setTextColor(WHITE);
        currentTimeText.setTextSize(13);
        currentTimeText.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
        currentTimeText.setMinWidth(dp(60));
        currentTimeText.setGravity(Gravity.CENTER);
        timelineContainer.addView(currentTimeText, new LinearLayout.LayoutParams(-2, -2));

        progressBar = new SeekBar(this);
        progressBar.setMax(10000); // High precision for seeking
        progressBar.setProgress(0);
        progressBar.setThumb(createSeekThumb());
        progressBar.setThumbOffset(dp(8));
        progressBar.setSplitTrack(false);
        progressBar.setPadding(0, 0, 0, 0);
        progressBar.setBackgroundColor(Color.TRANSPARENT);
        // Custom progress drawable with red progress
        progressBar.setProgressDrawable(createProgressDrawable());
        progressBar.setFocusable(true);
        progressBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private boolean userSeeking = false;
            @Override public void onStartTrackingTouch(SeekBar seekBar) {
                userSeeking = true;
                cancelHideControls();
            }
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && player != null && player.getDuration() > 0) {
                    long newPos = (progress * player.getDuration()) / 10000L;
                    currentTimeText.setText(formatTime(newPos));
                }
            }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                if (player != null && player.getDuration() > 0) {
                    long newPos = (seekBar.getProgress() * player.getDuration()) / 10000L;
                    player.seekTo(newPos);
                }
                userSeeking = false;
                scheduleHideControls();
            }
        });
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(0, dp(32), 1);
        progressParams.setMargins(dp(16), 0, dp(16), 0);
        timelineContainer.addView(progressBar, progressParams);

        totalTimeText = new TextView(this);
        totalTimeText.setText("0:00");
        totalTimeText.setTextColor(WHITE);
        totalTimeText.setTextSize(13);
        totalTimeText.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
        totalTimeText.setMinWidth(dp(60));
        totalTimeText.setGravity(Gravity.CENTER);
        timelineContainer.addView(totalTimeText, new LinearLayout.LayoutParams(-2, -2));

        FrameLayout.LayoutParams timelineParams = new FrameLayout.LayoutParams(-1, dp(64), Gravity.BOTTOM);
        timelineParams.setMargins(0, 0, 0, dp(78)); // Sits just above the bottom controls
        playerOverlay.addView(timelineContainer, timelineParams);

        // --- BOTTOM CONTROLS ---
        bottomControls = new LinearLayout(this);
        bottomControls.setOrientation(LinearLayout.HORIZONTAL);
        bottomControls.setGravity(Gravity.CENTER);
        bottomControls.setPadding(dp(24), dp(8), dp(24), dp(4));
        bottomControls.setBackground(bottomGradient);

        // Create control buttons with labels
        rewindBtn = createControlButton(R.drawable.ic_rewind, "10s\nRewind", "Rewind 10 seconds");
        playPauseBtn = createControlButton(R.drawable.ic_pause, "Pause", "Pause");
        forwardBtn = createControlButton(R.drawable.ic_forward, "10s\nForward", "Forward 10 seconds");
        subtitlesBtn = createControlButton(R.drawable.ic_subtitles, "Subtitles\nOff", "Subtitles: Off");
        // Audio track selection replaces the old placeholder "Video quality"
        // control: streams usually expose several audio renditions (languages,
        // 5.1 vs stereo) and those are actually switchable through ExoPlayer.
        audioBtn = createControlButton(R.drawable.ic_audio, "Audio\nAuto", "Audio: Auto");
        audioBtn.setMaxLines(2);
        audioBtn.setEllipsize(TextUtils.TruncateAt.END);
        fullscreenBtn = createControlButton(R.drawable.ic_fullscreen, "Fullscreen", "Fullscreen");

        TextView[] controls = {rewindBtn, playPauseBtn, forwardBtn, subtitlesBtn, audioBtn, fullscreenBtn};
        for (TextView control : controls) {
            LinearLayout.LayoutParams controlParams = new LinearLayout.LayoutParams(0, dp(60), 1);
            bottomControls.addView(control, controlParams);
        }

        // Set click listeners
        rewindBtn.setOnClickListener(v -> seekRelative(-10000));
        playPauseBtn.setOnClickListener(v -> togglePlayPause());
        forwardBtn.setOnClickListener(v -> seekRelative(10000));
        subtitlesBtn.setOnClickListener(v -> showSubtitleDialog());
        audioBtn.setOnClickListener(v -> showAudioDialog());
        fullscreenBtn.setOnClickListener(v -> toggleFullscreen());

        // Control order for D-pad navigation (Left/Right)
        controlOrder = new View[]{rewindBtn, playPauseBtn, forwardBtn, subtitlesBtn, audioBtn, fullscreenBtn};

        // Initially hide bottom controls (only timeline + center visible)
        bottomControls.setVisibility(View.GONE);

        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        playerOverlay.addView(bottomControls, bottomParams);

        // --- LOADING / ERROR PANELS (reuse existing) ---
        buildPlayerStatusPanels();

        // Start timeline updater
        startTimelineUpdater();

        // Update clock periodically
        startClockUpdater();
    }

    private void buildPlayerStatusPanels() {
        playerLoadingPanel = new LinearLayout(this);
        playerLoadingPanel.setOrientation(LinearLayout.VERTICAL);
        playerLoadingPanel.setGravity(Gravity.CENTER);
        playerLoadingPanel.setPadding(dp(28), dp(20), dp(28), dp(20));
        playerLoadingPanel.setBackground(rounded(0xDD17191E, 12));
        ProgressBar spinner = new ProgressBar(this);
        playerLoadingPanel.addView(spinner, new LinearLayout.LayoutParams(dp(42), dp(42)));
        playerLoadingText = text("Connecting to stream…", 17, WHITE);
        playerLoadingText.setGravity(Gravity.CENTER);
        playerLoadingText.setPadding(0, dp(12), 0, 0);
        playerLoadingPanel.addView(playerLoadingText);
        FrameLayout.LayoutParams loadingParams = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        playerOverlay.addView(playerLoadingPanel, loadingParams);

        playerErrorPanel = new LinearLayout(this);
        playerErrorPanel.setOrientation(LinearLayout.VERTICAL);
        playerErrorPanel.setGravity(Gravity.CENTER);
        playerErrorPanel.setPadding(dp(28), dp(20), dp(28), dp(20));
        playerErrorPanel.setBackground(rounded(0xFF202126, 12));
        TextView error = text("This stream could not be played. Check the playlist or network, then retry.", 18, WHITE);
        playerErrorText = error;
        error.setGravity(Gravity.CENTER);
        error.setMaxWidth(dp(460));
        playerErrorPanel.addView(error);
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        retryPlaybackButton = button("Retry");
        retryPlaybackButton.setOnClickListener(v -> {
            failedLiveUrls.clear();
            cancelPendingLiveSkip();
            if (playerErrorPanel != null) playerErrorPanel.setVisibility(View.GONE);
            startPlayback();
        });
        retryPlaybackButton.setFocusable(true);
        retryPlaybackButton.setFocusableInTouchMode(true);
        setFocusStyle(retryPlaybackButton, RED, 6);
        browseBtn = button("Back to browse");
        browseBtn.setFocusable(true);
        browseBtn.setFocusableInTouchMode(true);
        setFocusStyle(browseBtn, 0xFF262D38, 6);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-2, dp(54));
        actionParams.setMargins(dp(8), dp(14), dp(8), 0);
        actions.addView(retryPlaybackButton, actionParams);
        actions.addView(browseBtn, actionParams);
        browseBtn.setOnClickListener(v -> onBackPressed());
        playerErrorPanel.addView(actions);
        playerOverlay.addView(playerErrorPanel, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        playerErrorPanel.setVisibility(View.GONE);
    }

    private String buildMetadataString() {
        if (currentEntry == null) return "";
        StringBuilder sb = new StringBuilder();
        if (!currentEntry.group.isEmpty()) {
            sb.append(currentEntry.group).append(" · ");
        }
        sb.append(currentEntry.type);
        return sb.toString();
    }

    private String inferQualityLabel(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains("4k") || lower.contains("2160")) return "4K";
        if (lower.contains("1080") || lower.contains("fhd")) return "1080p";
        if (lower.contains("720") || lower.contains("hd")) return "720p";
        return "HD";
    }

    private TextView createBadge(String text) {
        TextView badge = new TextView(this);
        badge.setText(text);
        badge.setTextColor(WHITE);
        badge.setTextSize(10);
        badge.setTypeface(null, Typeface.BOLD);
        badge.setPadding(dp(8), dp(3), dp(8), dp(3));
        badge.setBackground(rounded(0xFF1A1A1A, 4));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.setMargins(0, dp(2), dp(6), 0);
        badge.setLayoutParams(p);
        return badge;
    }

    private ImageView createIconButton(int resId, String desc) {
        ImageView btn = new ImageView(this);
        btn.setImageResource(resId);
        btn.setColorFilter(WHITE);
        btn.setPadding(dp(12), dp(12), dp(12), dp(12));
        btn.setFocusable(true);
        btn.setClickable(true);
        btn.setContentDescription(desc);
        setFocusStyleView(btn, dp(40));
        return btn;
    }

    private TextView createControlButton(int iconResId, String label, String desc) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setTextColor(0xFFE0E0E0);
        btn.setTextSize(13);
        btn.setGravity(Gravity.CENTER_VERTICAL);
        btn.setCompoundDrawablesWithIntrinsicBounds(iconResId, 0, 0, 0);
        btn.setCompoundDrawablePadding(dp(14));
        btn.setPadding(dp(10), dp(8), dp(6), dp(8));
        btn.setFocusable(true);
        btn.setClickable(true);
        btn.setContentDescription(desc);
        btn.setTag(desc);
        setFocusStyleView(btn, dp(48));
        return btn;
    }

    private Drawable createPlayPauseDrawable(boolean isPlaying) {
        // Use system icons
        return ContextCompat.getDrawable(this, isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
    }

    private GradientDrawable createCircleBackground(int bgColor, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(bgColor);
        d.setStroke(dp(2), strokeColor);
        return d;
    }

    private void setFocusStyleView(View view, int size) {
        view.setOnFocusChangeListener((v, focused) -> {
            if (focused) {
                v.animate().cancel();
                // Apple TV style: scale up with spring effect, elevate, add glow
                v.animate()
                    .scaleX(1.12f).scaleY(1.12f)
                    .translationZ(dp(16))
                    .setDuration(200)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.2f))
                    .start();
                // Red glow outline with elevation
                if (v.getBackground() instanceof GradientDrawable) {
                    GradientDrawable bg = (GradientDrawable) v.getBackground();
                    bg.setStroke(dp(4), RED);
                }
                // Use View elevation + outline for glow effect
                v.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(size / 2));
                    }
                });
                v.setClipToOutline(true);
                v.setElevation(dp(16));
                focusedControl = v;
                focusedControlIndex = indexOfControl(v);
                // Bring to front for proper layering
                v.bringToFront();
                if (v.getParent() instanceof View) {
                    ((View) v.getParent()).invalidate();
                }
            } else {
                v.animate().cancel();
                v.animate()
                    .scaleX(1f).scaleY(1f)
                    .translationZ(0)
                    .setDuration(200)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f))
                    .start();
                if (v.getBackground() instanceof GradientDrawable) {
                    GradientDrawable bg = (GradientDrawable) v.getBackground();
                    bg.setStroke(dp(2), 0xFFFFFFFF);
                }
                v.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
                v.setElevation(0);
            }
        });
        // Enable hardware acceleration for smooth animations
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    private int indexOfControl(View v) {
        // `controlOrder` only exists while the player UI is built, but this
        // listener is also attached to the See All grid's Back button. When the
        // grid tears down and a focused tile is made non-focusable, Android
        // moves focus to that Back button mid-teardown — which ran this method
        // with controlOrder == null and killed the app (reported on Fire TV).
        if (controlOrder != null) {
            for (int i = 0; i < controlOrder.length; i++) {
                if (controlOrder[i] == v) return i;
            }
        }
        // Check other focusable views
        if (v == backBtn) return -1;
        if (v == ccBtn) return -2;
        if (v == settingsBtn) return -3;
        if (v == centerPlayPauseBtn) return -4;
        if (v == progressBar) return -5;
        return -1;
    }

    private Drawable createSeekThumb() {
        // White circle with red border
        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(WHITE);
        thumb.setStroke(dp(2), RED);
        thumb.setSize(dp(16), dp(16));
        return thumb;
    }

    private Drawable createProgressDrawable() {
        // Create a proper LayerDrawable for SeekBar with background and progress layers
        // Background track
        ShapeDrawable background = new ShapeDrawable(new RoundRectShape(new float[]{dp(2), dp(2), dp(2), dp(2), dp(2), dp(2), dp(2), dp(2)}, null, null));
        background.getPaint().setColor(0xFF333333);
        background.setIntrinsicHeight(dp(3));

        // Progress track
        ShapeDrawable progress = new ShapeDrawable(new RoundRectShape(new float[]{dp(2), dp(2), dp(2), dp(2), dp(2), dp(2), dp(2), dp(2)}, null, null));
        progress.getPaint().setColor(RED);
        progress.setIntrinsicHeight(dp(3));

        // Wrap progress in ClipDrawable for horizontal clipping
        ClipDrawable clipProgress = new ClipDrawable(progress, Gravity.LEFT, ClipDrawable.HORIZONTAL);

        LayerDrawable layerDrawable = new LayerDrawable(new Drawable[]{
            background,
            clipProgress
        });
        layerDrawable.setId(0, android.R.id.background);
        layerDrawable.setId(1, android.R.id.progress);
        layerDrawable.setLayerHeight(0, dp(3));
        layerDrawable.setLayerHeight(1, dp(3));
        layerDrawable.setLayerGravity(0, Gravity.FILL_HORIZONTAL | Gravity.CENTER_VERTICAL);
        layerDrawable.setLayerGravity(1, Gravity.FILL_HORIZONTAL | Gravity.CENTER_VERTICAL);
        return layerDrawable;
    }

    private void startTimelineUpdater() {
        timelineUpdater = new Runnable() {
            int saveCounter = 0;
            @Override public void run() {
                if (player != null && playing && controlsVisible) {
                    long pos = player.getCurrentPosition();
                    long dur = player.getDuration();
                    if (dur > 0 && dur != Long.MAX_VALUE) {
                        // VOD content - show timeline
                        int progress = (int) ((pos * 10000L) / dur);
                        progressBar.setProgress(progress);
                        currentTimeText.setText(formatTime(pos));
                        totalTimeText.setText(formatTime(dur));
                        timelineContainer.setVisibility(View.VISIBLE);
                    } else {
                        // Live stream - hide timeline or show LIVE
                        timelineContainer.setVisibility(View.GONE);
                    }
                }
                // Save progress every 10 seconds (20 ticks * 500ms)
                saveCounter++;
                if (saveCounter >= 20 && player != null && currentEntry != null && player.getDuration() > 0) {
                    saveCounter = 0;
                    long pos = player.getCurrentPosition();
                    long dur = player.getDuration();
                    if (pos > 30000 && pos < dur - 60000) {
                        savePlaybackProgress(currentEntry, pos, false);
                    }
                }
                timelineHandler.postDelayed(this, 500);
            }
        };
        timelineHandler.post(timelineUpdater);
    }

    private void startClockUpdater() {
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (clockText != null && playing) {
                    clockText.setText(new SimpleDateFormat("hh:mm a", Locale.getDefault()).format(new Date()));
                }
                handler.postDelayed(this, 30000); // Update every 30s
            }
        }, 30000);
    }

    private Handler handler = new Handler(Looper.getMainLooper());

    private String formatTime(long ms) {
        if (ms <= 0) return "0:00";
        long totalSeconds = ms / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
        }
    }

    private String formatRuntime(long ms) {
        long totalMinutes = ms / 60000;
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        return hours > 0 ? hours + "h " + minutes + "m" : minutes + "m";
    }

    private void showControls(boolean show) {
        boolean wasVisible = controlsVisible;
        controlsVisible = show;
        if (topBar == null || centerControls == null || timelineContainer == null || bottomControls == null) return;
        if (show) {
            topBar.animate().alpha(1f).setDuration(300).start();
            centerControls.setVisibility(View.VISIBLE);
            centerControls.setAlpha(1f);
            timelineContainer.animate().alpha(1f).setDuration(300).start();
            bottomControls.setVisibility(View.VISIBLE);
            bottomControls.animate().alpha(1f).setDuration(300).start();
            // Hiding the controls drops focus. Restore it when they come back,
            // otherwise OK does nothing and Right cannot reach Forward.
            if ((!wasVisible || getCurrentFocus() == null) && centerPlayPauseBtn != null) {
                centerPlayPauseBtn.post(() -> {
                    if (playing && controlsVisible && centerPlayPauseBtn != null
                            && centerPlayPauseBtn.getVisibility() == View.VISIBLE) {
                        centerPlayPauseBtn.requestFocus();
                    }
                });
            }
            scheduleHideControls();
        } else {
            topBar.animate().alpha(0f).setDuration(300).start();
            centerControls.animate().alpha(0f).setDuration(300).withEndAction(() -> centerControls.setVisibility(View.GONE)).start();
            timelineContainer.animate().alpha(0f).setDuration(300).start();
            bottomControls.animate().alpha(0f).setDuration(300).withEndAction(() -> bottomControls.setVisibility(View.GONE)).start();
            // Do not leave focus on a view that is about to be GONE.
            View focused = getCurrentFocus();
            if (playerOverlay != null && (focused == backBtn || focused == ccBtn || focused == settingsBtn
                    || focused == progressBar || focused == centerPlayPauseBtn || isBottomControl(focused))) {
                // Focus the overlay itself (not a child) while the controls are
                // hidden; showControls(true) then restores focus to the center.
                int previous = playerOverlay.getDescendantFocusability();
                playerOverlay.setFocusable(true);
                playerOverlay.setFocusableInTouchMode(true);
                playerOverlay.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                playerOverlay.requestFocus();
                playerOverlay.setDescendantFocusability(previous);
            }
            cancelHideControls();
        }
    }

    private void scheduleHideControls() {
        cancelHideControls();
        hideControlsRunnable = () -> {
            if (!controlsVisible || player == null || !isPlaying) return;
            View focused = getCurrentFocus();
            // Never hide the row out from under a remote button the user is on.
            if (isBottomControl(focused) || focused == backBtn || focused == ccBtn || focused == settingsBtn) return;
            showControls(false);
        };
        hideControlsHandler.postDelayed(hideControlsRunnable, CONTROLS_HIDE_DELAY_MS);
    }

    private void cancelHideControls() {
        if (hideControlsRunnable != null) {
            hideControlsHandler.removeCallbacks(hideControlsRunnable);
        }
    }

    private void updatePlayPauseUI() {
        if (playPauseBtn != null) {
            playPauseBtn.setText(isPlaying ? "Pause" : "Play");
            playPauseBtn.setCompoundDrawablesWithIntrinsicBounds(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play, 0, 0, 0);
            playPauseBtn.setContentDescription(isPlaying ? "Pause" : "Play");
        }
        if (centerPlayPauseBtn != null) {
            centerPlayPauseBtn.setImageDrawable(createPlayPauseDrawable(isPlaying));
            centerPlayPauseBtn.setContentDescription(isPlaying ? "Pause" : "Play");
        }
        if (controlsVisible) {
            centerControls.setVisibility(View.VISIBLE);
            centerControls.setAlpha(1f);
        } else {
            centerControls.setVisibility(View.GONE);
        }
    }

    private void togglePlayPause() {
        if (player == null) return;
        isPlaying = !isPlaying;
        if (isPlaying) {
            player.play();
        } else {
            player.pause();
        }
        updatePlayPauseUI();
        if (!isPlaying) {
            cancelHideControls(); // Keep controls visible when paused
        }
        scheduleHideControls();
    }

    private void seekRelative(long ms) {
        if (player == null) return;
        if (!player.isCurrentMediaItemSeekable()) {
            Toast.makeText(this, "This stream cannot be skipped", Toast.LENGTH_SHORT).show();
            showControls(true);
            return;
        }
        long duration = player.getDuration();
        long target = player.getCurrentPosition() + ms;
        if (target < 0) target = 0;
        // C.TIME_UNSET is negative, so only clamp when the duration is known.
        // Clamping against an unknown duration used to send Forward to 0:00.
        if (duration > 0 && target > duration) target = duration;
        player.seekTo(target);
        updateTimelinePosition(target);
        showControls(true);
    }

    /** Move the timeline immediately after a seek instead of waiting a tick. */
    private void updateTimelinePosition(long position) {
        if (player == null || progressBar == null) return;
        long duration = player.getDuration();
        if (duration > 0 && duration != Long.MAX_VALUE) {
            int progress = (int) Math.max(0, Math.min(10000L, position * 10000L / duration));
            progressBar.setProgress(progress);
            if (totalTimeText != null) totalTimeText.setText(formatTime(duration));
        }
        if (currentTimeText != null) currentTimeText.setText(formatTime(position));
    }

    private void switchChannel(boolean next) {
        if (currentEntry == null) return;
        ArrayList<Entry> liveEntries = new ArrayList<>();
        for (Entry e : entries) {
            if ("Live".equals(e.type)) liveEntries.add(e);
        }
        if (liveEntries.isEmpty()) return;

        // Find current index
        int currentIndex = 0;
        for (int i = 0; i < liveEntries.size(); i++) {
            if (liveEntries.get(i).url.equals(currentEntry.url)) {
                currentIndex = i;
                break;
            }
        }

        // Walk forward/backward past channels that already failed this session.
        for (int step = 1; step <= liveEntries.size(); step++) {
            int offset = next ? step : -step;
            int index = ((currentIndex + offset) % liveEntries.size() + liveEntries.size()) % liveEntries.size();
            Entry candidate = liveEntries.get(index);
            if (failedLiveUrls.contains(candidate.url)) continue;
            switchLiveTo(candidate, true);
            return;
        }
        Toast.makeText(this, "No working live channels found", Toast.LENGTH_SHORT).show();
    }

    /** Update the custom player header when the channel changes in place. */
    private void updatePlayerHeaderForEntry(Entry entry) {
        if (entry == null) return;
        if (titleText != null) titleText.setText(entry.name);
        if (metadataText != null) metadataText.setText(buildMetadataString());
        if (qualityBadge != null) qualityBadge.setText(inferQualityLabel(entry.url));
        if (currentTimeText != null) currentTimeText.setText("0:00");
        if (totalTimeText != null) totalTimeText.setText("0:00");
        if (progressBar != null) progressBar.setProgress(0);
    }

    /**
     * Switch live channels without rebuilding the player overlay, so there is
     * no black flash and the controls/focus stay put.
     */
    private void switchLiveTo(Entry entry, boolean userInitiated) {
        if (entry == null) return;
        cancelPendingLiveSkip();
        currentEntry = entry;
        pendingResumeMs = 0;
        updatePlayerHeaderForEntry(entry);
        if (playerErrorPanel != null) playerErrorPanel.setVisibility(View.GONE);
        if (playerLoadingPanel != null) playerLoadingPanel.setVisibility(View.VISIBLE);
        if (playerLoadingText != null) playerLoadingText.setText("Connecting to " + entry.name + "…");
        if (userInitiated) Toast.makeText(this, entry.name, Toast.LENGTH_SHORT).show();
        startPlayback();
    }

    /** Next live channel whose URL has not failed this session, or null. */
    private Entry nextUnfailedLive(Entry from) {
        ArrayList<Entry> live = new ArrayList<>();
        for (Entry e : entries) {
            if ("Live".equals(e.type)) live.add(e);
        }
        if (live.isEmpty()) return null;
        int start = 0;
        for (int i = 0; i < live.size(); i++) {
            if (live.get(i).url.equals(from.url)) { start = i; break; }
        }
        for (int step = 1; step <= live.size(); step++) {
            Entry candidate = live.get((start + step) % live.size());
            if (!failedLiveUrls.contains(candidate.url)) return candidate;
        }
        return null;
    }

    /**
     * A live stream failed. Remember it and auto-advance to the next channel
     * that has not failed yet. Only show the error panel (with Retry) when the
     * whole playlist is exhausted, so the user is never stuck on a dead feed.
     */
    private void handlePlaybackFailure(Entry failed) {
        if (failed == null || !"Live".equals(failed.type)) {
            showPlaybackError();
            return;
        }
        // Skipping every channel while offline would just burn through the
        // playlist; wait for connectivity instead.
        if (!networkAvailable) {
            showPlaybackError("Network connection lost. Reconnect, then press Retry.");
            return;
        }
        failedLiveUrls.add(failed.url);
        Entry next = nextUnfailedLive(failed);
        if (next == null) {
            showPlaybackError("Every live channel failed to play. Check your connection or the playlist, then retry.");
            return;
        }
        scheduleLiveSkip(failed, next);
    }

    private void scheduleLiveSkip(final Entry from, final Entry next) {
        if (liveSkipScheduled) return;
        liveSkipScheduled = true;
        if (liveSkipRunnable != null) handler.removeCallbacks(liveSkipRunnable);
        liveSkipRunnable = () -> {
            liveSkipScheduled = false;
            liveSkipRunnable = null;
            if (!playing || currentEntry == null) return;
            // The user may have switched manually while the notice was showing.
            if (!currentEntry.url.equals(from.url)) return;
            Toast.makeText(MainActivity.this,
                    from.name + " unavailable — next: " + next.name,
                    Toast.LENGTH_SHORT).show();
            switchLiveTo(next, false);
        };
        if (playerErrorPanel != null) playerErrorPanel.setVisibility(View.GONE);
        if (playerLoadingPanel != null) playerLoadingPanel.setVisibility(View.VISIBLE);
        if (playerLoadingText != null) playerLoadingText.setText("Channel unavailable. Trying " + next.name + "…");
        if (handler != null) handler.postDelayed(liveSkipRunnable, 900);
    }

    private void cancelPendingLiveSkip() {
        if (liveSkipRunnable != null && handler != null) handler.removeCallbacks(liveSkipRunnable);
        liveSkipRunnable = null;
        liveSkipScheduled = false;
    }

    private boolean isKannadaChannel(Entry e) {
        String text = (e.group + " " + e.name).toLowerCase(Locale.ROOT);
        return text.contains("kannada") || text.contains("kannad") 
                || text.contains("karnataka") || text.contains("suvarna")
                || text.contains("uddhav") || text.contains("kasthuri")
                || text.contains("janashri") || text.contains("tv9 kannada")
                || text.contains("public tv") || text.contains("news18 kannada");
    }

    private void showSubtitleDialog() {
        // TODO: Implement subtitle track selection
        Toast.makeText(this, "Subtitle tracks not available", Toast.LENGTH_SHORT).show();
    }

    /** Language name for an audio rendition, falling back to its code. */
    private String audioTrackLanguage(Format format) {
        if (format == null) return "";
        if (format.label != null && !format.label.trim().isEmpty()) return format.label.trim();
        String code = format.language == null ? "" : format.language.trim();
        if (code.isEmpty()) return "";
        try {
            String display = new Locale(code).getDisplayLanguage(Locale.getDefault());
            if (display != null && !display.isEmpty() && !display.equalsIgnoreCase(code)) return display;
        } catch (Exception ignored) {}
        return code.toUpperCase(Locale.ROOT);
    }

    /** "English · 5.1" style label for one audio rendition in the picker. */
    private String audioTrackLabel(Format format) {
        String language = audioTrackLanguage(format);
        if (language.isEmpty()) language = "Track";
        String layout = audioChannelLayout(format);
        return layout.isEmpty() ? language : language + "  ·  " + layout;
    }

    private String audioChannelLayout(Format format) {
        if (format == null || format.channelCount <= 0) return "";
        switch (format.channelCount) {
            case 1: return "Mono";
            case 2: return "Stereo";
            case 6: return "5.1";
            case 8: return "7.1";
            default: return format.channelCount + " ch";
        }
    }

    /** Keeps the audio control's label in step with the selected rendition. */
    private void updateAudioButtonLabel() {
        if (audioBtn == null) return;
        String label = "Auto";
        if (player != null) {
            try {
                for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
                    if (group.getType() != C.TRACK_TYPE_AUDIO || !group.isSelected()) continue;
                    for (int i = 0; i < group.length; i++) {
                        if (!group.isTrackSelected(i)) continue;
                        String language = audioTrackLanguage(group.getTrackFormat(i));
                        if (!language.isEmpty()) label = language;
                        break;
                    }
                    break;
                }
            } catch (Exception ignored) {}
        }
        String shortLabel = label.length() > 16 ? label.substring(0, 15) + "…" : label;
        audioBtn.setText("Audio\n" + shortLabel);
        String desc = "Audio: " + label;
        audioBtn.setContentDescription(desc);
        audioBtn.setTag(desc);
    }

    private void applyAudioTrack(TrackSelectionOverride override) {
        if (player == null) return;
        try {
            TrackSelectionParameters.Builder builder = player.getTrackSelectionParameters()
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO);
            if (override != null) builder.setOverrideForType(override);
            player.setTrackSelectionParameters(builder.build());
            updateAudioButtonLabel();
            Toast.makeText(this, override == null ? "Audio: Auto" : "Audio track changed",
                    Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
            Toast.makeText(this, "Could not change the audio track", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Audio renditions the current stream carries. ExoPlayer only reports them
     * once it has read the manifest, so this is built when the dialog opens.
     */
    private void showAudioDialog() {
        if (player == null) return;
        cancelHideControls();

        ArrayList<String> labels = new ArrayList<>();
        ArrayList<TrackSelectionOverride> overrides = new ArrayList<>();
        labels.add("Auto (default)");
        overrides.add(null);
        int selected = 0;

        try {
            Tracks tracks = player.getCurrentTracks();
            for (Tracks.Group group : tracks.getGroups()) {
                if (group.getType() != C.TRACK_TYPE_AUDIO) continue;
                for (int i = 0; i < group.length; i++) {
                    if (!group.isTrackSupported(i)) continue;
                    labels.add(audioTrackLabel(group.getTrackFormat(i)));
                    overrides.add(new TrackSelectionOverride(group.getMediaTrackGroup(), i));
                    if (group.isTrackSelected(i)) selected = labels.size() - 1;
                }
            }
        } catch (Exception ignored) {}

        if (labels.size() == 1) {
            Toast.makeText(this, "No other audio tracks in this stream", Toast.LENGTH_SHORT).show();
            showControls(true);
            return;
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Audio track")
                .setSingleChoiceItems(labels.toArray(new String[0]), selected, (d, which) -> {
                    applyAudioTrack(overrides.get(which));
                    d.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .create();
        // A TV dialog takes focus away from the player; give it back afterwards.
        dialog.setOnDismissListener(d -> {
            if (!playing) return;
            showControls(true);
            scheduleHideControls();
            View target = (audioBtn != null && audioBtn.isShown()) ? audioBtn : centerPlayPauseBtn;
            if (target != null) target.post(target::requestFocus);
        });
        dialog.show();
    }

    /** Player settings reachable from the remote's top-bar gear button. */
    private void showPlayerSettingsDialog() {
        String[] options = {"Audio track", "Subtitles", "Fullscreen"};
        new AlertDialog.Builder(this)
            .setTitle("Player settings")
            .setItems(options, (d, which) -> {
                if (which == 0) showAudioDialog();
                else if (which == 1) showSubtitleDialog();
                else toggleFullscreen();
            })
            .show();
    }

    private void toggleFullscreen() {
        // Already fullscreen on TV, but could toggle immersive mode
        applyImmersiveFullscreen();
        Toast.makeText(this, "Fullscreen", Toast.LENGTH_SHORT).show();
    }

    private void startPlayback(){
        if (currentEntry == null || playerOverlay == null) return;
        final Entry entry = currentEntry;

        try {
            if (player != null) {
                try { player.release(); } catch (Exception ignored) {}
                player = null;
            }

            playerErrorPanel.setVisibility(View.GONE);
            playerLoadingText.setText("Connecting to " + entry.name + "…");
            playerLoadingPanel.setVisibility(View.VISIBLE);

            androidx.media3.exoplayer.LoadControl loadControl = new androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(8000, 45000, 1000, 3000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();

            player = new ExoPlayer.Builder(this)
                .setLoadControl(loadControl)
                .build();

            player.setHandleAudioBecomingNoisy(true);

            final ExoPlayer boundPlayer = player;
            player.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int state) {
                    if (player != boundPlayer) return;
                    runOnUiThread(() -> {
                        if (player != boundPlayer || !playing) return;
                        Entry active = currentEntry;
                        try {
                            if (state == Player.STATE_BUFFERING) {
                                if (playerLoadingText != null) playerLoadingText.setText("Buffering " + (active != null ? active.name : "stream") + "…");
                                if (playerLoadingPanel != null) playerLoadingPanel.setVisibility(View.VISIBLE);
                                isPlaying = false;
                                updatePlayPauseUI();
                            } else if (state == Player.STATE_READY) {
                                if (playerLoadingPanel != null) playerLoadingPanel.setVisibility(View.GONE);
                                if (playerErrorPanel != null) playerErrorPanel.setVisibility(View.GONE);
                                // A channel that finally plays is healthy again.
                                if (active != null) failedLiveUrls.remove(active.url);
                                // Resume an unfinished movie/episode. Position is
                                // only meaningful once the timeline is known.
                                if (pendingResumeMs > 0) {
                                    long resumeTarget = pendingResumeMs;
                                    pendingResumeMs = 0;
                                    player.seekTo(resumeTarget);
                                    Toast.makeText(MainActivity.this,
                                            "Resuming from " + formatTime(resumeTarget),
                                            Toast.LENGTH_SHORT).show();
                                }
                                long durationMs = player.getDuration();
                                if (metadataText != null && active != null) {
                                    metadataText.setText(buildMetadataString()
                                            + (durationMs > 0 && durationMs != Long.MAX_VALUE ? " · " + formatRuntime(durationMs) : ""));
                                }
                                isPlaying = player.isPlaying();
                                updatePlayPauseUI();
                                showControls(true);
                            } else if (state == Player.STATE_ENDED) {
                                if (active != null && "Live".equals(active.type)) {
                                    handlePlaybackFailure(active);
                                } else {
                                    onPlaybackEnded();
                                }
                            } else if (state == Player.STATE_IDLE && player.getPlayerError() != null) {
                                handlePlaybackFailure(active);
                            }
                        } catch (Exception ignored) {}
                    });
                }

                @Override public void onPlayerError(PlaybackException error) {
                    if (player != boundPlayer) return;
                    Log.e(TAG, "Playback error: " + error.getMessage(), error);
                    final Entry failed = currentEntry;
                    runOnUiThread(() -> {
                        if (player == boundPlayer && playing) handlePlaybackFailure(failed);
                    });
                }

                @Override public void onIsPlayingChanged(boolean playingState) {
                    if (player != boundPlayer) return;
                    isPlaying = playingState;
                    updatePlayPauseUI();
                    if (playingState) {
                        scheduleHideControls();
                    } else {
                        cancelHideControls();
                    }
                }

                // Streams report their audio groups a moment after the manifest
                // loads; keep the Audio control showing what is actually playing.
                @Override public void onTracksChanged(Tracks tracks) {
                    if (player != boundPlayer) return;
                    runOnUiThread(() -> {
                        if (player == boundPlayer) updateAudioButtonLabel();
                    });
                }
            });

            playerView.setPlayer(player);

            // Create MediaItem with robust configuration for direct MP4/TS streams
            MediaItem mediaItem = new MediaItem.Builder()
                .setUri(entry.url)
                .setMimeType(inferMimeType(entry.url))
                .build();

            player.setMediaItem(mediaItem);
            player.prepare();
            player.play();

        } catch (Exception ex) {
            Log.e(TAG, "Error starting playback", ex);
            handlePlaybackFailure(entry);
        }
    }

    private String inferMimeType(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains(".m3u8") || lower.contains("hls")) return "application/x-mpegURL";
        if (lower.contains(".mpd") || lower.contains("dash")) return "application/dash+xml";
        if (lower.endsWith(".mp4") || lower.contains(".mp4?")) return "video/mp4";
        if (lower.endsWith(".ts") || lower.contains(".ts?")) return "video/mp2t";
        if (lower.endsWith(".mkv") || lower.contains(".mkv?")) return "video/x-matroska";
        if (lower.endsWith(".webm") || lower.contains(".webm?")) return "video/webm";
        return null; // Let ExoPlayer infer
    }

    private static final String DEFAULT_PLAYBACK_ERROR =
            "This stream could not be played. Check the playlist or network, then retry.";

    private void showPlaybackError() {
        showPlaybackError(null);
    }

    private void showPlaybackError(String message) {
        runOnUiThread(() -> {
            try {
                if (playerLoadingPanel != null) playerLoadingPanel.setVisibility(View.GONE);
                if (playerErrorPanel != null) {
                    if (playerErrorText != null) {
                        playerErrorText.setText(message == null ? DEFAULT_PLAYBACK_ERROR : message);
                    }
                    playerErrorPanel.setVisibility(View.VISIBLE);
                    if (retryPlaybackButton != null) retryPlaybackButton.requestFocus();
                }
            } catch (Exception ignored) {}
        });
    }

    private void onPlaybackEnded() {
        // Auto-return to browse when stream ends (e.g., VOD movie finishes)
        runOnUiThread(() -> {
            if (playing) {
                // Mark as completed (100% progress)
                if (currentEntry != null) savePlaybackProgress(currentEntry, player != null ? player.getDuration() : 0, true);
                Toast.makeText(this, "Playback finished", Toast.LENGTH_SHORT).show();
                onBackPressed();
            }
        });
    }

    private void savePlaybackProgress(Entry entry, long position, boolean completed) {
        if (entry == null || position <= 0) return;
        try {
            SharedPreferences prefs = getPreferences(MODE_PRIVATE);
            String key = "progress_" + sha256(entry.url);
            JSONObject obj = new JSONObject();
            obj.put("position", position);
            obj.put("duration", player != null ? player.getDuration() : 0);
            obj.put("completed", completed);
            obj.put("timestamp", System.currentTimeMillis());
            obj.put("name", entry.name);
            obj.put("logo", entry.logo);
            obj.put("type", entry.type);
            obj.put("group", entry.group);
            prefs.edit().putString(key, obj.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void saveCurrentPlaybackPosition() {
        if (player != null && currentEntry != null && player.getDuration() > 0) {
            long pos = player.getCurrentPosition();
            long dur = player.getDuration();
            // Only save if watched more than 30 seconds and not near the end
            if (pos > 30000 && pos < dur - 60000) {
                savePlaybackProgress(currentEntry, pos, false);
            }
        }
    }

    /**
     * Milliseconds to resume from, or 0 to start over.
     *
     * Mirrors saveCurrentPlaybackPosition's thresholds: anything under 30s in,
     * or within a minute of the end, counts as "start over" rather than resume.
     */
    private long resumePositionFor(Entry entry) {
        JSONObject saved = getPlaybackProgress(entry);
        if (saved == null || saved.optBoolean("completed")) return 0;
        long position = saved.optLong("position", 0);
        long duration = saved.optLong("duration", 0);
        if (position < 30000) return 0;
        if (duration > 0 && position > duration - 60000) return 0;
        return position;
    }

    private JSONObject getPlaybackProgress(Entry entry) {
        if (entry == null) return null;
        try {
            SharedPreferences prefs = getPreferences(MODE_PRIVATE);
            String key = "progress_" + sha256(entry.url);
            String json = prefs.getString(key, null);
            if (json != null) return new JSONObject(json);
        } catch (Exception ignored) {}
        return null;
    }

    private ArrayList<JSONObject> getRecentProgress(int limit) {
        ArrayList<JSONObject> recent = new ArrayList<>();
        try {
            SharedPreferences prefs = getPreferences(MODE_PRIVATE);
            Map<String, ?> all = prefs.getAll();
            ArrayList<JSONObject> items = new ArrayList<>();
            for (Map.Entry<String, ?> e : all.entrySet()) {
                if (e.getKey().startsWith("progress_")) {
                    try {
                        JSONObject obj = new JSONObject(e.getValue().toString());
                        items.add(obj);
                    } catch (Exception ignored) {}
                }
            }
            // Sort by timestamp descending
            items.sort((a, b) -> {
                try {
                    return Long.compare(b.getLong("timestamp"), a.getLong("timestamp"));
                } catch (Exception ex) { return 0; }
            });
            for (int i = 0; i < Math.min(limit, items.size()); i++) {
                recent.add(items.get(i));
            }
        } catch (Exception ignored) {}
        return recent;
    }
    @Override public void onBackPressed(){
        if (playing) {
            cancelPendingLiveSkip();
            failedLiveUrls.clear();
            saveCurrentPlaybackPosition(); // Save progress before exiting
            releaseWakeLock();
            cancelHideControls();
            if (timelineUpdater != null) timelineHandler.removeCallbacks(timelineUpdater);
            if (handler != null) handler.removeCallbacksAndMessages(null);
            if (hideControlsHandler != null) hideControlsHandler.removeCallbacksAndMessages(null);

            if (player != null) {
                try { player.release(); } catch (Exception ignored) {}
                player = null;
            }
            playing = false;
            isPlaying = false;
            currentEntry = null;
            setContentView(root);
            // A MENU press during playback saved the title without rebuilding the
            // home screen (it was detached). Refresh it now so the heart shows.
            if (favoritesChangedWhilePlaying) {
                favoritesChangedWhilePlaying = false;
                refreshRows();
            }
            root.setAlpha(0f);
            root.animate().alpha(1f).setDuration(140).withEndAction(() -> {
                if (previousHomeFocus != null && previousHomeFocus.isShown() && previousHomeFocus.isFocusable()) {
                    previousHomeFocus.requestFocus();
                } else {
                    requestInitialFocus();
                }
            }).start();
            playerView = null;
            playerOverlay = null;
            playerLoadingPanel = null;
            playerErrorPanel = null;
            // Clear custom UI refs
            topBar = null;
            centerControls = null;
            timelineContainer = null;
            bottomControls = null;
            progressBar = null;
            controlOrder = null;
            audioBtn = null;
            return;
        }
        if (gridOverlayView != null) {
            closeAllGrid();
            return;
        }
        super.onBackPressed();
    }

    /** Stable identity for a saved title, so expiring URL tokens cannot hide it. */
    private String favoriteKey(Entry e) {
        if (e == null) return "";
        try {
            String type = e.type == null ? "" : e.type.trim();
            String group = e.group == null ? "" : e.group.trim();
            String name = e.name == null ? "" : e.name.trim().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) return "";
            return sha256(type + "\u0001" + group + "\u0001" + name);
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * Everything in My List: titles from the loaded playlist (they carry the
     * freshest URL) plus saved snapshots the playlist no longer has, such as a
     * channel whose stream token rotated or a title from another source.
     */
    private ArrayList<Entry> myListEntries() {
        LinkedHashMap<String, Entry> byKey = new LinkedHashMap<>();
        boolean migrated = false;
        for (Entry e : entries) {
            if (!isFavorite(e)) continue;
            String key = favoriteKey(e);
            if (key.isEmpty()) continue;
            // Backfill snapshots for favourites saved by older versions.
            if (!favoriteEntries.containsKey(key)) {
                favoriteEntries.put(key, e);
                migrated = true;
            }
            byKey.put(key, e);
        }
        for (Map.Entry<String, Entry> saved : favoriteEntries.entrySet()) {
            if (!byKey.containsKey(saved.getKey())) byKey.put(saved.getKey(), saved.getValue());
        }
        if (migrated) saveFavorites();
        return new ArrayList<>(byKey.values());
    }

    private void loadFavorites(){
        Set<String> saved=getPreferences(0).getStringSet("favorites",Collections.emptySet());
        favoriteKeys.addAll(saved);
        String raw=getPreferences(0).getString(FAVORITES_JSON,null);
        if(raw==null||raw.isEmpty())return;
        try{
            JSONArray list=new JSONArray(raw);
            for(int i=0;i<list.length();i++){
                JSONObject o=list.getJSONObject(i);
                Entry e=new Entry(o.optString("name",""),o.optString("url",""),
                        o.optString("group",""),o.optString("logo",""),
                        o.optString("type","Movies"));
                String key=o.optString("key","");
                if(key.isEmpty())key=favoriteKey(e);
                if(!key.isEmpty())favoriteEntries.put(key,e);
            }
        }catch(Exception ignored){}
    }

    private void saveFavorites(){
        try{
            JSONArray list=new JSONArray();
            for(Map.Entry<String,Entry> saved:favoriteEntries.entrySet()){
                Entry e=saved.getValue();
                JSONObject o=new JSONObject();
                o.put("key",saved.getKey());
                o.put("name",e.name==null?"":e.name);
                o.put("url",e.url==null?"":e.url);
                o.put("group",e.group==null?"":e.group);
                o.put("logo",e.logo==null?"":e.logo);
                o.put("type",e.type==null?"":e.type);
                list.put(o);
            }
            getPreferences(0).edit()
                    .putStringSet("favorites",new HashSet<>(favoriteKeys))
                    .putString(FAVORITES_JSON,list.toString())
                    .apply();
        }catch(Exception ignored){}
    }

    private boolean isFavorite(Entry e){
        if(e==null)return false;
        String key=favoriteKey(e);
        if(!key.isEmpty()&&favoriteEntries.containsKey(key))return true;
        try{return favoriteKeys.contains(sha256(e.url));}catch(Exception ignored){return false;}
    }

    /** Add or remove one title from both the snapshot and the legacy key set. */
    private boolean setFavorite(Entry e, boolean add){
        return setFavorite(e, add, true);
    }

    private boolean setFavorite(Entry e, boolean add, boolean persist){
        if(e==null)return false;
        boolean changed=false;
        String key=favoriteKey(e);
        if(add){
            if(!key.isEmpty()&&favoriteEntries.put(key,e)==null)changed=true;
            try{if(favoriteKeys.add(sha256(e.url)))changed=true;}catch(Exception ignored){}
        }else{
            if(!key.isEmpty()&&favoriteEntries.remove(key)!=null)changed=true;
            try{if(favoriteKeys.remove(sha256(e.url)))changed=true;}catch(Exception ignored){}
        }
        if(changed&&persist)saveFavorites();
        return changed;
    }

    private void toggleFavorite(Entry e){
        if(e==null)return;
        try{
            boolean add=!isFavorite(e);
            setFavorite(e,add);
            Toast.makeText(this,add?"Added to My List":"Removed from My List",Toast.LENGTH_SHORT).show();
            // While a stream is playing the home screen is not attached, so the
            // rebuild waits until playback closes. The key is already saved.
            if(playing){favoritesChangedWhilePlaying=true;return;}
            if(gridOverlayView!=null)refreshFocusedFavoriteBadge(e);
            // Rebuild the visible shelf/tab in every category so the heart is
            // correct immediately. Saving from Movies or Live TV used to leave
            // the card unchanged, which looked like the save had failed.
            refreshRows();
            if(gridOverlayView==null)focusTileAfterRefresh(e);
        }catch(Exception ignored){Toast.makeText(this,"Could not update My List",Toast.LENGTH_SHORT).show();}
    }
    private void loadSources(){try{String raw=getPreferences(0).getString("sources_enc",null);if(raw==null)return;byte[] all=Base64.decode(raw,Base64.NO_WRAP);int n=((all[0]&255)<<8)|(all[1]&255);byte[] iv=Arrays.copyOfRange(all,2,2+n),cipher=Arrays.copyOfRange(all,2+n,all.length);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,secretKey(),new GCMParameterSpec(128,iv));JSONArray a=new JSONArray(new String(c.doFinal(cipher),StandardCharsets.UTF_8));for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);sources.add(new Source(o.getString("name"),o.getString("url")));}activeSource=Math.min(getPreferences(0).getInt("active",0),Math.max(0,sources.size()-1));}catch(Exception ignored){sources.clear();}}
    private void saveSources(){try{JSONArray a=new JSONArray();for(Source s:sources){JSONObject o=new JSONObject();o.put("name",s.name);o.put("url",s.url);a.put(o);}Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,secretKey());byte[] iv=c.getIV(),data=c.doFinal(a.toString().getBytes(StandardCharsets.UTF_8));byte[] out=new byte[2+iv.length+data.length];out[0]=(byte)(iv.length>>8);out[1]=(byte)iv.length;System.arraycopy(iv,0,out,2,iv.length);System.arraycopy(data,0,out,2+iv.length,data.length);getPreferences(0).edit().putString("sources_enc",Base64.encodeToString(out,Base64.NO_WRAP)).putInt("active",activeSource).apply();}catch(Exception e){status.setText("Could not securely save playlist URLs.");}}
    private javax.crypto.SecretKey secretKey() throws Exception {KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);String alias="StreamShelfKey";if(ks.containsAlias(alias))return (javax.crypto.SecretKey)ks.getKey(alias,null);KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");g.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());return g.generateKey();}
    @Override public void onLowMemory(){super.onLowMemory();thumbnailMemory.evictAll();}

    @Override protected void onStart(){
        super.onStart();
        if (player != null && playing) {
            player.play();
        }
    }

    @Override protected void onStop(){
        if (player != null) {
            try { player.pause(); } catch (Exception ignored) {}
        }
        super.onStop();
    }

    @Override protected void onPause() {
        super.onPause();
        // Don't release player here - let onStop handle it
        // This prevents issues when temporarily losing focus (e.g., system dialogs)
    }

    @Override protected void onResume() {
        super.onResume();
        applyImmersiveFullscreen();
        if (player != null && playing) {
            player.play();
        }
    }

    @Override protected void onDestroy(){
        playlistRequestId.incrementAndGet();
        playlistWorkers.shutdownNow();
        thumbnailWorkers.shutdownNow();
        if (networkCallback != null && connectivityManager != null) {
            try { connectivityManager.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {}
        }
        // Clean up player UI handlers
        cancelHideControls();
        if (timelineUpdater != null) timelineHandler.removeCallbacks(timelineUpdater);
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (hideControlsHandler != null) hideControlsHandler.removeCallbacksAndMessages(null);
        if (splashPulse != null) splashPulse.cancel();

        releaseWakeLock();
        if (player != null) {
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
        super.onDestroy();
    }
}
