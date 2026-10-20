package com.globalvillager.mobile;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Resume choices only; credentials remain encrypted in SecureSession. */
public final class BackgroundVpnState {
    private final SharedPreferences prefs;
    public BackgroundVpnState(Context context){prefs=context.getSharedPreferences("gv-vpn-resume",Context.MODE_PRIVATE);}
    public void remember(Intent request,String token){
        if(token==null||token.isBlank())throw new IllegalArgumentException("Login required");
        if(!prefs.edit().putBoolean("requested",true).putString("session-binding",binding(token))
            .putString("country",value(request,"country")).putString("node",value(request,"node"))
            .putString("mode",ConnectionMode.from(request.getStringExtra("mode")).value).commit())
            throw new IllegalStateException("Could not save VPN choices");
    }
    public Intent restore(Context context,String token,boolean systemRequest){
        if(token==null||token.isBlank()||(!systemRequest&&!prefs.getBoolean("requested",false))
            ||!binding(token).equals(prefs.getString("session-binding","")))return null;
        return new Intent(context,GvVpnService.class).setAction(GvVpnService.START)
            .putExtra("country",prefs.getString("country",""))
            .putExtra("node",prefs.getString("node",""))
            .putExtra("mode",prefs.getString("mode",ConnectionMode.GLOBAL.value));
    }
    /** Manual disconnect retains choices for a later explicit system request. */
    public void stop(){prefs.edit().putBoolean("requested",false).commit();}
    /** Revocation/logout must also discard the session-bound configuration. */
    public void clear(){prefs.edit().clear().commit();}
    private static String value(Intent request,String key){String v=request.getStringExtra(key);return v==null?"":v;}
    private static String binding(String token){
        try{
            byte[] bytes=MessageDigest.getInstance("SHA-256").digest(("gv.resume.v1|"+token).getBytes(StandardCharsets.UTF_8));
            StringBuilder result=new StringBuilder();for(byte b:bytes)result.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return result.toString();
        }catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
}
