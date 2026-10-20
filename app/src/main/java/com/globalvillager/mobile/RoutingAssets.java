package com.globalvillager.mobile;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.security.MessageDigest;

/** Bundled, checksum-pinned routing data. Never fetches executable code at runtime. */
public final class RoutingAssets {
    private RoutingAssets(){}
    public static synchronized void ensure(Context context)throws Exception {
        JSONObject manifest;
        try(InputStream in=context.getAssets().open("routing/manifest.json");ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            byte[] block=new byte[4096];int n;while((n=in.read(block))!=-1){if(bytes.size()+n>16384)throw new IOException("Manifest too large");bytes.write(block,0,n);}
            manifest=new JSONObject(new String(bytes.toByteArray(),java.nio.charset.StandardCharsets.UTF_8));
        }
        JSONArray assets=manifest.getJSONArray("assets");
        if(assets.length()!=2)throw new IOException("Incomplete routing assets");
        for(int i=0;i<assets.length();i++){
            JSONObject item=assets.getJSONObject(i);String name=item.getString("name"),expected=item.getString("sha256");
            if(!("geoip.dat".equals(name)||"geosite.dat".equals(name))||!expected.matches("[a-f0-9]{64}"))throw new IOException("Invalid routing asset manifest");
            File target=new File(context.getFilesDir(),name);
            if(target.isFile()&&expected.equals(hash(target)))continue;
            AtomicFile atomic=new AtomicFile(target);FileOutputStream out=atomic.startWrite();
            try(InputStream in=context.getAssets().open("routing/"+name)){
                MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] block=new byte[8192];int n,total=0;
                while((n=in.read(block))!=-1){total+=n;if(total>8*1024*1024)throw new IOException("Routing asset too large");out.write(block,0,n);digest.update(block,0,n);}
                if(!expected.equals(hex(digest.digest())))throw new IOException("Routing asset checksum mismatch");
                atomic.finishWrite(out);
            }catch(Exception error){atomic.failWrite(out);throw error;}
        }
    }
    private static String hash(File file)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(file)){byte[] block=new byte[8192];int n;while((n=in.read(block))!=-1)digest.update(block,0,n);}
        return hex(digest.digest());
    }
    private static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();}
}
