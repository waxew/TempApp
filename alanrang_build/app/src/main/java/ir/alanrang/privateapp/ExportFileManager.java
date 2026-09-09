package ir.alanrang.privateapp;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class ExportFileManager {
    private static final String PREFS = "alanrang_private_exports";
    private static final String LAST_URI = "last_uri";
    private static final String LAST_MIME = "last_mime";
    private static final String LAST_NAME = "last_name";
    private final Activity activity;
    private final Map<String, Prepared> prepared = new ConcurrentHashMap<>();

    private static final class Prepared {
        final byte[] bytes; final String mime; final String name;
        Prepared(byte[] bytes,String mime,String name){this.bytes=bytes;this.mime=mime;this.name=name;}
    }

    ExportFileManager(Activity activity){this.activity=activity;}

    private String authority(){ return activity.getPackageName()+".files"; }

    private static String cleanFileName(String raw){
        String name=raw==null?"AlanRang_File":raw.trim();
        name=name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_");
        if(name.isEmpty())name="AlanRang_File";
        return name.length()>160?name.substring(0,160):name;
    }

    private static byte[] decodeBase64(String encoded){
        if(encoded==null)return new byte[0];
        String s=encoded.trim(); int comma=s.indexOf(',');
        if(s.startsWith("data:")&&comma>=0)s=s.substring(comma+1);
        return Base64.decode(s,Base64.DEFAULT);
    }

    boolean prepare(String encoded,String mime,String name,String key){
        try{
            if(key==null||key.trim().isEmpty())return false;
            byte[] bytes=decodeBase64(encoded); if(bytes.length==0)return false;
            prepared.put(key,new Prepared(bytes,safeMime(mime),cleanFileName(name)));
            return true;
        }catch(Exception e){return false;}
    }

    boolean isPrepared(String key,String mime,String name){return key!=null&&prepared.containsKey(key);}

    boolean savePrepared(String key,String mime,String name){
        Prepared p=key==null?null:prepared.get(key); if(p==null)return false;
        return saveBytes(p.bytes,mime==null||mime.isEmpty()?p.mime:mime,name==null||name.isEmpty()?p.name:name)!=null;
    }

    boolean sharePrepared(String key,String mime,String name,String title){
        Prepared p=key==null?null:prepared.get(key); if(p==null)return false;
        return shareBytes(p.bytes,mime==null||mime.isEmpty()?p.mime:mime,name==null||name.isEmpty()?p.name:name,title);
    }

    boolean saveBase64(String encoded,String mime,String name){
        try{return saveBytes(decodeBase64(encoded),safeMime(mime),cleanFileName(name))!=null;}catch(Exception e){return false;}
    }

    boolean shareBase64(String encoded,String mime,String name,String title){
        try{return shareBytes(decodeBase64(encoded),safeMime(mime),cleanFileName(name),title);}catch(Exception e){return false;}
    }

    boolean saveBackupText(String name,String text){
        try{return saveBytes((text==null?"":text).getBytes(StandardCharsets.UTF_8),"application/octet-stream",cleanFileName(name))!=null;}catch(Exception e){return false;}
    }

    private Uri saveBytes(byte[] bytes,String mime,String rawName){
        if(bytes==null||bytes.length==0)return null;
        String name=cleanFileName(rawName); mime=safeMime(mime); Uri uri=null;
        try{
            if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q){
                ContentResolver r=activity.getContentResolver();
                ContentValues v=new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME,name); v.put(MediaStore.MediaColumns.MIME_TYPE,mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOCUMENTS+"/AlanRang");
                v.put(MediaStore.MediaColumns.IS_PENDING,1);
                uri=r.insert(MediaStore.Files.getContentUri("external"),v); if(uri==null)return null;
                try(OutputStream out=r.openOutputStream(uri,"w")){if(out==null)throw new IllegalStateException("OUTPUT_STREAM_NULL");out.write(bytes);out.flush();}
                ContentValues done=new ContentValues();done.put(MediaStore.MediaColumns.IS_PENDING,0);r.update(uri,done,null,null);
            }else{
                File base=new File(activity.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),"AlanRang");
                if(!base.exists()&&!base.mkdirs())return null;
                File file=new File(base,name);
                try(FileOutputStream out=new FileOutputStream(file)){out.write(bytes);out.flush();out.getFD().sync();}
                uri=FileProvider.getUriForFile(activity,authority(),file);
            }
            remember(uri,mime,name); return uri;
        }catch(Exception e){
            if(uri!=null&&Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q){try{activity.getContentResolver().delete(uri,null,null);}catch(Exception ignored){}}
            return null;
        }
    }

    private boolean shareBytes(byte[] bytes,String mime,String rawName,String title){
        if(bytes==null||bytes.length==0)return false;
        try{
            File dir=new File(activity.getCacheDir(),"shared_exports"); if(!dir.exists()&&!dir.mkdirs())return false;
            File file=new File(dir,cleanFileName(rawName));
            try(FileOutputStream out=new FileOutputStream(file)){out.write(bytes);out.flush();out.getFD().sync();}
            Uri uri=FileProvider.getUriForFile(activity,authority(),file);
            launchShare(uri,safeMime(mime),title); return true;
        }catch(Exception e){return false;}
    }

    boolean openLast(){
        Uri uri=lastUri(); if(uri==null)return false; String mime=activity.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(LAST_MIME,"application/octet-stream");
        try{Intent i=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);activity.runOnUiThread(()->{try{activity.startActivity(i);}catch(Exception ignored){}});return true;}catch(Exception e){return false;}
    }

    boolean shareLast(String mime,String title){
        Uri uri=lastUri(); if(uri==null)return false; String saved=activity.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(LAST_MIME,"application/octet-stream");
        launchShare(uri,(mime==null||mime.isEmpty())?saved:mime,title); return true;
    }

    private void launchShare(Uri uri,String mime,String title){
        activity.runOnUiThread(()->{
            Intent send=new Intent(Intent.ACTION_SEND);send.setType(safeMime(mime));send.putExtra(Intent.EXTRA_STREAM,uri);send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(send,title==null||title.isEmpty()?"اشتراک‌گذاری آلان رنگ":title));
        });
    }

    private void remember(Uri uri,String mime,String name){
        activity.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(LAST_URI,uri.toString()).putString(LAST_MIME,mime).putString(LAST_NAME,name).apply();
    }
    private Uri lastUri(){try{String s=activity.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(LAST_URI,"");return s==null||s.isEmpty()?null:Uri.parse(s);}catch(Exception e){return null;}}
    private static String safeMime(String mime){String s=mime==null?"":mime.trim();return s.isEmpty()?"application/octet-stream":s;}
}
