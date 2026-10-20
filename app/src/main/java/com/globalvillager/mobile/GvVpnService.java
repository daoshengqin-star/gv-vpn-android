package com.globalvillager.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.Context;
import android.content.res.Configuration;
import android.net.VpnService;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.Settings;
import org.json.JSONObject;
import org.json.JSONArray;
import go.Seq;
import libv2ray.Libv2ray;
import libv2ray.CoreController;
import libv2ray.CoreCallbackHandler;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** One service owns the engine and TUN FD. New requests invalidate every old callback. */
public final class GvVpnService extends VpnService {
    public static final String START="com.globalvillager.mobile.START", STOP="com.globalvillager.mobile.STOP";
    public static final String UPDATE="com.globalvillager.mobile.UPDATE";
    public static final class Snapshot {
        public final String phase,node,country;public final ConnectionMode mode;public final int messageResource,failureResource;public final long connectedAt,startedAt,up,down;
        Snapshot(String phase,int messageResource,String node,String country,long connectedAt,long startedAt,long up,long down) {
            this(phase,messageResource,node,country,ConnectionMode.GLOBAL,connectedAt,startedAt,up,down);
        }
        Snapshot(String phase,int messageResource,String node,String country,ConnectionMode mode,long connectedAt,long startedAt,long up,long down) {
            this(phase,messageResource,node,country,mode,connectedAt,startedAt,up,down,0);
        }
        Snapshot(String phase,int messageResource,String node,String country,ConnectionMode mode,long connectedAt,long startedAt,long up,long down,int failureResource) {
            this.phase=phase;this.messageResource=messageResource;this.node=node;this.country=country;
            this.failureResource=failureResource;
            this.mode=mode;
            this.connectedAt=connectedAt;this.startedAt=startedAt;this.up=up;this.down=down;
        }
        public boolean connected(){return "connected".equals(phase);}
        public boolean blocking(){return "blocked".equals(phase);}
        public boolean busy(){return !"idle".equals(phase)&&!"error".equals(phase)&&!blocking()&&!connected();}
        public String message(Context context){String value=AppLanguage.text(context,messageResource);return blocking()&&failureResource!=0?value+"\n"+AppLanguage.text(context,failureResource):value;}
    }
    public static volatile Snapshot status=new Snapshot("idle",R.string.disconnected,"","",0,0,0,0);
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    // A retired service must finish releasing its native reader before a new
    // service can reuse the process-wide TUN descriptor setting.
    private static final ExecutorService engineStopper=Executors.newSingleThreadExecutor();
    private final ExecutorService leaseWorker=Executors.newSingleThreadExecutor();
    private final ExecutorService probeWorker=Executors.newFixedThreadPool(2);
    private final ScheduledExecutorService ticker=Executors.newSingleThreadScheduledExecutor();
    private final AtomicInteger generation=new AtomicInteger();
    private final Object stateLock=new Object();
    private volatile boolean desired,closing,leaseBusy;
    private volatile GvApi api;
    private volatile ParcelFileDescriptor tunnel;
    private volatile Future<?> stopFuture;
    private CoreController core;
    private SecureSession session;
    private BackgroundVpnState resumeState;
    public static volatile boolean systemManaged;
    private volatile GvApi.Entitlement entitlement;
    private volatile long lastLeaseCheck;
    private final ConnectionHealth health=new ConnectionHealth();
    private final ReconnectPolicy recoveryPolicy=new ReconnectPolicy();
    private volatile boolean userRequested,everConnected,recoveryScheduled;
    private volatile int failureResource;
    private Intent lastStartRequest;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private final Runnable recovery=()->{
        if(!userRequested||!recoveryScheduled||lastStartRequest==null||!status.blocking())return;
        recoveryScheduled=false;
        beginConnection(new Intent(lastStartRequest),true,false);
    };
    private long startedAt,connectedAt,up,down;
    private String country="",node="",token="",device="";
    private ConnectionMode mode=ConnectionMode.GLOBAL;
    // Traffic counters change every second; the notification does not.
    private String notifiedKey="";
    private boolean privacyGuard(){return getSharedPreferences("gv-ui",MODE_PRIVATE).getBoolean("privacy-guard",true);}

    private void refreshSystemOwnership(){
        if(Build.VERSION.SDK_INT>=29)systemManaged=isAlwaysOn();
        else try{systemManaged=getPackageName().equals(Settings.Secure.getString(getContentResolver(),"always_on_vpn_app"));}
            catch(SecurityException restricted){/* Retain the observed system-start state. */}
    }

    @Override public void onCreate() {
        super.onCreate();session=new SecureSession(this);resumeState=new BackgroundVpnState(this);Seq.setContext(getApplicationContext());
        Libv2ray.initCoreEnv(getFilesDir().getAbsolutePath(),"");
        core=Libv2ray.newCoreController(new CoreCallbackHandler() {
            public long startup(){return 0;}
            public long shutdown(){return 0;}
            public long onEmitStatus(long code,String message){return 0;}
        });
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("gv-vpn","Global Villager VPN",NotificationManager.IMPORTANCE_LOW));
        ticker.scheduleWithFixedDelay(this::tick,1,1,TimeUnit.SECONDS);
        connectivity=getSystemService(ConnectivityManager.class);
        networkCallback=new ConnectivityManager.NetworkCallback(){
            @Override public void onAvailable(Network network){networkChanged(true);}
            @Override public void onLost(Network network){networkChanged(false);}
        };
        if(connectivity!=null)try{connectivity.registerNetworkCallback(new NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),networkCallback);}
            catch(RuntimeException ignored){networkCallback=null;}
    }
    private void networkChanged(boolean available){main.post(()->{
        if(!userRequested)return;
        if(desired&&status.connected())lastLeaseCheck=0;
        if(available&&recoveryScheduled){main.removeCallbacks(recovery);main.postDelayed(recovery,250);}
    });}

    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent!=null&&STOP.equals(intent.getAction())) {stopRequested();return START_NOT_STICKY;}
        boolean systemRequest=intent!=null&&(intent.getAction()==null||SERVICE_INTERFACE.equals(intent.getAction()));
        if(intent==null||systemRequest){
            if(Build.VERSION.SDK_INT<29&&systemRequest)systemManaged=true;
            refreshSystemOwnership();
            if(desired||(tunnel!=null&&status.blocking()))return START_STICKY;
            try{
                boolean disclosed=getSharedPreferences("gv-ui",MODE_PRIVATE).getInt("vpn-disclosure-version",0)==1;
                Intent restored=disclosed?resumeState.restore(this,session.token(),systemRequest):null;
                if(restored!=null)return beginConnection(restored,false,true);
            }catch(Exception ignored){}
            startForeground(7,notification(R.string.background_login_required));
            publish("error",R.string.background_login_required);
            stopForeground(STOP_FOREGROUND_REMOVE);if(startId>0)stopSelf(startId);
            return START_NOT_STICKY;
        }
        if(!START.equals(intent.getAction()))return START_NOT_STICKY;
        return beginConnection(intent,false,false);
    }
    private int beginConnection(Intent intent,boolean automatic,boolean restoring) {
        if(automatic&&!userRequested)return START_NOT_STICKY;
        if(!automatic){recoveryPolicy.reset();everConnected=restoring||status.connected();}
        refreshSystemOwnership();
        userRequested=true;main.removeCallbacks(recovery);recoveryScheduled=false;failureResource=0;
        lastStartRequest=new Intent(intent);
        startForeground(7,notification(R.string.vpn_preparing));
        if(VpnService.prepare(this)!=null) {resumeState.clear();stopRequested();return START_NOT_STICKY;}
        try{resumeState.remember(intent,session.token());}
        catch(Exception unavailable){resumeState.clear();failure(R.string.background_login_required);return START_NOT_STICKY;}
        int g=nextGeneration();desired=true;closing=false;
        cancelNetwork();
        // Keep the already established VPN interface during node/mode changes.
        // Closing it here would briefly expose other apps to the underlying network.
        stopFuture=stopEngine(!privacyGuard(),null);
        mode=ConnectionMode.from(intent.getStringExtra("mode"));
        country=intent.getStringExtra("country");if(country==null)country="";
        String requested=country,requestedNode=intent.getStringExtra("node");if(requestedNode==null)requestedNode="";
        String exactNode=requestedNode;node="";startedAt=SystemClock.elapsedRealtime();connectedAt=0;up=down=0;
        publish("checking",R.string.vpn_checking);
        worker.submit(()->connect(g,requested,exactNode,restoring));
        return START_STICKY;
    }

    private void guard(int g) throws InterruptedException {
        if(!desired||generation.get()!=g||Thread.currentThread().isInterrupted())throw new InterruptedException();
    }
    private void connect(int g,String requestedCountry,String requestedNode,boolean restoring) {
        GvApi current=new GvApi(this);api=current;
        try {
            Future<?> previous=stopFuture;if(previous!=null)previous.get(12,TimeUnit.SECONDS);
            guard(g);token=session.token();device=session.deviceId();
            if(token.isBlank())throw new IllegalStateException("login");
            // Reestablish protection before account/device/node checks on a restart.
            // Our own control sockets remain on the physical network.
            if(restoring&&privacyGuard())ensureTunnel(g);
            entitlement=new GvApi.Entitlement(current.call("GET","/api/entitlement",null,token));guard(g);
            if(!entitlement.usable()) { failFor(g,R.string.vpn_plan_required);return; }
            current.call("POST","/api/devices",new JSONObject().put("device_id",device)
                .put("device_name","Global Villager Android"),token);guard(g);
            publishFor(g,"selecting",R.string.vpn_selecting);
            String path="/api/connect-candidates?device_id="+encode(device)+"&mode=tcp"+(!requestedNode.isEmpty()||requestedCountry.isEmpty()?"":"&country="+encode(requestedCountry));
            JSONArray candidates=current.call("GET",path,null,token).object().getJSONArray("candidates");guard(g);
            RouteCandidates.Route route=RouteCandidates.select(new RouteHealthStore(this).order(RouteCandidates.parse(candidates)),requestedCountry,requestedNode);
            if(route==null) {failFor(g,requestedNode.isEmpty()?R.string.vpn_no_route:R.string.vpn_selected_route_unavailable);return;}
            node=route.node;country=route.country;
            if(BuildConfig.DEBUG)android.util.Log.i("GvPhoneQA","selected_node="+node);
            publishFor(g,"provisioning",R.string.vpn_provisioning);
            JSONObject config=current.call("POST","/api/provision-exact",new JSONObject().put("device_id",device)
                .put("node",node).put("protocol","xray-reality"),token).object();guard(g);
            long deadline=SystemClock.elapsedRealtime()+45000;int polls=0;
            while(!"ready".equals(config.optString("status"))) {
                if("failed".equals(config.optString("status")))throw new IllegalStateException("provision failed");
                if(SystemClock.elapsedRealtime()>deadline) {failFor(g,R.string.vpn_provision_timeout);return;}
                long job=config.optLong("job_id",0);if(job<=0)throw new IllegalStateException("missing job");
                Thread.sleep(Math.min(1500,300+200L*polls++));guard(g);
                JSONObject reply=current.call("GET","/api/provision/"+job,null,token).object();guard(g);
                reply.put("job_id",job);config=reply;
            }
            if(mode==ConnectionMode.SMART)RoutingAssets.ensure(this);
            String nativeConfig=ConfigCodec.forAndroid(config.getString("config"),mode);guard(g);
            publishFor(g,"starting",R.string.vpn_starting);
            ensureTunnel(g);
            synchronized(this) {
                guard(g);
                core.startLoop(nativeConfig,tunnel.getFd());
            }
            guard(g);publishFor(g,"verifying",R.string.vpn_verifying);
            long delay=probe(core);guard(g);
            if(delay<0||!core.getIsRunning())throw new IllegalStateException("probe failed");
            new RouteHealthStore(this).success(node,delay);
            connectedAt=SystemClock.elapsedRealtime();lastLeaseCheck=connectedAt;health.success();everConnected=true;
            publishFor(g,"connected",R.string.connected);
        } catch(InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch(Exception e) {
            if(generation.get()==g&&desired&&!(e instanceof GvApi.ApiException)&&!node.isEmpty())new RouteHealthStore(this).failure(node);
            String diagnosticDetail=String.valueOf(e instanceof go.error?((go.error)e).error():e.getMessage());
            android.util.Log.w("GvConnectionFault",SafeConnectionFault.summarize(status.phase,e.getClass().getSimpleName(),diagnosticDetail));
            // Test builds expose the failing stage/type, never config, token,
            // user identity or exception text containing server credentials.
            if(BuildConfig.DEBUG){String hint="";String detail=String.valueOf(e instanceof go.error?((go.error)e).error():e.getMessage()).toLowerCase(java.util.Locale.ROOT);
                for(String reason:new String[]{"x509","certificate","timeout","deadline","refused","no such host","eof","reset","tls","configuration","address already","permission"})
                    if(detail.contains(reason))hint+=" "+reason;
                android.util.Log.w("GvPhoneQA",status.phase+": "+e.getClass().getSimpleName()+hint);}
            if(generation.get()==g&&desired)failFor(g,e instanceof GvApi.ApiException?((GvApi.ApiException)e).friendlyResource():
                R.string.vpn_connection_failed,ReconnectPolicy.transientError(e));
        }
    }

    private synchronized void ensureTunnel(int g) throws Exception {
        guard(g);if(tunnel!=null)return;
        // IPv6 is deliberately blocked until the nodes support it. Never allow
        // cached AAAA answers to fall through to the underlying network.
        Builder builder=new Builder().setSession("Global Villager").setMtu(1500)
            .addAddress("10.77.0.2",30).addRoute("0.0.0.0",0)
            .addDnsServer("1.1.1.1").addDnsServer("8.8.8.8")
            .addDisallowedApplication(getPackageName()).setBlocking(false);
        if(Build.VERSION.SDK_INT>=29)builder.setMetered(false);
        tunnel=builder.establish();
        if(tunnel==null)throw new IllegalStateException("VPN permission revoked");
    }

    private static String encode(String value){try{return URLEncoder.encode(value,"UTF-8");}catch(java.io.UnsupportedEncodingException impossible){throw new AssertionError(impossible);}}
    private long probe(CoreController controller) throws Exception {
        Future<Long> result=probeWorker.submit(()->TunnelVerification.verify(controller::verifyConnection));
        try{return result.get(10,TimeUnit.SECONDS);}
        catch(ExecutionException e){Throwable cause=e.getCause();if(cause instanceof Exception)throw (Exception)cause;throw e;}
        finally{controller.cancelVerification();result.cancel(true);}
    }
    private void tick() {
        refreshSystemOwnership();
        if(!desired||!status.connected())return;
        int g=generation.get();long now=SystemClock.elapsedRealtime();
        GvApi.Entitlement access=entitlement;
        if(access==null||!access.usable()) {failFor(g,R.string.vpn_plan_expired);return;}
        if(!core.getIsRunning()) {failFor(g,R.string.vpn_tunnel_stopped,true);return;}
        try {
            String stats=core.queryAllOutboundTrafficStats();
            for(String part:stats.split(";")) {
                String[] columns=part.split(",");if(columns.length!=3||!"gv-proxy".equals(columns[0]))continue;
                long bytes=Math.max(0,Long.parseLong(columns[2]));
                if("uplink".equals(columns[1]))up+=bytes;if("downlink".equals(columns[1]))down+=bytes;
            }
            publishFor(g,"connected",health.rechecking()?R.string.vpn_rechecking:R.string.connected);
        }catch(Exception ignored){}
        if(now-lastLeaseCheck>health.intervalMs()&&!leaseBusy) {
            leaseBusy=true;
            leaseWorker.submit(()-> {
                String checkStage="entitlement";
                try {
                    GvApi a=api;if(a==null)return;
                    GvApi.Entitlement renewed=new GvApi.Entitlement(a.call("GET","/api/entitlement",null,token));guard(g);
                    if(!renewed.usable()) {failFor(g,R.string.vpn_access_expired);return;}
                    // A live candidate check also validates that this exact device
                    // has not been disabled since the tunnel was established.
                    checkStage="device";
                    a.call("GET","/api/connect-candidates?device_id="+encode(device)+"&mode=tcp",null,token);guard(g);
                    checkStage="tunnel";
                    long delay=probe(core);guard(g);
                    if(delay<0)throw new IllegalStateException("Network probe failed");
                    new RouteHealthStore(this).success(node,delay);
                    entitlement=renewed;lastLeaseCheck=SystemClock.elapsedRealtime();health.success();recoveryPolicy.reset();
                    if(BuildConfig.DEBUG)android.util.Log.i("GvPhoneQA","periodic_check_ok");
                }catch(InterruptedException ignored){}catch(Exception e){
                    if(generation.get()==g&&desired){
                        if(BuildConfig.DEBUG)android.util.Log.w("GvPhoneQA","periodic_check_stage="+checkStage+" error="+e.getClass().getSimpleName()+
                            (e instanceof GvApi.ApiException?" http="+((GvApi.ApiException)e).status:""));
                        if(health.retry(e)){
                            lastLeaseCheck=SystemClock.elapsedRealtime();
                            publishFor(g,"connected",R.string.vpn_rechecking);
                        }else {
                            if("tunnel".equals(checkStage)&&!node.isEmpty())new RouteHealthStore(this).failure(node);
                            failFor(g,e instanceof GvApi.ApiException?((GvApi.ApiException)e).friendlyResource():R.string.vpn_check_failed,
                                "tunnel".equals(checkStage)||ReconnectPolicy.transientError(e));
                        }
                    }
                }
                finally{leaseBusy=false;}
            });
        }
    }

    private void failFor(int g,int message) {failFor(g,message,false);}
    private void failFor(int g,int message,boolean retryable) {synchronized(stateLock){if(generation.get()==g&&desired)failure(message,retryable);}}
    private void failure(int message) {failure(message,false);}
    private void failure(int message,boolean retryable) {
        int g=nextGeneration();desired=false;closing=true;cancelNetwork();
        boolean keep=privacyGuard()&&tunnel!=null;
        failureResource=message;
        long delay=recoveryPolicy.nextDelayMs(everConnected,keep,retryable&&userRequested);
        main.removeCallbacks(recovery);recoveryScheduled=delay>=0;
        if(!recoveryScheduled)userRequested=false;
        publish(keep?"blocked":"error",keep?(recoveryScheduled?R.string.vpn_recovering:R.string.vpn_guard_blocked):message);
        if(keep){
            stopFuture=stopEngine(false,null);
            getSystemService(NotificationManager.class).notify(7,notification(status.messageResource));
            if(recoveryScheduled)main.postDelayed(recovery,delay);
        }else {resumeState.stop();stopFuture=stopEngine(()->{if(generation.get()==g&&!desired){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}});}
    }
    private void stopRequested() {
        resumeState.stop();
        userRequested=false;recoveryScheduled=false;main.removeCallbacks(recovery);recoveryPolicy.reset();failureResource=0;
        int g=nextGeneration();desired=false;closing=true;cancelNetwork();
        publish("stopping",R.string.vpn_stopping);
        stopFuture=stopEngine(()->{if(generation.get()==g&&!desired){connectedAt=0;publish("idle",R.string.disconnected);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}});
    }
    private void cancelNetwork(){GvApi a=api;if(a!=null)a.cancel();}
    private synchronized ParcelFileDescriptor takeTunnel(){ParcelFileDescriptor fd=tunnel;tunnel=null;return fd;}
    private Future<?> stopEngine(){return stopEngine(null);}
    private Future<?> stopEngine(Runnable completed){
        return stopEngine(true,completed);
    }
    private Future<?> stopEngine(boolean closeTunnel,Runnable completed){
        ParcelFileDescriptor retired=closeTunnel?takeTunnel():null;
        CoreController retiringCore=core;
        retiringCore.cancelVerification();
        return engineStopper.submit(()->{
            try{retiringCore.stopLoop();}catch(Exception ignored){}
            finally{if(retired!=null)try{retired.close();}catch(Exception ignored){}}
            // Native gVisor Attach(nil) waits for its FD reader to stop. Only
            // then may Android close/recycle the Java-owned descriptor.
            if(completed!=null)main.post(completed);
        });
    }
    private int nextGeneration(){synchronized(stateLock){return generation.incrementAndGet();}}
    private void publishFor(int g,String phase,int message){synchronized(stateLock){if(generation.get()==g&&desired)publish(phase,message);}}
    private void publish(String phase,int message) {
        String previousPhase=status.phase;
        status=new Snapshot(phase,message,node,country,mode,connectedAt,startedAt,up,down,failureResource);
        if(BuildConfig.DEBUG&&!previousPhase.equals(phase))android.util.Log.i("GvPhoneQA","state="+phase+" elapsed_ms="+(startedAt==0?0:Math.max(0,SystemClock.elapsedRealtime()-startedAt)));
        main.post(()-> {
            sendBroadcast(new Intent(UPDATE).setPackage(getPackageName()));
            if(desired||status.blocking()) {
                Snapshot snapshot=status;
                String key=snapshot.phase+":"+snapshot.messageResource+":"+systemManaged+":"+AppLanguage.renderKey(this);
                if(!key.equals(notifiedKey)){notifiedKey=key;getSystemService(NotificationManager.class).notify(7,notification(snapshot.messageResource));}
            }
        });
    }
    private Notification notification(int messageResource) {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent disconnect=PendingIntent.getService(this,1,new Intent(this,GvVpnService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent settings=PendingIntent.getActivity(this,2,new Intent(Settings.ACTION_VPN_SETTINGS),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder=new Notification.Builder(this,"gv-vpn").setSmallIcon(R.drawable.gv_icon).setContentTitle("Global Villager")
            .setContentText(AppLanguage.text(this,messageResource)).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE);
        if(!systemManaged)builder.addAction(new Notification.Action.Builder(null,AppLanguage.text(this,R.string.disconnect),disconnect).build());
        return builder.addAction(new Notification.Action.Builder(null,AppLanguage.text(this,R.string.system_vpn_settings),settings).build()).build();
    }
    @Override public void onConfigurationChanged(Configuration config){
        super.onConfigurationChanged(config);
        if(desired||status.blocking()){notifiedKey="";getSystemService(NotificationManager.class).notify(7,notification(status.messageResource));}
    }
    // Closing/removing the UI task does not withdraw the VPN request.
    @Override public void onTaskRemoved(Intent rootIntent){super.onTaskRemoved(rootIntent);}
    @Override public void onRevoke(){stopRequested();super.onRevoke();}
    @Override public void onDestroy() {
        systemManaged=false;userRequested=false;recoveryScheduled=false;main.removeCallbacksAndMessages(null);
        if(connectivity!=null&&networkCallback!=null)try{connectivity.unregisterNetworkCallback(networkCallback);}catch(RuntimeException ignored){}
        desired=false;nextGeneration();cancelNetwork();ticker.shutdownNow();worker.shutdownNow();leaseWorker.shutdownNow();probeWorker.shutdownNow();
        stopEngine();
        if(status.startedAt==startedAt&&!"error".equals(status.phase))status=new Snapshot("idle",R.string.disconnected,"","",0,0,0,0);
        super.onDestroy();
    }
}
