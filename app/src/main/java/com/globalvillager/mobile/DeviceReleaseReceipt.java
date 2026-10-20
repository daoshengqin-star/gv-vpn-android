package com.globalvillager.mobile;
import org.json.JSONObject;
/** A freed seat requires an exact server receipt and completed node revocation. */
public final class DeviceReleaseReceipt {
    public static boolean completed(JSONObject value,String device,String request){
        return value!=null&&device.equals(value.optString("device_id"))&&request.equals(value.optString("request_id"))
            &&"completed".equals(value.optString("status"))&&value.optBoolean("seat_released")
            &&"completed".equals(value.optString("node_revocation"));
    }
    public static String key(String identity,String device)throws Exception{
        return scope(identity)+digest(device);
    }
    public static String scope(String identity)throws Exception{return "device-release-"+digest(identity.trim().toLowerCase(java.util.Locale.ROOT))+"-";}
    private static String digest(String value)throws Exception{byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder text=new StringBuilder();for(byte b:hash)text.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return text.toString();}
}
