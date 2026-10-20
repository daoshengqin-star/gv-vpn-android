package com.globalvillager.mobile;

import org.json.JSONArray;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class RouteCandidatesTest {
    private List<RouteCandidates.Route> routes()throws Exception {
        return RouteCandidates.parse(new JSONArray("["+
            "{\"node\":\"US01\",\"country\":\"US\",\"protocol\":\"xray-reality\"},"+
            "{\"node\":\"US02\",\"country\":\"US\",\"protocol\":\"xray-reality\"},"+
            "{\"node\":\"JP01\",\"country\":\"JP\",\"protocol\":\"xray-reality\"},"+
            "{\"node\":\"SG01\",\"country\":\"SG\",\"protocol\":\"xray-reality\"}]"));
    }
    @Test public void fourServersAcrossThreeCountriesRemainFourChoices()throws Exception {
        List<RouteCandidates.Route> routes=routes();assertEquals(4,routes.size());
        assertEquals("US01",routes.get(0).node);assertEquals("US02",routes.get(1).node);
    }
    @Test public void everyExplicitChoiceSelectsThatExactServer()throws Exception {
        List<RouteCandidates.Route> routes=routes();
        for(RouteCandidates.Route route:routes)assertSame(route,RouteCandidates.select(routes,route.country,route.node));
        assertEquals("US02",RouteCandidates.select(routes,"US","US02").node);
    }
    @Test public void unavailableExplicitServerNeverFallsBack()throws Exception {
        assertNull(RouteCandidates.select(routes(),"US","US99"));
    }
    @Test public void automaticAndLegacyCountryKeepServerOrder()throws Exception {
        assertEquals("US01",RouteCandidates.select(routes(),"","").node);
        assertEquals("JP01",RouteCandidates.select(routes(),"JP","").node);
        assertNull(RouteCandidates.select(routes(),"HK",""));
    }
    @Test public void duplicateProtocolsDoNotCreateDuplicateServerChoices()throws Exception {
        List<RouteCandidates.Route> list=RouteCandidates.parse(new JSONArray("["+
            "{\"node\":\"US01\",\"country\":\"US\",\"protocol\":\"xray-reality\"},"+
            "{\"node\":\"US01\",\"country\":\"US\",\"protocol\":\"xray-reality\"},"+
            "{\"node\":\"US02\",\"country\":\"US\",\"protocol\":\"wireguard\"},"+
            "{\"node\":\"\",\"protocol\":\"xray-reality\"}]"));
        assertEquals(1,list.size());assertEquals("US01",list.get(0).node);
    }
}
