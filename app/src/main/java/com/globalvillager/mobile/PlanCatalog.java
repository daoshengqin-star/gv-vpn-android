package com.globalvillager.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Display only server-approved website prices; never invent a purchasable offer. */
public final class PlanCatalog {
    public record Item(String code,int months,long cents) {}
    public record Result(List<Item> items,boolean checkoutEnabled) {}
    private static final Map<String,Integer> MONTHS=Map.of("gv_1m",1,"gv_3m",3,"gv_6m",6,"gv_12m",12,"gv_24m_web",24);
    private PlanCatalog(){}
    public static boolean allowedCode(String value){return value!=null&&MONTHS.containsKey(value);}
    public static Result parse(JSONObject data) throws Exception {
        if(!"usd".equals(data.optString("currency")))throw new IllegalArgumentException("Unsupported currency");
        JSONArray rows=data.getJSONArray("plans");if(rows.length()>20)throw new IllegalArgumentException("Too many plans");
        List<Item> result=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);String code=row.getString("code");Integer months=MONTHS.get(code);
            if(months==null||!seen.add(code))throw new IllegalArgumentException("Unexpected plan");
            Object raw=row.get("amount_cents"),count=row.get("interval_count");
            if(!(raw instanceof Integer||raw instanceof Long)||!(count instanceof Integer||count instanceof Long))throw new IllegalArgumentException("Non-integer price or period");
            long cents=((Number)raw).longValue(),intervalCount=((Number)count).longValue();String interval=row.getString("interval");
            if(cents<=0||cents>10000000||intervalCount<=0||intervalCount>24||!(interval.equals("month")||interval.equals("year")))throw new IllegalArgumentException("Invalid offer");
            if(intervalCount*(interval.equals("year")?12:1)!=months)throw new IllegalArgumentException("Period mismatch");
            result.add(new Item(code,months,cents));
        }
        result.sort(Comparator.comparingInt(Item::months));
        return new Result(List.copyOf(result),data.optBoolean("website_checkout_enabled",false));
    }
}
