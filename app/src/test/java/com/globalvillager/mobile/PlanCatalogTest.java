package com.globalvillager.mobile;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class PlanCatalogTest {
    private JSONObject row(String code,int months,long cents)throws Exception{return new JSONObject().put("code",code).put("amount_cents",cents).put("interval","month").put("interval_count",months);}
    private JSONObject data(JSONArray plans)throws Exception{return new JSONObject().put("currency","usd").put("plans",plans).put("website_checkout_enabled",true);}
    @Test public void usesServerPriceAndSortsFiveRealPeriods()throws Exception{
        JSONArray a=new JSONArray().put(row("gv_24m_web",24,9876)).put(row("gv_1m",1,899)).put(row("gv_12m",12,4567)).put(row("gv_3m",3,1234)).put(row("gv_6m",6,2345));
        var r=PlanCatalog.parse(data(a));assertEquals(5,r.items().size());assertEquals(899,r.items().get(0).cents());assertEquals(24,r.items().get(4).months());assertTrue(r.checkoutEnabled());
    }
    @Test public void disabledCheckoutCannotBeEnabledByPrice()throws Exception{assertFalse(PlanCatalog.parse(data(new JSONArray().put(row("gv_1m",1,899))).put("website_checkout_enabled",false)).checkoutEnabled());}
    @Test public void missingCheckoutIsDisabled()throws Exception{var d=data(new JSONArray());d.remove("website_checkout_enabled");assertFalse(PlanCatalog.parse(d).checkoutEnabled());}
    @Test public void emptyCatalogDoesNotInventOffers()throws Exception{assertTrue(PlanCatalog.parse(data(new JSONArray())).items().isEmpty());}
    @Test public void allowsEquivalentAnnualPeriod()throws Exception{assertEquals(12,PlanCatalog.parse(data(new JSONArray().put(row("gv_12m",12,1234).put("interval","year").put("interval_count",1)))).items().get(0).months());}
    @Test(expected=IllegalArgumentException.class) public void rejectsUnknownPlan()throws Exception{PlanCatalog.parse(data(new JSONArray().put(row("fake",1,1))));}
    @Test(expected=IllegalArgumentException.class) public void rejectsDuplicate()throws Exception{PlanCatalog.parse(data(new JSONArray().put(row("gv_1m",1,1)).put(row("gv_1m",1,2))));}
    @Test(expected=IllegalArgumentException.class) public void rejectsWrongPeriod()throws Exception{PlanCatalog.parse(data(new JSONArray().put(row("gv_3m",1,1))));}
    @Test(expected=IllegalArgumentException.class) public void rejectsOtherCurrency()throws Exception{PlanCatalog.parse(data(new JSONArray()).put("currency","cny"));}
    @Test(expected=IllegalArgumentException.class) public void rejectsZeroPrice()throws Exception{PlanCatalog.parse(data(new JSONArray().put(row("gv_1m",1,0))));}
    @Test(expected=IllegalArgumentException.class) public void rejectsFractionalCents()throws Exception{PlanCatalog.parse(data(new JSONArray().put(row("gv_1m",1,1).put("amount_cents",1.5))));}
    @Test(expected=IllegalArgumentException.class) public void rejectsStringPrice()throws Exception{PlanCatalog.parse(data(new JSONArray().put(row("gv_1m",1,1).put("amount_cents","899"))));}
    @Test(expected=IllegalArgumentException.class) public void rejectsFractionalPeriod()throws Exception{PlanCatalog.parse(data(new JSONArray().put(row("gv_1m",1,1).put("interval_count",1.5))));}
    @Test(expected=IllegalArgumentException.class) public void rejectsOversizePrice()throws Exception{PlanCatalog.parse(data(new JSONArray().put(row("gv_1m",1,10000001))));}
    @Test public void selectedPlanIsAnAllowlistedCode(){assertTrue(PlanCatalog.allowedCode("gv_24m_web"));assertFalse(PlanCatalog.allowedCode("https://untrusted.example"));assertFalse(PlanCatalog.allowedCode(null));}
}
