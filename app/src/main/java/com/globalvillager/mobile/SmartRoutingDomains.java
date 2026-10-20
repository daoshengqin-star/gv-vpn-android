package com.globalvillager.mobile;
import org.json.JSONArray;
import org.json.JSONObject;
/** Narrow domestic exceptions after protected foreign roots; unknown names remain proxied. */
final class SmartRoutingDomains {
    private SmartRoutingDomains(){}
    static JSONArray protectedForeign(){return new JSONArray().put("domain:accountkit.com").put("domain:chatgpt.com").put("domain:cloudflare-dns.com").put("domain:dns.google").put("domain:facebook.com").put("domain:facebook.net").put("domain:fastspring.com").put("domain:fb.com").put("domain:fb.watch").put("domain:fbcdn-video.net").put("domain:fbcdn.com").put("domain:fbcdn.net").put("domain:fbsbx.com").put("domain:ggpht.com").put("domain:google.com").put("domain:googleapis.com").put("domain:googleusercontent.com").put("domain:googlevideo.com").put("domain:gvt1.com").put("domain:gvt2.com").put("domain:m.me").put("domain:messenger.com").put("domain:openai.com").put("domain:paddle.com").put("domain:paypal.com").put("domain:paypalobjects.com").put("domain:stripe.com").put("domain:stripe.network").put("domain:tello.com").put("domain:worldcitizenvpn.com").put("domain:youtube-nocookie.com").put("domain:youtube.com").put("domain:youtube.googleapis.com").put("domain:youtubei.googleapis.com").put("domain:ytimg.com");}
    static JSONArray domesticAssets(){return new JSONArray().put("regexp:.+\\.awsdns-cn-[0-9][0-9]\\.(biz|com|net|top)$").put("regexp:.+\\.awsdns-cn-[0-9][a-e0-9]\\.cn$").put("regexp:^.+-mihayo\\.akamaized\\.net$");}
    static JSONArray priorityResolvers()throws Exception{
        JSONArray result=new JSONArray();
        pair(result,protectedForeign(),"gv-dns-query","https://1.1.1.1/dns-query","https://8.8.8.8/dns-query");
        pair(result,domesticAssets(),"gv-dns-cn","https://223.5.5.5/dns-query","https://doh.pub/dns-query");
        return result;
    }
    private static void pair(JSONArray result,JSONArray domains,String tag,String first,String second)throws Exception{
        result.put(new JSONObject().put("address",first).put("domains",domains).put("tag",tag).put("skipFallback",true));
        result.put(new JSONObject().put("address",second).put("domains",domains).put("tag",tag).put("skipFallback",true).put("finalQuery",true));
    }
}
