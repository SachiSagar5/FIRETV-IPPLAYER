package tv.sachin.streamshelf;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
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
    private LinearLayout root, content, nav, sidebar, sidebarBrand; private ScrollView mainScroll; private TextView status, sourceLabel, brandWordmark, brandProduct; private TextView searchControl; private String category="Home"; private int activeSource=0; private boolean sidebarExpanded;
    private final ArrayList<TextView> categoryTabs=new ArrayList<>(); private final ArrayList<HorizontalScrollView> shelfRows=new ArrayList<>(); private View firstContentItem;
    private final LruCache<String,Bitmap> thumbnailMemory=new LruCache<String,Bitmap>((int)(Runtime.getRuntime().maxMemory()/1024/8)){@Override protected int sizeOf(String key,Bitmap bitmap){return Math.max(1,bitmap.getByteCount()/1024);}};
    private final ThreadPoolExecutor thumbnailWorkers = new ThreadPoolExecutor(
        4, 8, 30, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(64),
        new ThreadPoolExecutor.DiscardOldestPolicy()
    );
    private ExoPlayer player; private PlayerView playerView; private FrameLayout playerOverlay; private View previousHomeFocus; private boolean playing=false;
    private Entry currentEntry; private LinearLayout playerLoadingPanel,playerErrorPanel; private TextView playerLoadingText; private Button retryPlaybackButton, browseBtn;
    private final ExecutorService playlistWorkers=Executors.newFixedThreadPool(2);
    private final AtomicInteger playlistRequestId=new AtomicInteger();
    private PowerManager.WakeLock wakeLock;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean networkAvailable = true;

    private static class Source { String name,url; Source(String n,String u){name=n;url=u;} }
    private static class Entry { String name,url,group,logo,type; Entry(String n,String u,String g,String l,String t){name=n;url=u;group=g;logo=l;type=t;} }

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        setupExceptionHandler();
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
                fw.write("Version: 1.4.2\n");
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
        sidebar=new LinearLayout(this);sidebar.setOrientation(LinearLayout.VERTICAL);sidebar.setPadding(dp(8),dp(8),dp(8),dp(8));sidebar.setBackgroundColor(0xff080c12);root.addView(sidebar,new LinearLayout.LayoutParams(dp(64),-1));
        sidebarBrand=new LinearLayout(this);sidebarBrand.setGravity(Gravity.CENTER_VERTICAL);sidebarBrand.setPadding(dp(4),0,0,dp(27));sidebar.addView(sidebarBrand,new LinearLayout.LayoutParams(-1,dp(68)));TextView mark=text("▶",27,RED);sidebarBrand.addView(mark);brandWordmark=text("  IPTV ",21,WHITE);brandWordmark.setTypeface(null,Typeface.BOLD);sidebarBrand.addView(brandWordmark);brandProduct=text("Player",21,RED);brandProduct.setTypeface(null,Typeface.BOLD);sidebarBrand.addView(brandProduct);
        nav=new LinearLayout(this);nav.setOrientation(LinearLayout.VERTICAL);sidebar.addView(nav,new LinearLayout.LayoutParams(-1,0,1));String[][] tabs={{"Home","⌂"},{"Movies","▣"},{"Series","▤"},{"Live TV","▣"},{"My List","♡"},{"Settings","⚙"}};for(String[] item:tabs){String tab=item[0];String icon=item[1];TextView v=text(icon,21,WHITE);v.setTag(tab);v.setContentDescription(tab);v.setFocusable(true);v.setClickable(true);v.setGravity(Gravity.CENTER);v.setId(View.generateViewId());v.setPadding(dp(5),0,dp(5),0);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(47));p.setMargins(0,0,0,dp(5));nav.addView(v,p);setFocusStyle(v,Color.TRANSPARENT,7);v.setOnClickListener(w->{if(tab.equals("Settings")){sourceDialog();return;}category=tab;updateCategoryTabs();refreshRows();focusFirstContent();});categoryTabs.add(v);}updateCategoryTabs();
        sourceLabel=text("SOURCE  ·  "+(sources.isEmpty()?"Add playlist":sources.get(Math.min(activeSource,sources.size()-1)).name),12,MUTED);sourceLabel.setSingleLine(true);sourceLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);sourceLabel.setFocusable(true);sourceLabel.setClickable(true);sourceLabel.setId(View.generateViewId());sourceLabel.setPadding(dp(9),dp(12),dp(6),dp(7));setFocusStyle(sourceLabel,Color.TRANSPARENT,6);sourceLabel.setOnClickListener(v->{if(sources.isEmpty())sourceDialog();else manageSources();});sidebar.addView(sourceLabel,new LinearLayout.LayoutParams(-1,dp(46)));
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus,newFocus)->setSidebarExpanded(isInsideView(newFocus,nav)));
        setSidebarExpanded(false);
        LinearLayout main=new LinearLayout(this);main.setOrientation(LinearLayout.VERTICAL);root.addView(main,new LinearLayout.LayoutParams(0,-1,1));LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);top.setPadding(dp(16),dp(3),dp(25),0);main.addView(top,new LinearLayout.LayoutParams(-1,dp(54)));searchControl=text("⌕",29,WHITE);searchControl.setGravity(Gravity.CENTER);searchControl.setFocusable(true);searchControl.setClickable(true);searchControl.setContentDescription("Search playlist");searchControl.setId(View.generateViewId());searchControl.setPadding(dp(12),0,dp(12),0);setFocusStyle(searchControl,Color.TRANSPARENT,7);searchControl.setOnClickListener(v->searchDialog());top.addView(searchControl,new LinearLayout.LayoutParams(dp(48),dp(44)));TextView settings=text("⚙",22,WHITE);settings.setGravity(Gravity.CENTER);settings.setFocusable(true);settings.setClickable(true);settings.setContentDescription("Settings and playlist sources");settings.setPadding(dp(8),0,dp(8),0);setFocusStyle(settings,Color.TRANSPARENT,7);settings.setOnClickListener(v->sourceDialog());top.addView(settings,new LinearLayout.LayoutParams(dp(48),dp(44)));TextView clock=text(new java.text.SimpleDateFormat("hh:mm a",Locale.getDefault()).format(new Date()),14,WHITE);clock.setGravity(Gravity.CENTER);top.addView(clock,new LinearLayout.LayoutParams(dp(94),dp(44)));
        mainScroll=new ScrollView(this);mainScroll.setFillViewport(true);mainScroll.setVerticalScrollBarEnabled(false);mainScroll.setOnScrollChangeListener((View v,int x,int y,int oldX,int oldY)->{for(HorizontalScrollView shelf:shelfRows)loadVisibleThumbnails(shelf);});main.addView(mainScroll,new LinearLayout.LayoutParams(-1,0,1));content=new LinearLayout(this);content.setPadding(dp(14),dp(1),dp(22),dp(16));content.setOrientation(LinearLayout.VERTICAL);content.setClipChildren(false);mainScroll.addView(content);
        status=text("",12,MUTED);status.setPadding(dp(15),0,0,0);LinearLayout footer=new LinearLayout(this);footer.setGravity(Gravity.CENTER_VERTICAL|Gravity.RIGHT);footer.setPadding(dp(12),0,dp(24),dp(7));TextView hints=text("✥  Navigate     ⓞ  Select     ↶  Back     MENU  My List",12,0xffc5c8d0);hints.setGravity(Gravity.CENTER);hints.setPadding(dp(16),dp(7),dp(16),dp(7));hints.setBackground(rounded(0xff10151d,24));footer.addView(status,new LinearLayout.LayoutParams(0,dp(32),1));footer.addView(hints);main.addView(footer,new LinearLayout.LayoutParams(-1,dp(43)));
        requestInitialFocus();
    }
    private void showWelcome(){content.removeAllViews();TextView t=text("Your screen for live TV, movies and series.",27,WHITE);t.setTypeface(null,Typeface.BOLD);t.setPadding(0,dp(30),0,dp(12));content.addView(t);TextView sub=text("Connect a playlist to start building your home screen.",16,MUTED);content.addView(sub);Button b=button("Add playlist");b.setId(View.generateViewId());b.setOnClickListener(v->sourceDialog());LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(54));p.setMargins(0,dp(18),0,0);content.addView(b,p);firstContentItem=b;}
    private Button button(String label){Button b=new Button(this);b.setText(label);b.setTextColor(WHITE);b.setBackground(rounded(RED,6));b.setFocusable(true);b.setClickable(true);b.setFocusableInTouchMode(true);setFocusStyle(b,RED,6);return b;}
    private void refreshRows(){content.animate().cancel();content.setAlpha(0f);content.removeAllViews();shelfRows.clear();firstContentItem=null;sourceLabel.setText("SOURCE  ·  "+(sources.isEmpty()?"Add playlist":sources.get(Math.min(activeSource,sources.size()-1)).name));
        if(sources.isEmpty()){showWelcome();wireMainFocus();animateContentIn();return;}if(category.equals("Home")){renderHome();wireMainFocus();animateContentIn();return;}
        ArrayList<Entry> shown=new ArrayList<>();for(Entry e:entries){if(category.equals("My List")?isFavorite(e):e.type.equals(category.equals("Live TV")?"Live":category))shown.add(e);}
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
        if(shown.isEmpty()){TextView empty=text(entries.isEmpty()?"Loading your playlist…":category.equals("My List")?"Your list is empty. Focus a title and press MENU to save it.":"No "+category.toLowerCase(Locale.ROOT)+" found in this playlist.",20,MUTED);empty.setPadding(0,dp(22),0,0);empty.setFocusable(true);empty.setId(View.generateViewId());content.addView(empty);firstContentItem=empty;wireMainFocus();animateContentIn();return;}
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

            TextView name = text(group + "  ›", 20, WHITE);
            name.setTypeface(null, Typeface.BOLD);
            heading.addView(name, new LinearLayout.LayoutParams(0, dp(36), 1));

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
    private void renderHome(){
        Entry hero = featuredEntry();
        content.addView(buildHero(hero));
        
        // Add Continue Watching row if there's progress
        addContinueWatchingRow();
        
        appendHomeShelf("Movies","Movies",false);
        appendHomeShelf("Series","Series",false);
        appendHomeGrid("Live TV","Live"); // Live TV as card grid
        if(entries.isEmpty()){TextView empty=text("Loading your playlist…",16,MUTED);empty.setPadding(dp(4),dp(12),0,dp(10));content.addView(empty);}
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
        TextView name = text("Continue Watching  ›", 20, WHITE);
        name.setTypeface(null, Typeface.BOLD);
        heading.addView(name, new LinearLayout.LayoutParams(0, dp(36), 1));
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
        firstContentItem = null;
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
        try {
            if (items == null || items.isEmpty()) return;
            
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
            FrameLayout gridOverlay = new FrameLayout(this);
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
            backBtn.setOnClickListener(v -> {
                gridOverlay.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                    recycleGridViews(gridOverlay);
                    setContentView(root);
                    if (previousHomeFocus != null && previousHomeFocus.isShown() && previousHomeFocus.isFocusable()) {
                        previousHomeFocus.requestFocus();
                    } else {
                        requestInitialFocus();
                    }
                }).start();
            });
            setFocusStyleView(backBtn, dp(40));
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
            gridArtHeight = gridTileHeight - dp(40); // Space for title
            
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
                    if (firstContentItem != null) firstContentItem.requestFocus();
                }
            });
            
            // Override key handling for grid
            gridOverlay.setFocusableInTouchMode(true);
            gridOverlay.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            gridOverlay.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (keyCode == KeyEvent.KEYCODE_BACK) {
                        gridOverlay.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                            recycleGridViews(gridOverlay);
                            setContentView(root);
                            if (previousHomeFocus != null && previousHomeFocus.isShown() && previousHomeFocus.isFocusable()) {
                                previousHomeFocus.requestFocus();
                            } else {
                                requestInitialFocus();
                            }
                        }).start();
                        return true;
                    }
                }
                return false;
            });
            
            setContentView(gridOverlay);
            gridOverlay.animate().alpha(1f).setDuration(200).start();
            
            // Focus first item
            gridOverlay.post(() -> {
                if (firstContentItem != null) firstContentItem.requestFocus();
                else backBtn.requestFocus();
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
                holder.tile.setOnClickListener(v -> {
                    gridScroll.animate().alpha(0f).setDuration(150).withEndAction(() -> {
                        recycleGridViews((FrameLayout) gridScroll.getParent());
                        setContentView(root);
                        if (previousHomeFocus != null && previousHomeFocus.isShown() && previousHomeFocus.isFocusable()) {
                            previousHomeFocus.requestFocus();
                        } else {
                            requestInitialFocus();
                        }
                        play(e);
                    }).start();
                });
                
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
                tileParams.setMargins(0, 0, dp(8), 0);
                row.addView(holder.tile, tileParams);
                
                // Track first focusable item
                if (firstContentItem == null) firstContentItem = holder.tile;
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
            LinearLayout.LayoutParams artParams = new LinearLayout.LayoutParams(-1, dp(104));
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
            art.post(() -> loadThumbnail(image, e.logo, art.getWidth(), art.getHeight(), placeholder));
            
            // Progress bar overlay
            if (duration > 0) {
                View progressBg = new View(this);
                progressBg.setBackgroundColor(0x88000000);
                FrameLayout.LayoutParams bgParams = new FrameLayout.LayoutParams(-1, dp(4), Gravity.BOTTOM);
                art.addView(progressBg, bgParams);
                
                View progressFill = new View(this);
                progressFill.setBackgroundColor(RED);
                float pct = Math.min(1f, (float) position / duration);
                FrameLayout.LayoutParams fillParams = new FrameLayout.LayoutParams((int)(dp(118) * pct), dp(4), Gravity.BOTTOM);
                art.addView(progressFill, fillParams);
                
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
            
            TextView titleText = text(e.name, 12, WHITE);
            titleText.setMaxLines(1);
            titleText.setEllipsize(TextUtils.TruncateAt.END);
            titleText.setPadding(dp(5), dp(2), dp(5), dp(3));
            tile.addView(titleText);
            
            LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(dp(118), dp(130));
            tileParams.setMargins(0, 0, dp(8), dp(7));
            row.addView(tile, tileParams);
            
            if (firstContentItem == null) firstContentItem = tile;
        }
        
        if (entries.size() < 6) {
            TextView more = text("See All  ›", 15, WHITE);
            more.setGravity(Gravity.CENTER);
            more.setFocusable(true);
            more.setClickable(true);
            setFocusStyle(more, 0xff131a23, 8);
            more.setOnClickListener(v -> Toast.makeText(this, "Continue Watching", Toast.LENGTH_SHORT).show());
            row.addView(more, new LinearLayout.LayoutParams(dp(95), dp(130)));
        }
        hsv.postDelayed(() -> loadVisibleThumbnails(hsv), 100);
    }
    private FrameLayout buildHero(Entry e){
        FrameLayout hero=new FrameLayout(this);
        hero.setBackground(rounded(0xff10151d,12));
        hero.setClipToOutline(true);
        hero.setClipChildren(true);
        LinearLayout.LayoutParams heroParams=new LinearLayout.LayoutParams(-1,dp(220));
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
        details.setPadding(dp(24),dp(12),0,dp(12));
        FrameLayout.LayoutParams detailParams=new FrameLayout.LayoutParams(dp(450),-1,Gravity.LEFT|Gravity.CENTER_VERTICAL);
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

        TextView name = text(title + "  ›", 20, WHITE);
        name.setTypeface(null, Typeface.BOLD);
        heading.addView(name, new LinearLayout.LayoutParams(0, dp(36), 1));

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

    private void appendHomeGrid(String title, String type) {
        ArrayList<Entry> items = new ArrayList<>();
        for (Entry e : entries) if (e.type.equals(type)) items.add(e);
        if (items.isEmpty()) return;

        // Sort Live TV: Kannada channels first
        if (type.equals("Live")) {
            items.sort((a, b) -> {
                boolean aKannada = isKannadaChannel(a);
                boolean bKannada = isKannadaChannel(b);
                if (aKannada && !bKannada) return -1;
                if (!aKannada && bKannada) return 1;
                return 0;
            });
        }

        // Category icon mapping
        int iconRes = 0;
        int iconColor = WHITE;
        switch (type) {
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

        TextView name = text(title + "  ›", 20, WHITE);
        name.setTypeface(null, Typeface.BOLD);
        heading.addView(name, new LinearLayout.LayoutParams(0, dp(36), 1));

        TextView count = text(items.size() + " channels", 13, 0xFF888888);
        count.setPadding(0, 0, dp(12), 0);
        heading.addView(count);

        TextView seeAll = text("See All  ›", 13, 0xffc7cad1);
        seeAll.setFocusable(true);
        seeAll.setClickable(true);
        seeAll.setGravity(Gravity.CENTER);
        seeAll.setPadding(dp(10), dp(5), dp(10), dp(5));
        setFocusStyle(seeAll, Color.TRANSPARENT, 6);
        seeAll.setTag(items);
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

        // Grid container (4 columns for Live TV)
        LinearLayout gridContainer = new LinearLayout(this);
        gridContainer.setOrientation(LinearLayout.VERTICAL);
        gridContainer.setPadding(dp(14), dp(8), dp(22), dp(16));
        gridContainer.setClipChildren(false);
        content.addView(gridContainer);

        final int columns = 4;
        final int screenWidth = getResources().getDisplayMetrics().widthPixels;
        final int tileWidth = (screenWidth - dp(14) - dp(22) - dp(12) * (columns - 1)) / columns;
        final int tileHeight = dp(150);
        final int artHeight = tileHeight - dp(40);

        // Show first 12 items (3 rows x 4 columns) with "See All" for more
        int maxItems = Math.min(items.size(), 12);
        for (int rowStart = 0; rowStart < maxItems; rowStart += columns) {
            int rowEnd = Math.min(rowStart + columns, maxItems);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            rowParams.setMargins(0, 0, 0, dp(12));
            gridContainer.addView(row, rowParams);

            for (int i = rowStart; i < rowEnd; i++) {
                Entry e = items.get(i);
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
                LinearLayout.LayoutParams artParams = new LinearLayout.LayoutParams(-1, artHeight);
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

                // LIVE badge
                TextView badge = text("LIVE", 10, WHITE);
                badge.setTypeface(null, Typeface.BOLD);
                badge.setPadding(dp(7), dp(3), dp(7), dp(3));
                badge.setBackground(rounded(RED, 4));
                FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
                bp.setMargins(dp(7), dp(7), 0, 0);
                art.addView(badge, bp);

                TextView titleText = text(e.name, 12, WHITE);
                titleText.setTypeface(null, Typeface.BOLD);
                titleText.setMaxLines(2);
                titleText.setEllipsize(TextUtils.TruncateAt.END);
                titleText.setPadding(dp(10), dp(6), dp(10), dp(8));
                tile.addView(titleText);

                LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(tileWidth, tileHeight);
                tileParams.setMargins(0, 0, dp(12), 0);
                row.addView(tile, tileParams);

                if (firstContentItem == null) firstContentItem = tile;

                // Load thumbnail
                art.post(() -> loadThumbnail(image, e.logo, art.getWidth(), art.getHeight(), placeholder));
            }
        }

        // If more items exist, add a "See All" tile at the end
        if (items.size() > maxItems) {
            LinearLayout moreTile = new LinearLayout(this);
            moreTile.setOrientation(LinearLayout.VERTICAL);
            moreTile.setBackground(rounded(0xff131a23, 8));
            moreTile.setFocusable(true);
            moreTile.setClickable(true);
            moreTile.setTag(items);
            moreTile.setId(View.generateViewId());
            setFocusStyle(moreTile, 0xff131a23, 8);
            moreTile.setOnClickListener(v -> {
                ArrayList<Entry> allItems = (ArrayList<Entry>) v.getTag();
                showAllGrid(title, allItems);
            });

            TextView moreText = text("See All\n" + items.size() + " channels", 14, WHITE);
            moreText.setGravity(Gravity.CENTER);
            moreText.setPadding(dp(10), 0, dp(10), 0);
            moreTile.addView(moreText, new LinearLayout.LayoutParams(-1, -1, 1));

            // Add icon
            ImageView moreIcon = new ImageView(this);
            moreIcon.setImageResource(R.drawable.ic_live_tv);
            moreIcon.setColorFilter(iconColor);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(48), dp(48));
            iconParams.gravity = Gravity.CENTER;
            iconParams.setMargins(0, dp(20), 0, dp(8));
            moreTile.addView(moreIcon, iconParams);

            LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(tileWidth, tileHeight);
            tileParams.setMargins(0, 0, dp(12), 0);
            // Add to last row if space, otherwise new row
            LinearLayout lastRow = (LinearLayout) gridContainer.getChildAt(gridContainer.getChildCount() - 1);
            if (lastRow.getChildCount() < columns) {
                lastRow.addView(moreTile, tileParams);
            } else {
                LinearLayout newRow = new LinearLayout(this);
                newRow.setOrientation(LinearLayout.HORIZONTAL);
                newRow.setGravity(Gravity.CENTER_HORIZONTAL);
                LinearLayout.LayoutParams newRowParams = new LinearLayout.LayoutParams(-1, -2);
                newRowParams.setMargins(0, 0, 0, dp(12));
                gridContainer.addView(newRow, newRowParams);
                newRow.addView(moreTile, tileParams);
            }
        }
    }
    private HorizontalScrollView createShelfScroll(){HorizontalScrollView hsv=new HorizontalScrollView(this);hsv.setHorizontalScrollBarEnabled(false);hsv.setClipToPadding(false);hsv.setClipChildren(false);LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setClipChildren(false);row.setClipToPadding(false);hsv.addView(row);hsv.setOnScrollChangeListener((View v,int x,int y,int oldX,int oldY)->loadVisibleThumbnails(hsv));return hsv;}
    private void addHomeTiles(HorizontalScrollView hsv,LinearLayout row,ArrayList<Entry> shelf,int start,boolean live){int batchSize=start==0?6:12;int end=Math.min(start+batchSize,shelf.size());int width=live?dp(104):dp(118),height=live?dp(68):dp(70);for(int i=start;i<end;i++){Entry e=shelf.get(i);LinearLayout tile=new LinearLayout(this);tile.setOrientation(LinearLayout.VERTICAL);tile.setBackground(rounded(0xff0d141d,8));tile.setClipChildren(false);tile.setFocusable(true);tile.setClickable(true);tile.setTag(e);tile.setId(View.generateViewId());setFocusStyle(tile,0xff0d141d,8);tile.setOnClickListener(v->play(e));FrameLayout art=new FrameLayout(this);art.setBackground(rounded(0xff131a23,8));art.setClipToOutline(true);LinearLayout.LayoutParams artParams=new LinearLayout.LayoutParams(-1,height);artParams.setMargins(dp(2),dp(2),dp(2),dp(2));tile.addView(art,artParams);TextView placeholder=text("",25,WHITE);styleArtworkPlaceholder(placeholder,e);art.addView(placeholder,new FrameLayout.LayoutParams(-1,-1));ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setAdjustViewBounds(false);image.setTag(e);art.addView(image,new FrameLayout.LayoutParams(-1,-1));placeholder.bringToFront();TextView titleText=text(e.name,12,WHITE);titleText.setMaxLines(1);titleText.setEllipsize(android.text.TextUtils.TruncateAt.END);titleText.setPadding(dp(5),dp(2),dp(5),dp(3));tile.addView(titleText);LinearLayout.LayoutParams tileParams=new LinearLayout.LayoutParams(width,dp(96));tileParams.setMargins(0,0,dp(8),dp(7));row.addView(tile,tileParams);if(firstContentItem==null)firstContentItem=tile;}if(end<shelf.size()){TextView more=text("See All  ›",15,WHITE);more.setGravity(Gravity.CENTER);more.setFocusable(true);more.setClickable(true);setFocusStyle(more,0xff131a23,8);more.setTag(shelf);more.setOnClickListener(v->{ArrayList<Entry> allItems=(ArrayList<Entry>)v.getTag();if(allItems==null||allItems.isEmpty())return;String shelfTitle=live?"Live TV":allItems.get(0).type;showAllGrid(shelfTitle,allItems);});row.addView(more,new LinearLayout.LayoutParams(dp(95),dp(96)));}hsv.postDelayed(()->loadVisibleThumbnails(hsv),100);}
    private void wireMainFocus(){if(firstContentItem==null)return;TextView selected=currentCategoryTab();if(selected!=null){selected.setNextFocusRightId(firstContentItem.getId());firstContentItem.setNextFocusLeftId(selected.getId());}if(searchControl!=null)searchControl.setNextFocusDownId(firstContentItem.getId());}
    private void showEntryInfo(Entry e){new AlertDialog.Builder(this).setTitle(e.name).setMessage(e.type+(e.group.isEmpty()?"":"\n"+e.group)).setPositiveButton("Watch Now",(d,w)->play(e)).setNegativeButton("Close",null).setNeutralButton(isFavorite(e)?"Remove from My List":"Add to My List",(d,w)->{toggleFavorite(e);}).show();}
    private void addShelfTiles(HorizontalScrollView hsv,LinearLayout row,ArrayList<Entry> shelf,int start,boolean portrait){
        int batchSize=start==0?8:12;int end=Math.min(start+batchSize,shelf.size());int width=portrait?dp(146):dp(216),artHeight=portrait?dp(184):dp(104);
        for(int i=start;i<end;i++){
            Entry e=shelf.get(i);LinearLayout tile=new LinearLayout(this);tile.setOrientation(LinearLayout.VERTICAL);tile.setBackground(rounded(PANEL,9));tile.setClipChildren(false);tile.setFocusable(true);tile.setClickable(true);tile.setTag(e);tile.setId(View.generateViewId());setFocusStyle(tile,PANEL,9);tile.setOnClickListener(v->play(e));
            if(firstContentItem==null)firstContentItem=tile;
            FrameLayout art=new FrameLayout(this);art.setBackground(rounded(0xff17191e,8));art.setClipToOutline(true);LinearLayout.LayoutParams artParams=new LinearLayout.LayoutParams(-1,artHeight);artParams.setMargins(dp(5),dp(5),dp(5),dp(4));tile.addView(art,artParams);
            TextView placeholder=text("",32,WHITE);styleArtworkPlaceholder(placeholder,e);art.addView(placeholder,new FrameLayout.LayoutParams(-1,-1));
            ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setAdjustViewBounds(false);image.setContentDescription(e.name+" artwork");image.setTag(e);art.addView(image,new FrameLayout.LayoutParams(-1,-1));
            placeholder.bringToFront();
            if(e.type.equals("Live")){TextView badge=text("LIVE",10,WHITE);badge.setTypeface(null,Typeface.BOLD);badge.setPadding(dp(7),dp(3),dp(7),dp(3));badge.setBackground(rounded(RED,4));FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.LEFT);bp.setMargins(dp(7),dp(7),0,0);art.addView(badge,bp);}
            TextView title=text(e.name,14,WHITE);title.setTypeface(null,Typeface.BOLD);title.setMaxLines(2);title.setEllipsize(android.text.TextUtils.TruncateAt.END);title.setPadding(dp(10),dp(4),dp(10),dp(8));tile.addView(title,new LinearLayout.LayoutParams(-1,-2));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(width,portrait?dp(224):dp(150));p.setMargins(0,0,dp(12),dp(8));row.addView(tile,p);
        }
        if(end<shelf.size()){
            LinearLayout more=new LinearLayout(this);more.setGravity(Gravity.CENTER);more.setOrientation(LinearLayout.VERTICAL);more.setBackground(rounded(0xff292b31,9));more.setFocusable(true);more.setClickable(true);setFocusStyle(more,0xff292b31,9);
            TextView arrow=text("›",42,RED);arrow.setGravity(Gravity.CENTER);more.addView(arrow);TextView label=text("LOAD MORE",12,WHITE);label.setTypeface(null,Typeface.BOLD);label.setGravity(Gravity.CENTER);more.addView(label);
            more.setOnClickListener(v->{row.removeView(more);int firstAdded=row.getChildCount();addShelfTiles(hsv,row,shelf,end,portrait);if(firstAdded<row.getChildCount())row.getChildAt(firstAdded).requestFocus();});LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(width,portrait?dp(224):dp(150));mp.setMargins(0,0,dp(12),dp(8));row.addView(more,mp);
        }
        hsv.postDelayed(()->loadVisibleThumbnails(hsv),100);
    }
    private void loadVisibleThumbnails(HorizontalScrollView shelf) {
        if (shelf.getVisibility() != View.VISIBLE || shelf.getChildCount() == 0
                || !(shelf.getChildAt(0) instanceof ViewGroup)) return;
        ViewGroup row = (ViewGroup) shelf.getChildAt(0);
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (!(child instanceof LinearLayout)) continue;
            LinearLayout tile = (LinearLayout) child;
            if (tile.getChildCount() == 0 || !(tile.getChildAt(0) instanceof FrameLayout)) continue;
            FrameLayout art = (FrameLayout) tile.getChildAt(0);
            Rect visible = new Rect();
            if (!art.getGlobalVisibleRect(visible)) continue;
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
    private void animateContentIn(){content.animate().alpha(1f).setDuration(180).start();}
    private void updateCategoryTabs(){for(TextView tab:categoryTabs){boolean selected=category.equals(String.valueOf(tab.getTag()));tab.setTextColor(selected?WHITE:0xffd0d2d7);tab.setTypeface(null,selected?Typeface.BOLD:Typeface.NORMAL);tab.setBackground(rounded(selected?0xffa90d19:Color.TRANSPARENT,7));}}
    private boolean isInsideView(View child, ViewGroup parent){for(View view=child;view!=null;){if(view==parent)return true;android.view.ViewParent next=view.getParent();view=next instanceof View?(View)next:null;}return false;}
    private void setSidebarExpanded(boolean expanded){
        if(sidebar==null)return;
        sidebarExpanded=expanded;
        ViewGroup.LayoutParams params=sidebar.getLayoutParams();
        params.width=dp(expanded?180:64);
        sidebar.setLayoutParams(params);
        sidebarBrand.setGravity(expanded?Gravity.CENTER_VERTICAL:Gravity.CENTER);
        brandWordmark.setVisibility(expanded?View.VISIBLE:View.GONE);
        brandProduct.setVisibility(expanded?View.VISIBLE:View.GONE);
        sourceLabel.setVisibility(expanded?View.VISIBLE:View.GONE);
        for(TextView tab:categoryTabs){
            String name=String.valueOf(tab.getTag());
            String icon="Home".equals(name)?"⌂":"Movies".equals(name)||"Live TV".equals(name)?"▣":"Series".equals(name)?"▤":"My List".equals(name)?"♡":"⚙";
            tab.setText(expanded?icon+"   "+name:icon);
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
                // Don't show white border for menu items (transparent background)
                if (!isMenuItem) {
                    bg.setStroke(dp(2), 0xFFFFFFFF);
                } else {
                    bg.setStroke(dp(1), 0x00FFFFFF); // Transparent stroke
                }
                v.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
                v.setElevation(0);
            }
            v.setBackground(bg);
            v.animate().cancel();
            if(focused){
                v.animate()
                    .scaleX(1.1f).scaleY(1.1f)
                    .translationZ(dp(12))
                    .setDuration(180)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.1f))
                    .start();
            } else {
                v.animate()
                    .scaleX(1f).scaleY(1f)
                    .translationZ(0)
                    .setDuration(180)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f))
                    .start();
            }
        });
        // Enable hardware acceleration for smooth animations
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }
    private TextView currentCategoryTab(){for(TextView tab:categoryTabs)if(category.equals(String.valueOf(tab.getTag())))return tab;return categoryTabs.isEmpty()?sourceLabel:categoryTabs.get(0);}
    private void requestInitialFocus(){if(nav==null)return;nav.post(()->{if(getCurrentFocus()==null){TextView target=currentCategoryTab();if(target!=null)target.requestFocus();}});}
    private void focusFirstContent(){content.post(()->{if(firstContentItem!=null)firstContentItem.requestFocus();else if(searchControl!=null)searchControl.requestFocus();});}
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

                if (player != null && (key == android.view.KeyEvent.KEYCODE_MEDIA_REWIND
                        || key == android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
                        && player.isCurrentMediaItemSeekable()) {
                    long position = player.getCurrentPosition();
                    long duration = player.getDuration();
                    long next = position + (key == android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ? 10000 : -10000);
                    player.seekTo(Math.max(0, duration > 0 ? Math.min(duration, next) : next));
                    showControls(true);
                    return true;
                }

                // D-pad navigation for custom player controls
                if (key == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                        || key == android.view.KeyEvent.KEYCODE_ENTER
                        || key == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    // Show controls if hidden, otherwise click focused view
                    if (!controlsVisible) {
                        showControls(true);
                    } else {
                        View focused = getCurrentFocus();
                        if (focused != null && focused.isClickable()) {
                            focused.performClick();
                        }
                    }
                    return true;
                }

                // D-pad Up/Down: channel switching for live TV, control navigation for VOD
                if (key == android.view.KeyEvent.KEYCODE_DPAD_UP || key == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (!controlsVisible) {
                        showControls(true);
                        return true;
                    }
                    // For live TV, Up/Down switches channels (prev/next) regardless of focus
                    if (currentEntry != null && "Live".equals(currentEntry.type)) {
                        switchChannel(key == android.view.KeyEvent.KEYCODE_DPAD_DOWN);
                        return true;
                    }
                    handleVerticalNavigation(key == android.view.KeyEvent.KEYCODE_DPAD_DOWN);
                    return true;
                }

                // D-pad Left/Right: seek when timeline focused, otherwise navigate between controls
                if (key == android.view.KeyEvent.KEYCODE_DPAD_LEFT || key == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (!controlsVisible) {
                        showControls(true);
                        return true;
                    }
                    View focused = getCurrentFocus();
                    if (focused == progressBar) {
                        // Seeking handled by SeekBar's internal key handling
                        return false; // Let SeekBar handle it
                    }
                    // Handle error panel navigation
                    if (playerErrorPanel != null && playerErrorPanel.getVisibility() == View.VISIBLE) {
                        if (focused == retryPlaybackButton) {
                            browseBtn.requestFocus();
                        } else if (focused == browseBtn) {
                            retryPlaybackButton.requestFocus();
                        }
                        return true;
                    }
                    handleHorizontalNavigation(key == android.view.KeyEvent.KEYCODE_DPAD_RIGHT);
                    return true;
                }

                // D-pad Up/Down on error panel
                if ((key == android.view.KeyEvent.KEYCODE_DPAD_UP || key == android.view.KeyEvent.KEYCODE_DPAD_DOWN)
                        && playerErrorPanel != null && playerErrorPanel.getVisibility() == View.VISIBLE) {
                    View focused = getCurrentFocus();
                    if (focused == retryPlaybackButton || focused == browseBtn) {
                        // Toggle between the two buttons
                        if (focused == retryPlaybackButton) {
                            browseBtn.requestFocus();
                        } else {
                            retryPlaybackButton.requestFocus();
                        }
                        return true;
                    }
                }

                // Any other key while playing: show controls
                showControls(true);
                return true;
            }

            // Non-playing keys (existing logic)
            if (!playing && key == android.view.KeyEvent.KEYCODE_SEARCH) {
                searchDialog();
                return true;
            }
            if (!playing && key == android.view.KeyEvent.KEYCODE_MENU) {
                View focused = getCurrentFocus();
                Object tag = focused == null ? null : focused.getTag();
                if (tag instanceof Entry) {
                    toggleFavorite((Entry) tag);
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
                    || key == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)) {
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
        for (View ctrl : controlOrder) {
            if (ctrl == v) return true;
        }
        return false;
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
            // Center button - go to bottom controls
            if (controlOrder.length > 0) {
                controlOrder[0].requestFocus();
            }
        }
    }
    private void loadPlaylist(){if(sources.isEmpty())return;final Source s=sources.get(activeSource);final int request=playlistRequestId.incrementAndGet();status.setText("Loading "+s.name+"…");entries.clear();refreshRows();playlistWorkers.execute(()->{ArrayList<Entry> parsed=new ArrayList<>();String error=null;HttpURLConnection c=null;try{URL u=new URL(s.url);c=(HttpURLConnection)u.openConnection();c.setConnectTimeout(12000);c.setReadTimeout(18000);c.setRequestProperty("User-Agent","StreamShelf/1.2");c.connect();if(c.getResponseCode()<200||c.getResponseCode()>=300)throw new IOException("HTTP "+c.getResponseCode());try(BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8),32768)){String line,info=null;while((line=r.readLine())!=null){line=line.trim();if(line.startsWith("#EXTINF")){info=line;}else if(!line.isEmpty()&&!line.startsWith("#")&&info!=null){String name=afterComma(info);String group=attr(info,GROUP_TITLE_ATTR);String logo=attr(info,TVG_LOGO_ATTR);String type=classify(group,name);parsed.add(new Entry(name,line,group,logo,type));info=null;}}}}catch(Exception e){error=e.getMessage()==null?"Could not load playlist":e.getMessage();}finally{if(c!=null)c.disconnect();}final String err=error;runOnUiThread(()->{if(request!=playlistRequestId.get()||isFinishing()||isDestroyed())return;entries.clear();entries.addAll(parsed);status.setText(err==null?parsed.size()+" items loaded":"Playlist error: "+err);refreshRows();if(parsed.isEmpty()&&err==null)status.setText("No playable entries found. Check the M3U format and URLs.");});});}
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
            playerOverlay = new FrameLayout(this);
            playerOverlay.setBackgroundColor(Color.BLACK);
            playerOverlay.setAlpha(0f);

            playerView = new PlayerView(this);
            playerView.setUseController(false); // Disable default controller - we use custom
            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
            playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER);
            playerView.setFocusable(false);
            playerOverlay.addView(playerView, new FrameLayout.LayoutParams(-1, -1));

            buildCustomPlayerUI();
            setContentView(playerOverlay);
            playerOverlay.animate().alpha(1f).setDuration(140).start();
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
    private TextView rewindBtn, playPauseBtn, forwardBtn, subtitlesBtn, qualityBtn, fullscreenBtn;
    private TextView rewindLabel, playPauseLabel, forwardLabel, subtitlesLabel, qualityLabel, fullscreenLabel;
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
        settingsBtn = createIconButton(R.drawable.ic_settings, "Settings");
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
        timelineParams.setMargins(0, 0, 0, dp(96)); // Above bottom controls (reduced from 120)
        playerOverlay.addView(timelineContainer, timelineParams);

        // --- BOTTOM CONTROLS ---
        bottomControls = new LinearLayout(this);
        bottomControls.setOrientation(LinearLayout.HORIZONTAL);
        bottomControls.setGravity(Gravity.CENTER);
        bottomControls.setPadding(dp(32), dp(16), dp(32), dp(24));
        bottomControls.setBackground(bottomGradient);

        // Create control buttons with labels
        rewindBtn = createControlButton(R.drawable.ic_rewind, "10s\nRewind", "Rewind 10 seconds");
        playPauseBtn = createControlButton(R.drawable.ic_pause, "Pause", "Pause");
        forwardBtn = createControlButton(R.drawable.ic_forward, "10s\nForward", "Forward 10 seconds");
        subtitlesBtn = createControlButton(R.drawable.ic_subtitles, "Subtitles\nOff", "Subtitles: Off");
        qualityBtn = createControlButton(R.drawable.ic_quality, "Quality\nAuto", "Quality: Auto");
        fullscreenBtn = createControlButton(R.drawable.ic_fullscreen, "Fullscreen", "Fullscreen");

        TextView[] controls = {rewindBtn, playPauseBtn, forwardBtn, subtitlesBtn, qualityBtn, fullscreenBtn};
        for (TextView control : controls) {
            LinearLayout.LayoutParams controlParams = new LinearLayout.LayoutParams(0, dp(64), 1);
            bottomControls.addView(control, controlParams);
        }

        // Set click listeners
        rewindBtn.setOnClickListener(v -> seekRelative(-10000));
        playPauseBtn.setOnClickListener(v -> togglePlayPause());
        forwardBtn.setOnClickListener(v -> seekRelative(10000));
        subtitlesBtn.setOnClickListener(v -> showSubtitleDialog());
        qualityBtn.setOnClickListener(v -> showQualityDialog());
        fullscreenBtn.setOnClickListener(v -> toggleFullscreen());

        // Control order for D-pad navigation (Left/Right)
        controlOrder = new View[]{rewindBtn, playPauseBtn, forwardBtn, subtitlesBtn, qualityBtn, fullscreenBtn};

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
        error.setGravity(Gravity.CENTER);
        error.setMaxWidth(dp(460));
        playerErrorPanel.addView(error);
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        retryPlaybackButton = button("Retry");
        retryPlaybackButton.setOnClickListener(v -> startPlayback());
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
                ((ViewGroup) v.getParent()).invalidate();
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
        for (int i = 0; i < controlOrder.length; i++) {
            if (controlOrder[i] == v) return i;
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
        controlsVisible = show;
        if (show) {
            topBar.animate().alpha(1f).setDuration(300).start();
            centerControls.setVisibility(View.VISIBLE);
            centerControls.setAlpha(1f);
            timelineContainer.animate().alpha(1f).setDuration(300).start();
            bottomControls.setVisibility(View.VISIBLE);
            bottomControls.animate().alpha(1f).setDuration(300).start();
            scheduleHideControls();
        } else {
            topBar.animate().alpha(0f).setDuration(300).start();
            centerControls.animate().alpha(0f).setDuration(300).withEndAction(() -> centerControls.setVisibility(View.GONE)).start();
            timelineContainer.animate().alpha(0f).setDuration(300).start();
            bottomControls.animate().alpha(0f).setDuration(300).withEndAction(() -> bottomControls.setVisibility(View.GONE)).start();
            cancelHideControls();
        }
    }

    private void scheduleHideControls() {
        cancelHideControls();
        hideControlsRunnable = () -> {
            if (controlsVisible && player != null && isPlaying) {
                showControls(false);
            }
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
        long newPos = Math.max(0, Math.min(player.getDuration(), player.getCurrentPosition() + ms));
        player.seekTo(newPos);
        cancelHideControls();
        scheduleHideControls();
    }

    private void switchChannel(boolean next) {
        if (entries == null || entries.isEmpty()) return;
        
        // Get all live entries
        ArrayList<Entry> liveEntries = new ArrayList<>();
        for (Entry e : entries) {
            if ("Live".equals(e.type)) liveEntries.add(e);
        }
        if (liveEntries.isEmpty()) return;
        
        // Find current index
        int currentIndex = -1;
        for (int i = 0; i < liveEntries.size(); i++) {
            if (liveEntries.get(i).url.equals(currentEntry.url)) {
                currentIndex = i;
                break;
            }
        }
        if (currentIndex == -1) return;
        
        // Calculate next/prev index with wraparound
        int newIndex = next ? (currentIndex + 1) % liveEntries.size() : (currentIndex - 1 + liveEntries.size()) % liveEntries.size();
        Entry newEntry = liveEntries.get(newIndex);
        
        // Play new channel
        play(newEntry);
        Toast.makeText(this, newEntry.name, Toast.LENGTH_SHORT).show();
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

    private void showQualityDialog() {
        // TODO: Implement quality selection for adaptive streams
        String[] qualities = {"Auto", "4K", "1080p", "720p"};
        new AlertDialog.Builder(this)
            .setTitle("Video Quality")
            .setItems(qualities, (d, w) -> {
                qualityBtn.setContentDescription("Quality: " + qualities[w]);
                Toast.makeText(this, "Quality set to " + qualities[w], Toast.LENGTH_SHORT).show();
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

            player.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int state) {
                    if (player == null) return;
                    runOnUiThread(() -> {
                        try {
                            if (state == Player.STATE_BUFFERING) {
                                playerLoadingText.setText("Buffering " + entry.name + "…");
                                playerLoadingPanel.setVisibility(View.VISIBLE);
                                isPlaying = false;
                                updatePlayPauseUI();
                            } else if (state == Player.STATE_READY) {
                                playerLoadingPanel.setVisibility(View.GONE);
                                playerErrorPanel.setVisibility(View.GONE);
                                long durationMs = player.getDuration();
                                if (metadataText != null && durationMs > 0 && durationMs != Long.MAX_VALUE) {
                                    metadataText.setText(buildMetadataString() + " · " + formatRuntime(durationMs));
                                }
                                isPlaying = player.isPlaying();
                                updatePlayPauseUI();
                                showControls(true);
                            } else if (state == Player.STATE_ENDED) {
                                onPlaybackEnded();
                            } else if (state == Player.STATE_IDLE && player.getPlayerError() != null) {
                                showPlaybackError();
                            }
                        } catch (Exception ignored) {}
                    });
                }

                @Override public void onPlayerError(PlaybackException error) {
                    Log.e(TAG, "Playback error: " + error.getMessage(), error);
                    runOnUiThread(() -> showPlaybackError());
                }

                @Override public void onIsPlayingChanged(boolean playingState) {
                    isPlaying = playingState;
                    updatePlayPauseUI();
                    if (playingState) {
                        scheduleHideControls();
                    } else {
                        cancelHideControls();
                    }
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
            showPlaybackError();
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

    private void showPlaybackError() {
        runOnUiThread(() -> {
            try {
                if (playerLoadingPanel != null) playerLoadingPanel.setVisibility(View.GONE);
                if (playerErrorPanel != null) {
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
            return;
        }
        super.onBackPressed();
    }

    private void loadFavorites(){Set<String> saved=getPreferences(0).getStringSet("favorites",Collections.emptySet());favoriteKeys.addAll(saved);}
    private boolean isFavorite(Entry e){try{return favoriteKeys.contains(sha256(e.url));}catch(Exception ignored){return false;}}
    private void toggleFavorite(Entry e){try{String key=sha256(e.url);if(favoriteKeys.contains(key)){favoriteKeys.remove(key);Toast.makeText(this,"Removed from My List",Toast.LENGTH_SHORT).show();}else{favoriteKeys.add(key);Toast.makeText(this,"Added to My List",Toast.LENGTH_SHORT).show();}getPreferences(0).edit().putStringSet("favorites",new HashSet<>(favoriteKeys)).apply();if(category.equals("My List")||category.equals("Home")){refreshRows();focusFirstContent();}}catch(Exception ignored){Toast.makeText(this,"Could not update My List",Toast.LENGTH_SHORT).show();}}
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

        releaseWakeLock();
        if (player != null) {
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
        super.onDestroy();
    }
}
