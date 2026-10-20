package com.globalvillager.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;

/** Only node names and bounded timing are saved locally; no user browsing history. */
public final class RouteHealthStore {
    private static final long FRESH_MS=10*60*1000,FAILED_MS=2*60*1000;
    private final SharedPreferences prefs;
    public RouteHealthStore(Context context){prefs=context.getSharedPreferences("gv-route-health",Context.MODE_PRIVATE);}
    public void success(String node,long delay){if(valid(node)&&delay>=0)prefs.edit().putLong(node+".delay",delay).putLong(node+".at",System.currentTimeMillis()).remove(node+".failed").apply();}
    public void failure(String node){if(valid(node))prefs.edit().putLong(node+".failed",System.currentTimeMillis()).apply();}
    public long delay(String node){long age=System.currentTimeMillis()-prefs.getLong(node+".at",0);return age>=0&&age<FRESH_MS?prefs.getLong(node+".delay",-1):-1;}
    public boolean recentlyFailed(String node){long age=System.currentTimeMillis()-prefs.getLong(node+".failed",0);return age>=0&&age<FAILED_MS;}
    public List<RouteCandidates.Route> order(List<RouteCandidates.Route> routes){
        Map<String,Long> scores=new HashMap<>();
        for(RouteCandidates.Route route:routes){long delay=delay(route.node);scores.put(route.node,recentlyFailed(route.node)?Long.MAX_VALUE:delay>=0?delay:Long.MAX_VALUE-1);}
        // Stable sort: preserve the server's order for unmeasured candidates.
        // Snapshot each score once so a timer update cannot change a comparator mid-sort.
        return RouteCandidates.ranked(routes,scores);
    }
    private static boolean valid(String node){return node!=null&&node.matches("[A-Za-z0-9_-]{1,40}");}
}
