package com.globalvillager.mobile;
import org.junit.Test;
import org.json.*;
import static org.junit.Assert.*;
public class TrialReviewTest {
    private GvApi.Entitlement access(boolean active,String plan)throws Exception{return new GvApi.Entitlement(new GvApi.Reply(new JSONObject().put("active",active).put("subscription",new JSONObject().put("plan",plan).put("expires_at","2040-01-01T00:00:00Z")),System.currentTimeMillis()));}
    private JSONObject claim(String state)throws Exception{return new JSONObject().put("state",state);}
    @Test public void grantNeedsAuthoritativeUsableTrialAccess()throws Exception{
        assertEquals(R.string.trial_unavailable,TrialReview.message(claim("granted"),access(false,"trial_2h")));
        assertEquals(R.string.trial_ready,TrialReview.message(claim("granted"),access(true,"trial_2h")));
    }
    @Test public void repeatedDeviceAndOldSignupAreNotShownAsFreeAccess()throws Exception{
        assertEquals(R.string.trial_device_used,TrialReview.message(claim("device_already_received"),access(false,"")));
        assertEquals(R.string.trial_window_ended,TrialReview.message(claim("registration_window_ended"),access(false,"")));
    }
    @Test public void aPaidPlanNeverBecomesATrialWelcome()throws Exception{
        assertEquals(0,TrialReview.message(claim("existing_access"),access(true,"m12")));
        assertEquals(0,TrialReview.message(claim("already_received"),access(true,"m12")));
    }
    @Test public void missingAndUnknownReceiptsNeverPromiseActivation()throws Exception{
        assertEquals(0,TrialReview.message(null,access(false,"")));
        assertEquals(R.string.trial_unavailable,TrialReview.message(claim("unexpected"),access(false,"")));
    }
}
