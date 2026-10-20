package com.globalvillager.mobile;
import org.junit.Test;
import org.json.JSONArray;
import java.util.*;
import static org.junit.Assert.*;

public class RouteRankingTest {
    private List<RouteCandidates.Route> routes()throws Exception{return RouteCandidates.parse(new JSONArray("[{\"node\":\"US01\",\"country\":\"US\",\"protocol\":\"xray-reality\"},{\"node\":\"JP01\",\"country\":\"JP\",\"protocol\":\"xray-reality\"},{\"node\":\"US02\",\"country\":\"US\",\"protocol\":\"xray-reality\"}]"));}
    @Test public void automaticChoiceUsesVerifiedLatency()throws Exception{
        List<RouteCandidates.Route> ranked=RouteCandidates.ranked(routes(),Map.of("US01",180L,"JP01",65L));
        assertEquals("JP01",RouteCandidates.select(ranked,"","").node);assertEquals("US02",ranked.get(2).node);
    }
    @Test public void recentFailureMovesBehindUnmeasuredCandidates()throws Exception{
        List<RouteCandidates.Route> ranked=RouteCandidates.ranked(routes(),Map.of("US01",Long.MAX_VALUE));
        assertEquals("JP01",ranked.get(0).node);assertEquals("US01",ranked.get(2).node);
    }
    @Test public void explicitChoiceDoesNotSilentlyChange()throws Exception{
        List<RouteCandidates.Route> ranked=RouteCandidates.ranked(routes(),Map.of("US01",Long.MAX_VALUE,"JP01",50L));
        assertEquals("US01",RouteCandidates.select(ranked,"US","US01").node);
    }
    @Test public void unmeasuredAndEqualScoreRoutesKeepServerOrder()throws Exception{
        assertEquals("US01",RouteCandidates.ranked(routes(),Map.of()).get(0).node);
        assertEquals("US01",RouteCandidates.ranked(routes(),Map.of("US01",50L,"JP01",50L)).get(0).node);
    }
}
