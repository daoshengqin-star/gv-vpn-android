package com.globalvillager.mobile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;

/** One choice per server, shared by the picker and exact provisioning. */
public final class RouteCandidates {
    public static final class Route {
        public final String node,country;
        Route(String node,String country){this.node=node;this.country=country;}
    }
    public static List<Route> parse(JSONArray candidates)throws JSONException {
        LinkedHashMap<String,Route> routes=new LinkedHashMap<>();
        for(int i=0;i<candidates.length();i++) {
            JSONObject candidate=candidates.getJSONObject(i);
            String node=candidate.optString("node").trim();
            if(!"xray-reality".equals(candidate.optString("protocol"))||node.isEmpty())continue;
            routes.putIfAbsent(node,new Route(node,candidate.optString("country").trim()));
        }
        return new ArrayList<>(routes.values());
    }
    public static Route select(List<Route> candidates,String country,String node) {
        for(Route candidate:candidates) {
            if(!node.isEmpty()) {if(node.equals(candidate.node))return candidate;}
            else if(country.isEmpty()||country.equals(candidate.country))return candidate;
        }
        // An unavailable explicit choice must never silently connect elsewhere.
        return null;
    }
    public static List<Route> ranked(List<Route> candidates,Map<String,Long> recentScores){
        List<Route> result=new ArrayList<>(candidates);
        result.sort(Comparator.comparingLong(route->recentScores.getOrDefault(route.node,Long.MAX_VALUE-1)));
        return result;
    }
}
