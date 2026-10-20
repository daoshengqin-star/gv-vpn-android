package com.globalvillager.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Read-only comparison with the server's order history, never a payment instruction. */
public final class BillingReview {
    public enum Outcome { CONFIRMED, PAID_SYNCING, SCHEDULED, PENDING, NEEDS_REVIEW, NO_CHANGE, UNKNOWN }
    private BillingReview() {}
    public static JSONObject capture(JSONArray orders) throws Exception {
        JSONObject baseline=new JSONObject();
        for(int i=0;i<orders.length();i++) {
            JSONObject order=orders.getJSONObject(i);
            String id=order.optString("order_id");
            if(id.isBlank())throw new IllegalArgumentException("Order identity absent");
            baseline.put(hash(id),new JSONObject().put("state",order.optString("payment_state"))
                .put("plan",effectivePlan(order)));
        }
        return baseline;
    }
    public static Outcome inspect(JSONObject baseline,JSONArray orders,boolean entitlementUsable) throws Exception {
        if(baseline==null)return Outcome.UNKNOWN;
        // Orders are newest first. Include upgrades of an existing paid order,
        // but never claim a pre-existing unchanged paid plan is a new purchase.
        for(int i=0;i<orders.length();i++) {
            JSONObject order=orders.getJSONObject(i);
            String id=order.optString("order_id");if(id.isBlank())return Outcome.UNKNOWN;
            JSONObject before=baseline.optJSONObject(hash(id));
            String state=order.optString("payment_state");
            // An older paid period naturally expiring or starting must not
            // resolve a new checkout whose order has not arrived yet.
            boolean purchaseChanged=before==null||!effectivePlan(order).equals(before.optString("plan"))
                ||("pending".equals(before.optString("state"))&&!state.equals(before.optString("state")));
            if(purchaseChanged)
                return outcome(state,entitlementUsable);
        }
        for(int i=0;i<orders.length();i++)if("pending".equals(orders.getJSONObject(i).optString("payment_state")))return Outcome.PENDING;
        return Outcome.NO_CHANGE;
    }
    private static Outcome outcome(String state,boolean usable) {
        return switch(state) {
            case "paid" -> usable?Outcome.CONFIRMED:Outcome.PAID_SYNCING;
            case "pending" -> Outcome.PENDING;
            case "paid_not_started" -> Outcome.SCHEDULED;
            case "expired","revoked" -> Outcome.NEEDS_REVIEW;
            default -> Outcome.UNKNOWN;
        };
    }
    private static String effectivePlan(JSONObject order) {
        String active=order.optString("active_plan_code","");
        return active.isBlank()?order.optString("plan_code",""):active;
    }
    private static String hash(String value) throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex=new StringBuilder(64);for(byte b:digest)hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return hex.toString();
    }
}
