package com.globalvillager.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class SecureSession {
    private static final String ALIAS="GlobalVillager.mobile.session.v1";
    private final SharedPreferences prefs;
    private final Context context;
    public SecureSession(Context c) { context=c.getApplicationContext();prefs=context.getSharedPreferences("gv-session",Context.MODE_PRIVATE); }
    private SecretKey key() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(!ks.containsAlias(ALIAS)) {
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return ((KeyStore.SecretKeyEntry)ks.getEntry(ALIAS,null)).getSecretKey();
    }
    public synchronized void save(String token,String email) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        String encrypted=Base64.encodeToString(cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
        String tokenIv=Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP);
        cipher.init(Cipher.ENCRYPT_MODE,key());
        String identity=Base64.encodeToString(cipher.doFinal(email.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
        if(!prefs.edit().putString("token",encrypted).putString("iv",tokenIv)
            .putString("identity",identity).putString("identity-iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)).remove("email").commit())
            throw new IllegalStateException("Could not save session");
    }
    public synchronized String token() throws Exception {
        String data=prefs.getString("token","");if(data.isEmpty())return "";
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(prefs.getString("iv",""),Base64.NO_WRAP)));
        String token=new String(cipher.doFinal(Base64.decode(data,Base64.NO_WRAP)),StandardCharsets.UTF_8);
        // Upgrade the old retained username without clearing the existing login.
        if(prefs.contains("email"))save(token,prefs.getString("email",""));
        return token;
    }
    public synchronized String email() {
        if(prefs.contains("email"))return prefs.getString("email","");
        try{
            String encrypted=prefs.getString("identity","");if(encrypted.isEmpty())return "";
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(prefs.getString("identity-iv",""),Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(encrypted,Base64.NO_WRAP)),StandardCharsets.UTF_8);
        }catch(Exception unreadable){return "";}
    }
    public synchronized void clear() { new BackgroundVpnState(context).clear();prefs.edit().remove("token").remove("iv").remove("email").remove("identity").remove("identity-iv").commit(); }
    public String deviceId() throws Exception {
        String id=Settings.Secure.getString(context.getContentResolver(),Settings.Secure.ANDROID_ID);
        if(id==null||id.isBlank())throw new IllegalStateException("Missing device identifier");
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(("gv.android.v1|"+id).getBytes(StandardCharsets.UTF_8));
        StringBuilder text=new StringBuilder("android-");for(byte b:hash)text.append(String.format("%02x",b&255));return text.toString();
    }
}
