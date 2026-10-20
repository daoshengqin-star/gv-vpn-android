package com.globalvillager.mobile;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.globalvillager.mobile.BillingReview.Outcome.*;

public class BillingReviewTest {
    @Test public void oldOrderExpiryCannotOverrideANewPendingPurchase()throws Exception {
        JSONObject baseline=BillingReview.capture(list(order("old","paid","m3")));
        assertEquals(PENDING,BillingReview.inspect(baseline,list(order("old","expired","m3"),order("new","pending","m12")),false));
    }
    @Test public void oldScheduledPeriodStartingCannotConfirmANewCheckout()throws Exception {
        JSONObject baseline=BillingReview.capture(list(order("old","paid_not_started","m3")));
        assertEquals(NO_CHANGE,BillingReview.inspect(baseline,list(order("old","paid","m3")),true));
    }
    @Test public void newPaymentIsFoundAfterAnOldOrderEnds()throws Exception {
        JSONObject baseline=BillingReview.capture(list(order("old","paid","m3")));
        assertEquals(CONFIRMED,BillingReview.inspect(baseline,list(order("old","revoked","m3"),order("new","paid","m12")),true));
    }
    private JSONObject order(String id,String state,String plan)throws Exception{return new JSONObject().put("order_id",id).put("payment_state",state).put("plan_code",plan);}
    private JSONArray list(JSONObject... orders){JSONArray result=new JSONArray();for(JSONObject o:orders)result.put(o);return result;}
    @Test public void oldPaidAccessDoesNotConfirmNewPayment()throws Exception {
        JSONArray old=list(order("old","paid","m3"));assertEquals(NO_CHANGE,BillingReview.inspect(BillingReview.capture(old),old,true));
    }
    @Test public void newPendingOrderRemainsPendingDespiteOldPaidAccess()throws Exception {
        JSONObject old=order("old","paid","m3");assertEquals(PENDING,BillingReview.inspect(BillingReview.capture(list(old)),list(order("new","pending","m12"),old),true));
    }
    @Test public void newPaidOrderNeedsServerAccessToBeUsable()throws Exception {
        JSONObject baseline=BillingReview.capture(new JSONArray());JSONArray paid=list(order("new","paid","m3"));
        assertEquals(PAID_SYNCING,BillingReview.inspect(baseline,paid,false));assertEquals(CONFIRMED,BillingReview.inspect(baseline,paid,true));
    }
    @Test public void anExistingPendingOrderCanComplete()throws Exception {
        JSONObject old=BillingReview.capture(list(order("existing","pending","m3")));
        assertEquals(PENDING,BillingReview.inspect(old,list(order("existing","pending","m3")),false));
        assertEquals(CONFIRMED,BillingReview.inspect(old,list(order("existing","paid","m3")),true));
    }
    @Test public void upgradeOnSameOrderIsDetected()throws Exception {
        JSONObject baseline=BillingReview.capture(list(order("same","paid","m3")));
        JSONObject upgraded=order("same","paid","m3").put("active_plan_code","m12");
        assertEquals(CONFIRMED,BillingReview.inspect(baseline,list(upgraded),true));
    }
    @Test public void noBaselineCannotConfirmAnOldPayment()throws Exception {
        assertEquals(UNKNOWN,BillingReview.inspect(null,list(order("old","paid","m3")),true));
    }
    @Test public void endedAndExpiredOrdersNeverMeanPaymentSucceeded()throws Exception {
        for(String state:new String[]{"revoked","expired"})assertEquals(NEEDS_REVIEW,BillingReview.inspect(new JSONObject(),list(order("o",state,"m3")),true));
    }
    @Test public void futurePaidPeriodIsNotUnpaidOrAvailableNow()throws Exception {
        assertEquals(SCHEDULED,BillingReview.inspect(new JSONObject(),list(order("o","paid_not_started","m3")),false));
    }
    @Test public void unknownAndMissingIdentityFailClosed()throws Exception {
        assertEquals(UNKNOWN,BillingReview.inspect(new JSONObject(),list(order("o","unexpected","m3")),true));
        assertEquals(UNKNOWN,BillingReview.inspect(new JSONObject(),list(new JSONObject().put("payment_state","paid")),true));
    }
    @Test public void baselineContainsNoRawOrderOrSubscriptionIds()throws Exception {
        String raw=BillingReview.capture(list(order("cs_private_identity","paid","m3").put("subscription_id","sub_private_identity"))).toString();
        assertFalse(raw.contains("cs_private_identity"));assertFalse(raw.contains("sub_private_identity"));
    }
}
