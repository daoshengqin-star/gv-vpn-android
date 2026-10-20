package com.globalvillager.mobile;

import org.json.JSONObject;
import org.json.JSONArray;
import android.os.SystemClock;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** HTTPS only; bounded response sizes; no body/credential logging or redirects. */
public final class GvApi implements ApiTransport {
    public static final String BASE="https://api.worldcitizenvpn.com";
    private final Set<HttpURLConnection> running=ConcurrentHashMap.newKeySet();
    private final ConnectivityManager connectivity;
    private volatile boolean cancelled;
    public GvApi(){connectivity=null;}
    public GvApi(Context context){connectivity=context.getApplicationContext().getSystemService(ConnectivityManager.class);}
    private static final Object controlNetworkLock=new Object();
    private static Network retainedControlNetwork;
    private Network reusableControlNetwork(Network current){
        synchronized(controlNetworkLock){
            // Android gives every Network object a private HTTP connection pool.
            // Share only this pool across API clients in the application process.
            // Credentials, cancellation and entitlement remain request/client scoped.
            // Capabilities are checked on every request; a new network gets a new pool.
            if(retainedControlNetwork==null||!retainedControlNetwork.equals(current))retainedControlNetwork=current;
            return retainedControlNetwork;
        }
    }
    private HttpURLConnection openControlConnection(URL url) throws Exception {
        if(connectivity==null)return (HttpURLConnection)url.openConnection();
        Network preferred=connectivity.getActiveNetwork();
        NetworkCapabilities capabilities=preferred==null?null:connectivity.getNetworkCapabilities(preferred);
        if(capabilities!=null&&capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            &&capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            return (HttpURLConnection)reusableControlNetwork(preferred).openConnection(url);
        for(Network network:connectivity.getAllNetworks()){
            NetworkCapabilities candidate=connectivity.getNetworkCapabilities(network);
            if(candidate!=null&&candidate.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                &&candidate.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                return (HttpURLConnection)reusableControlNetwork(network).openConnection(url);
        }
        synchronized(controlNetworkLock){retainedControlNetwork=null;}
        throw new java.net.SocketException("No underlying internet network");
    }
    public static final class ApiException extends Exception {
        public final int status; public final String detail;
        ApiException(int status,String detail) { super("API "+status);this.status=status;this.detail=detail; }
        public int friendlyResource() {
            if(status==401)return R.string.api_login;
            if(status==403&&detail.equals("trial device mismatch"))return R.string.trial_original_device;
            if(status==403&&detail.equals("trial entitlement expired"))return R.string.trial_window_ended;
            if(status==403&&detail.contains("device limit"))return R.string.api_device_limit;
            if(status==403&&detail.equals("device disabled"))return R.string.api_device_disabled;
            if(status==409&&detail.equals("device release node cleanup pending"))return R.string.device_release_pending;
            if(status==403)return R.string.api_forbidden;
            if(status==409)return R.string.api_duplicate;
            if(status==429)return R.string.api_rate_limit;
            if(status==503)return R.string.api_unavailable;
            return R.string.api_incomplete;
        }
    }
    public static final class Reply {
        public final Object data;public final long serverTime;
        Reply(Object data,long serverTime){this.data=data;this.serverTime=serverTime;}
        public JSONObject object(){return (JSONObject)data;}
    }
    public Reply call(String method,String path,JSONObject body,String token) throws Exception {
        if(cancelled||Thread.currentThread().isInterrupted())throw new InterruptedException();
        if(!path.startsWith("/api/"))throw new IllegalArgumentException("Invalid API path");
        // DNS/socket selection follows the underlying network even while an old
        // VPN route is being removed. This changes no process-wide network state.
        HttpURLConnection c=openControlConnection(new URL(BASE+path));running.add(c);
        c.setConnectTimeout(6000);c.setReadTimeout(8000);c.setInstanceFollowRedirects(false);c.setRequestMethod(method);
        c.setRequestProperty("Accept","application/json");
        c.setRequestProperty("User-Agent","GlobalVillager-Android/"+BuildConfig.VERSION_NAME);
        if(token!=null&&!token.isBlank())c.setRequestProperty("Authorization","Bearer "+token);
        try {
            if(cancelled||Thread.currentThread().isInterrupted())throw new InterruptedException();
            if(body!=null) {
                byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);c.setDoOutput(true);
                c.setRequestProperty("Content-Type","application/json");c.setFixedLengthStreamingMode(bytes.length);
                try(var out=c.getOutputStream()){out.write(bytes);}
            }
            int status=c.getResponseCode();long serverTime=0;
            try{serverTime=ZonedDateTime.parse(c.getHeaderField("Date"),DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();}catch(Exception ignored){}
            InputStream stream=status>=200&&status<300?c.getInputStream():c.getErrorStream();
            String text="";
            if(stream!=null)try(InputStream in=stream;ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
                byte[] block=new byte[8192];int n;
                while((n=in.read(block))!=-1) { if(cancelled||Thread.currentThread().isInterrupted())throw new InterruptedException(); if(bytes.size()+n>2*1024*1024)throw new IllegalArgumentException("Response too large");bytes.write(block,0,n); }
                text=new String(bytes.toByteArray(),StandardCharsets.UTF_8);
            }
            Object data=ApiResponse.parse(status,text);
            return new Reply(data,serverTime);
        } finally { running.remove(c);c.disconnect(); }
    }
    public void cancel() { cancelled=true;for(HttpURLConnection c:running)c.disconnect(); }
    public static String friendly(Context context,Exception e) { return AppLanguage.text(context,e instanceof ApiException?((ApiException)e).friendlyResource():R.string.network_request_failed); }
    public static final class Entitlement {
        public final boolean active;public final long deadline;public final long expiryEpoch;public final String plan;
        public Entitlement(Reply reply) {
            JSONObject data=reply.object();JSONObject sub=data.optJSONObject("subscription");
            boolean a=data.optBoolean("active")&&sub!=null;long expiry=0,limit=0;
            if(a&&!sub.isNull("expires_at")) {
                try{expiry=Instant.parse(sub.getString("expires_at")).toEpochMilli();long now=reply.serverTime>0?reply.serverTime:System.currentTimeMillis();limit=SystemClock.elapsedRealtime()+Math.max(0,expiry-now);a=expiry>now;}catch(Exception e){a=false;}
            }
            active=a;deadline=limit;expiryEpoch=expiry;plan=sub==null?"":sub.optString("plan");
        }
        public boolean usable() { return active&&(deadline==0||SystemClock.elapsedRealtime()<deadline); }
    }
}
