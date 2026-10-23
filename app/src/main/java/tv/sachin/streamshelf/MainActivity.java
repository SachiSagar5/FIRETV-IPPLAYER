package tv.sachin.streamshelf;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import android.text.InputType;
import org.json.*;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

public class MainActivity extends Activity {
    private static final int BG=0xff101114, PANEL=0xff202126, RED=0xffe50914, WHITE=0xfff5f5f5, MUTED=0xffa7a7ad;
    private final ArrayList<Source> sources=new ArrayList<>();
    private final ArrayList<Entry> entries=new ArrayList<>();
    private LinearLayout root, content, nav; private TextView status, sourceLabel; private String category="Live"; private int activeSource=0;
    private final ArrayList<TextView> categoryTabs=new ArrayList<>(); private View firstContentItem;
    private ExoPlayer player; private PlayerView playerView; private FrameLayout playerOverlay; private boolean playing=false;
    private static class Source { String name,url; Source(String n,String u){name=n;url=u;} }
    private static class Entry { String name,url,group,logo,type; Entry(String n,String u,String g,String l,String t){name=n;url=u;group=g;logo=l;type=t;} }

    @Override public void onCreate(Bundle b){super.onCreate(b); getWindow().setFlags(1024,1024); loadSources(); buildHome(); if(!sources.isEmpty()) loadPlaylist(); else showWelcome();}
    private int dp(float x){return (int)(x*getResources().getDisplayMetrics().density+.5f);}
    private GradientDrawable rounded(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);return t;}
    private void buildHome(){
        categoryTabs.clear();
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);root.setFocusableInTouchMode(true);root.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);setContentView(root);
        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);top.setPadding(dp(34),dp(17),dp(34),dp(12));root.addView(top,new LinearLayout.LayoutParams(-1,dp(65)));
        TextView brand=text("STREAMSHELF",23,RED);brand.setTypeface(null,Typeface.BOLD);top.addView(brand);
        nav=new LinearLayout(this);nav.setGravity(Gravity.CENTER_VERTICAL);nav.setPadding(dp(38),0,0,0);top.addView(nav,new LinearLayout.LayoutParams(0,-1,1));
        String[] tabs={"Live","Movies","Series","Sources"}; for(String tab:tabs){TextView v=text(tab,17,WHITE);v.setFocusable(true);v.setClickable(true);v.setId(View.generateViewId());v.setPadding(dp(14),dp(9),dp(14),dp(9));setFocusStyle(v,Color.TRANSPARENT,7);v.setOnClickListener(w->{if(tab.equals("Sources")){sourceDialog();return;}category=tab;refreshRows();focusFirstContent();});categoryTabs.add(v);nav.addView(v);}
        TextView search=text("⌕",30,WHITE);search.setFocusable(true);search.setClickable(true);search.setPadding(dp(10),0,dp(10),0);setFocusStyle(search,Color.TRANSPARENT,7);search.setOnClickListener(v->searchDialog());top.addView(search);
        sourceLabel=text("Choose a playlist source",14,MUTED);sourceLabel.setPadding(dp(40),dp(5),dp(12),dp(12));sourceLabel.setFocusable(true);sourceLabel.setClickable(true);sourceLabel.setId(View.generateViewId());setFocusStyle(sourceLabel,Color.TRANSPARENT,6);sourceLabel.setOnClickListener(v->manageSources());root.addView(sourceLabel);
        ScrollView scroll=new ScrollView(this);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));content=new LinearLayout(this);content.setPadding(dp(38),dp(6),dp(38),dp(25));content.setOrientation(LinearLayout.VERTICAL);scroll.addView(content);
        status=text("",15,MUTED);status.setPadding(dp(40),dp(8),0,dp(12));root.addView(status);
        requestInitialFocus();
    }
    private void showWelcome(){content.removeAllViews();TextView t=text("Your screen for live TV, movies and series.",26,WHITE);t.setPadding(0,dp(30),0,dp(12));content.addView(t);TextView sub=text("Add an M3U playlist URL in Sources to begin. Playlist links are encrypted on this device.",17,MUTED);content.addView(sub);Button b=button("Add playlist");b.setOnClickListener(v->sourceDialog());content.addView(b);}
    private Button button(String label){Button b=new Button(this);b.setText(label);b.setTextColor(WHITE);b.setBackground(rounded(RED,6));b.setFocusable(true);b.setClickable(true);b.setFocusableInTouchMode(true);setFocusStyle(b,RED,6);return b;}
    private void refreshRows(){content.removeAllViews();firstContentItem=null;sourceLabel.setText(sources.isEmpty()?"No playlist selected":"SOURCE  ·  "+sources.get(Math.min(activeSource,sources.size()-1)).name+"     ▾");
        if(sources.isEmpty()){showWelcome();return;} ArrayList<Entry> shown=new ArrayList<>();for(Entry e:entries)if(e.type.equals(category))shown.add(e);
        if(shown.isEmpty()){TextView empty=text(entries.isEmpty()?"Loading playlist…":"No "+category.toLowerCase()+" found in this source.",20,MUTED);empty.setPadding(0,dp(22),0,0);content.addView(empty);return;}
        LinkedHashMap<String,ArrayList<Entry>> groups=new LinkedHashMap<>();for(Entry e:shown){String g=e.group==null||e.group.isEmpty()?category:e.group;groups.computeIfAbsent(g,k->new ArrayList<>()).add(e);}
        for(String group:groups.keySet()){TextView heading=text(group,21,WHITE);heading.setTypeface(null,Typeface.BOLD);heading.setPadding(0,dp(17),0,dp(10));content.addView(heading);HorizontalScrollView hsv=new HorizontalScrollView(this);hsv.setHorizontalScrollBarEnabled(false);LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);hsv.addView(row);content.addView(hsv);
            for(Entry e:groups.get(group)){LinearLayout tile=new LinearLayout(this);tile.setOrientation(LinearLayout.VERTICAL);tile.setGravity(Gravity.CENTER);tile.setBackground(rounded(PANEL,7));tile.setPadding(dp(10),dp(9),dp(10),dp(8));tile.setFocusable(true);tile.setClickable(true);tile.setId(View.generateViewId());setFocusStyle(tile,PANEL,7);tile.setOnClickListener(v->play(e));if(firstContentItem==null){firstContentItem=tile;for(TextView tab:categoryTabs)tab.setNextFocusDownId(tile.getId());tile.setNextFocusUpId(currentCategoryTab().getId());}LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(160),dp(112));p.setMargins(0,0,dp(12),dp(6));row.addView(tile,p);
                TextView icon=text(e.type.equals("Live")?"▶":"▣",25,RED);tile.addView(icon);TextView title=text(e.name,14,WHITE);title.setMaxLines(2);title.setGravity(Gravity.CENTER);tile.addView(title);
            }
        }
    }
    private void setFocusStyle(View view,int normalColor,int radius){view.setOnFocusChangeListener((v,focused)->{GradientDrawable bg=rounded(focused?0xff363940:normalColor,radius);if(focused)bg.setStroke(dp(3),0xfff5f5f5);v.setBackground(bg);v.setScaleX(focused?1.04f:1f);v.setScaleY(focused?1.04f:1f);});}
    private TextView currentCategoryTab(){for(TextView tab:categoryTabs)if(tab.getText().toString().equals(category))return tab;return categoryTabs.isEmpty()?sourceLabel:categoryTabs.get(0);}
    private void requestInitialFocus(){if(nav==null)return;nav.post(()->{if(getCurrentFocus()==null){TextView target=currentCategoryTab();if(target!=null)target.requestFocus();}});}
    private void focusFirstContent(){content.post(()->{if(firstContentItem!=null)firstContentItem.requestFocus();else sourceLabel.requestFocus();});}
    @Override public boolean dispatchKeyEvent(android.view.KeyEvent event){
        if(event.getAction()==android.view.KeyEvent.ACTION_DOWN){
            int key=event.getKeyCode();
            if(playing&&key==android.view.KeyEvent.KEYCODE_BACK){onBackPressed();return true;}
            if(!playing&&getCurrentFocus()==null&&event.getRepeatCount()==0&&(key==android.view.KeyEvent.KEYCODE_DPAD_UP||key==android.view.KeyEvent.KEYCODE_DPAD_DOWN||key==android.view.KeyEvent.KEYCODE_DPAD_LEFT||key==android.view.KeyEvent.KEYCODE_DPAD_RIGHT||key==android.view.KeyEvent.KEYCODE_DPAD_CENTER||key==android.view.KeyEvent.KEYCODE_ENTER||key==android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)){
                requestInitialFocus();return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }
    private void loadPlaylist(){if(sources.isEmpty())return;final Source s=sources.get(activeSource);status.setText("Loading "+s.name+"…");entries.clear();refreshRows();new Thread(()->{ArrayList<Entry> parsed=new ArrayList<>();String error=null;HttpURLConnection c=null;try{URL u=new URL(s.url);c=(HttpURLConnection)u.openConnection();c.setConnectTimeout(18000);c.setReadTimeout(25000);c.setRequestProperty("User-Agent","StreamShelf/1.0");c.connect();if(c.getResponseCode()<200||c.getResponseCode()>=300)throw new IOException("HTTP "+c.getResponseCode());BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8));String line,info=null;while((line=r.readLine())!=null){line=line.trim();if(line.startsWith("#EXTINF")){info=line;}else if(!line.isEmpty()&&!line.startsWith("#")&&info!=null){String name=afterComma(info);String group=attr(info,"group-title");String logo=attr(info,"tvg-logo");String type=classify(group,name);parsed.add(new Entry(name,line,group,logo,type));info=null;}}r.close();}catch(Exception e){error=e.getMessage()==null?"Could not load playlist":e.getMessage();}finally{if(c!=null)c.disconnect();}final String err=error;runOnUiThread(()->{entries.clear();entries.addAll(parsed);status.setText(err==null?parsed.size()+" items loaded":"Playlist error: "+err);refreshRows();if(parsed.isEmpty()&&err==null)status.setText("No playable entries found. Check the M3U format and URLs.");});}).start();}
    private String afterComma(String s){int i=s.indexOf(',');String v=i>=0?s.substring(i+1).trim():"Untitled";return v.isEmpty()?"Untitled":v;}
    private String attr(String s,String key){java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?:^|\\s)"+java.util.regex.Pattern.quote(key)+"=\"([^\"]*)\"",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(s);return m.find()?m.group(1):"";}
    private String classify(String g,String n){String s=(g+" "+n).toLowerCase(Locale.ROOT);if(s.matches(".*\\b(series|tv series|season|episode|shows)\\b.*"))return "Series";if(s.matches(".*\\b(movie|movies|film|films|vod)\\b.*"))return "Movies";return "Live";}
    private void sourceDialog(){LinearLayout box=new LinearLayout(this);box.setPadding(dp(20),dp(10),dp(20),0);box.setOrientation(LinearLayout.VERTICAL);EditText name=new EditText(this);name.setHint("Playlist name");box.addView(name);EditText url=new EditText(this);url.setHint("M3U URL (http:// or https://)");url.setSingleLine(true);url.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);box.addView(url);String[] opts=sources.stream().map(x->x.name).toArray(String[]::new);if(opts.length>0){Spinner sp=new Spinner(this);ArrayList<String> labels=new ArrayList<>();labels.add("Save as new playlist");labels.addAll(Arrays.asList(opts));sp.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));box.addView(sp);new AlertDialog.Builder(this).setTitle("Playlist sources").setView(box).setPositiveButton("Save",(d,w)->{String n=name.getText().toString().trim(),u=url.getText().toString().trim();if(!u.isEmpty()){if(n.isEmpty())n="Playlist "+(sources.size()+1);if(sp.getSelectedItemPosition()>0){activeSource=sp.getSelectedItemPosition()-1;}else sources.add(new Source(n,u));saveSources();loadPlaylist();}}).setNeutralButton("Switch / remove",(d,w)->manageSources()).setNegativeButton("Cancel",null).show();}else new AlertDialog.Builder(this).setTitle("Add playlist source").setView(box).setPositiveButton("Add",(d,w)->{String n=name.getText().toString().trim(),u=url.getText().toString().trim();if(!u.isEmpty()){sources.add(new Source(n.isEmpty()?"Playlist 1":n,u));activeSource=sources.size()-1;saveSources();loadPlaylist();}}).setNegativeButton("Cancel",null).show();}
    private void manageSources(){if(sources.isEmpty())return;String[] names=sources.stream().map(x->x.name).toArray(String[]::new);new AlertDialog.Builder(this).setTitle("Switch playlist").setItems(names,(d,w)->{activeSource=w;loadPlaylist();new AlertDialog.Builder(this).setMessage("Remove "+sources.get(w).name+"?").setPositiveButton("Remove",(a,b)->{sources.remove(w);activeSource=Math.max(0,Math.min(activeSource,sources.size()-1));saveSources();entries.clear();if(sources.isEmpty()){showWelcome();sourceLabel.setText("Choose a playlist source");}else loadPlaylist();}).setNegativeButton("Keep",null).show();}).setNegativeButton("Close",null).show();}
    private void searchDialog(){EditText q=new EditText(this);q.setSingleLine(true);q.setHint("Search this playlist");new AlertDialog.Builder(this).setTitle("Search").setView(q).setPositiveButton("Search",(d,w)->{String needle=q.getText().toString().toLowerCase(Locale.ROOT);content.removeAllViews();for(Entry e:entries)if(e.name.toLowerCase(Locale.ROOT).contains(needle)){Button b=button(e.name);b.setOnClickListener(v->play(e));content.addView(b);}if(content.getChildCount()==0)content.addView(text("No matches",18,MUTED));}).setNegativeButton("Cancel",null).show();}
    private void play(Entry e){player=new ExoPlayer.Builder(this).build();playerView=new PlayerView(this);playerView.setPlayer(player);playerView.setUseController(true);playerView.setControllerAutoShow(true);playerView.setFocusable(true);playerOverlay=new FrameLayout(this);playerOverlay.setBackgroundColor(Color.BLACK);playerOverlay.addView(playerView,new FrameLayout.LayoutParams(-1,-1));setContentView(playerOverlay);player.setMediaItem(MediaItem.fromUri(e.url));player.prepare();player.play();playing=true;}
    @Override public void onBackPressed(){if(playing){if(player!=null){player.release();player=null;}playing=false;buildHome();refreshRows();return;}super.onBackPressed();}

    private void loadSources(){try{String raw=getPreferences(0).getString("sources_enc",null);if(raw==null)return;byte[] all=Base64.decode(raw,Base64.NO_WRAP);int n=((all[0]&255)<<8)|(all[1]&255);byte[] iv=Arrays.copyOfRange(all,2,2+n),cipher=Arrays.copyOfRange(all,2+n,all.length);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,secretKey(),new GCMParameterSpec(128,iv));JSONArray a=new JSONArray(new String(c.doFinal(cipher),StandardCharsets.UTF_8));for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);sources.add(new Source(o.getString("name"),o.getString("url")));}activeSource=Math.min(getPreferences(0).getInt("active",0),Math.max(0,sources.size()-1));}catch(Exception ignored){sources.clear();}}
    private void saveSources(){try{JSONArray a=new JSONArray();for(Source s:sources){JSONObject o=new JSONObject();o.put("name",s.name);o.put("url",s.url);a.put(o);}Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,secretKey());byte[] iv=c.getIV(),data=c.doFinal(a.toString().getBytes(StandardCharsets.UTF_8));byte[] out=new byte[2+iv.length+data.length];out[0]=(byte)(iv.length>>8);out[1]=(byte)iv.length;System.arraycopy(iv,0,out,2,iv.length);System.arraycopy(data,0,out,2+iv.length,data.length);getPreferences(0).edit().putString("sources_enc",Base64.encodeToString(out,Base64.NO_WRAP)).putInt("active",activeSource).apply();}catch(Exception e){status.setText("Could not securely save playlist URLs.");}}
    private javax.crypto.SecretKey secretKey() throws Exception {KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);String alias="StreamShelfKey";if(ks.containsAlias(alias))return (javax.crypto.SecretKey)ks.getKey(alias,null);KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");g.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());return g.generateKey();}
    @Override protected void onDestroy(){if(player!=null)player.release();super.onDestroy();}
}
