package com.globalvillager.mobile;
import org.junit.Test;
import org.json.*;
import static org.junit.Assert.*;
public class ApiResponseTest {
    @Test public void htmlErrorsKeepTheirHttpStatus()throws Exception {
        for(int code:new int[]{401,403,429,503})try{ApiResponse.parse(code,"<html>Proxy error</html>");fail();}
        catch(GvApi.ApiException e){assertEquals(code,e.status);}
    }
    @Test public void jsonErrorsPreserveTheServerDetail()throws Exception {
        try{ApiResponse.parse(403,"{\"detail\":\"device limit\"}");fail();}
        catch(GvApi.ApiException e){assertEquals(403,e.status);assertEquals(R.string.api_device_limit,e.friendlyResource());}
    }
    @Test public void successfulObjectsArraysAndEmptyBodiesParse()throws Exception {
        assertTrue(ApiResponse.parse(200,"[]") instanceof JSONArray);
        assertTrue(((JSONObject)ApiResponse.parse(200,"{\"active\":true}")).getBoolean("active"));
        assertEquals(0,((JSONObject)ApiResponse.parse(204," ")).length());
    }
    @Test public void malformedSuccessfulResponseFailsClosed()throws Exception {
        try{ApiResponse.parse(200,"<html>Login redirect</html>");fail();}catch(JSONException expected){}
    }
    @Test public void trialAdmissionErrorsGiveAnActionableReason()throws Exception {
        try{ApiResponse.parse(403,"{\"detail\":\"trial device mismatch\"}");fail();}
        catch(GvApi.ApiException e){assertEquals(R.string.trial_original_device,e.friendlyResource());}
        try{ApiResponse.parse(403,"{\"detail\":\"trial entitlement expired\"}");fail();}
        catch(GvApi.ApiException e){assertEquals(R.string.trial_window_ended,e.friendlyResource());}
    }
}
