package ir.alanrang.privateapp;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class AndroidBridge {
    private final Activity activity;
    private final WebView webView;
    private final SecureDataStore store;
    private final ExportFileManager exports;

    public AndroidBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        this.store = new SecureDataStore(activity.getApplicationContext());
        this.exports = new ExportFileManager(activity);
    }

    @JavascriptInterface public boolean saveSecureData(String name, String value) { return store.save(name == null ? "" : name, value == null ? "" : value); }
    @JavascriptInterface public String loadSecureData(String name) { return store.load(name == null ? "" : name); }
    @JavascriptInterface public boolean removeSecureData(String name) { return store.remove(name == null ? "" : name); }
    @JavascriptInterface public String getAppVersion() { return BuildConfig.VERSION_NAME; }

    @JavascriptInterface public String sha256Text(String text) {
        try { byte[] digest=MessageDigest.getInstance("SHA-256").digest((text==null?"":text).getBytes(StandardCharsets.UTF_8));StringBuilder sb=new StringBuilder(digest.length*2);for(byte b:digest)sb.append(String.format("%02x",b&0xff));return sb.toString(); }
        catch(Exception e){return "";}
    }

    @JavascriptInterface public void setTextZoom(int percent) { final int safe=Math.max(70,Math.min(180,percent));activity.runOnUiThread(()->webView.getSettings().setTextZoom(safe)); }
    @JavascriptInterface public void shareText(String title,String text) { activity.runOnUiThread(()->{Intent intent=new Intent(Intent.ACTION_SEND);intent.setType("text/plain");intent.putExtra(Intent.EXTRA_SUBJECT,title==null?"آلان رنگ":title);intent.putExtra(Intent.EXTRA_TEXT,text==null?"":text);activity.startActivity(Intent.createChooser(intent,title==null?"اشتراک‌گذاری":title));}); }

    @JavascriptInterface public boolean prepareExportData(String base64,String mime,String filename,String key){return exports.prepare(base64,mime,filename,key);}
    @JavascriptInterface public boolean isPreparedExportReady(String key,String mime,String filename){return exports.isPrepared(key,mime,filename);}
    @JavascriptInterface public boolean savePreparedExport(String key,String mime,String filename){return exports.savePrepared(key,mime,filename);}
    @JavascriptInterface public boolean sharePreparedExport(String key,String mime,String filename,String title){return exports.sharePrepared(key,mime,filename,title);}
    @JavascriptInterface public boolean saveData(String base64,String mime,String filename){return exports.saveBase64(base64,mime,filename);}
    @JavascriptInterface public boolean saveDataV2(String base64,String mime,String filename,String key){if(key!=null&&!key.isEmpty())exports.prepare(base64,mime,filename,key);return exports.saveBase64(base64,mime,filename);}
    @JavascriptInterface public boolean shareData(String base64,String mime,String filename,String title){return exports.shareBase64(base64,mime,filename,title);}
    @JavascriptInterface public boolean saveBackupFile(String filename,String text){return exports.saveBackupText(filename,text);}
    @JavascriptInterface public boolean openLastSavedFile(){return exports.openLast();}
    @JavascriptInterface public boolean shareLastSavedFile(String key,String mime,String filename,String title){if(key!=null&&exports.isPrepared(key,mime,filename))return exports.sharePrepared(key,mime,filename,title);return exports.shareLast(mime,title);}

    @JavascriptInterface public String encryptBackupText(String plaintext,String password){try{return BackupCrypto.encryptPortable(plaintext==null?"":plaintext,password==null?"":password);}catch(Exception e){return "";}}
    @JavascriptInterface public String decryptBackupText(String envelope,String password){try{return BackupCrypto.decryptPortable(envelope==null?"":envelope,password==null?"":password);}catch(Exception e){return "";}}

    @JavascriptInterface public String deriveSecurityHash(String secret,String saltBase64,int iterations){
        try{
            int rounds=Math.max(10000,Math.min(1000000,iterations));
            byte[] salt=Base64.decode(saltBase64==null?"":saltBase64,Base64.DEFAULT);
            if(salt.length<8)return "";
            PBEKeySpec spec=new PBEKeySpec((secret==null?"":secret).toCharArray(),salt,rounds,256);
            byte[] key=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return Base64.encodeToString(key,Base64.NO_WRAP);
        }catch(Exception e){
            try{
                int rounds=Math.max(10000,Math.min(1000000,iterations));
                byte[] salt=Base64.decode(saltBase64==null?"":saltBase64,Base64.DEFAULT);
                if(salt.length<8)return "";
                return Base64.encodeToString(BackupCrypto.pbkdf2Sha256((secret==null?"":secret).toCharArray(),salt,rounds,32),Base64.NO_WRAP);
            }catch(Exception ignored){return "";}
        }
    }

    @JavascriptInterface public boolean hasSeenReleaseNotes(String build){
        return activity.getSharedPreferences("alanrang_private_ui",Context.MODE_PRIVATE).getBoolean("release_"+(build==null?"":build),false);
    }
    @JavascriptInterface public void markReleaseNotesSeen(String build){
        activity.getSharedPreferences("alanrang_private_ui",Context.MODE_PRIVATE).edit().putBoolean("release_"+(build==null?"":build),true).apply();
    }

    @JavascriptInterface public boolean canPostFinancialNotifications(){
        return Build.VERSION.SDK_INT<33 || activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;
    }
    @JavascriptInterface public void requestFinancialNotificationPermission(){
        if(Build.VERSION.SDK_INT>=33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
            activity.runOnUiThread(()->activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},3105));
        }
    }
    @JavascriptInterface public boolean showLocalFinancialReminder(String key,String title,String text){
        if(!canPostFinancialNotifications())return false;
        try{
            NotificationManager nm=(NotificationManager)activity.getSystemService(Context.NOTIFICATION_SERVICE);
            String channelId="alanrang_financial";
            if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(new NotificationChannel(channelId,"یادآورهای مالی آلان رنگ",NotificationManager.IMPORTANCE_DEFAULT));
            android.app.Notification.Builder b=Build.VERSION.SDK_INT>=26?new android.app.Notification.Builder(activity,channelId):new android.app.Notification.Builder(activity);
            b.setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title==null?"یادآور آلان رنگ":title).setContentText(text==null?"":text).setAutoCancel(true);
            nm.notify((key==null?"alanrang":key).hashCode(),b.build());
            return true;
        }catch(Exception e){return false;}
    }

    @JavascriptInterface public void signalSecurityReady(){ }
}
