package com.globalvillager.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.InflaterInputStream;

/** Decodes only the user's server-issued Xray profile. No profile is logged. */
public final class ConfigCodec {
    private static final int MAX = 1024 * 1024;
    private ConfigCodec() { }

    public static JSONObject decode(String input) throws Exception {
        if (input == null || input.length() == 0 || input.length() > MAX * 2)
            throw new IllegalArgumentException("Invalid profile size");
        String value=input.trim();
        if (value.startsWith("vpn://")) {
            byte[] bytes=Base64.getUrlDecoder().decode(value.substring(6));
            if (bytes.length > 4 && bytes[4] == 0x78) {
                long expected=((bytes[0]&255L)<<24)|((bytes[1]&255L)<<16)|((bytes[2]&255L)<<8)|(bytes[3]&255L);
                if(expected<=0||expected>MAX)throw new IllegalArgumentException("Invalid compressed profile");
                try(InflaterInputStream in=new InflaterInputStream(new ByteArrayInputStream(bytes,4,bytes.length-4)); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                    byte[] block=new byte[8192];int n;
                    while((n=in.read(block))!=-1) { if(out.size()+n>MAX)throw new IllegalArgumentException("Profile too large");out.write(block,0,n); }
                    if(out.size()!=expected)throw new IllegalArgumentException("Incomplete compressed profile");
                    bytes=out.toByteArray();
                }
            }
            if(bytes.length>MAX)throw new IllegalArgumentException("Profile too large");
            value=new String(bytes,StandardCharsets.UTF_8);
        }
        JSONObject root=new JSONObject(value);
        if(root.has("outbounds"))return root;
        JSONObject candidate=extract(root);
        if(candidate!=null)return candidate;
        JSONArray containers=root.optJSONArray("containers");
        if(containers!=null)for(int i=0;i<containers.length();i++) {
            JSONObject item=containers.optJSONObject(i);if(item==null)continue;
            candidate=extract(item);if(candidate!=null)return candidate;
        }
        throw new IllegalArgumentException("Unsupported profile");
    }

    private static JSONObject extract(JSONObject o) throws Exception {
        for(String key:new String[]{"xray_config_data","ssxray_config_data","amnezia-xray"}) {
            JSONObject nested=o.optJSONObject(key);
            if(nested!=null) {
                if(nested.has("outbounds"))return nested;
                Object c=nested.opt("config");
                if(c instanceof JSONObject && ((JSONObject)c).has("outbounds"))return (JSONObject)c;
                if(c instanceof String && ((String)c).startsWith("{"))return new JSONObject((String)c);
                JSONObject inner=extract(nested);if(inner!=null)return inner;
            }
        }
        return null;
    }

    /** Android owns routes and the FD. Server-provided listener/API/file paths are discarded. */
    public static String forAndroid(String encoded) throws Exception {
        return forAndroid(encoded,ConnectionMode.GLOBAL);
    }
    public static String forAndroid(String encoded,ConnectionMode mode) throws Exception {
        JSONObject original=decode(encoded);JSONArray outbounds=original.optJSONArray("outbounds");
        if(outbounds==null||outbounds.length()>10)throw new IllegalArgumentException("Missing transport");
        JSONObject proxy=null;
        for(int i=0;i<outbounds.length();i++) {
            JSONObject outbound=outbounds.optJSONObject(i);
            if(outbound!=null && "vless".equals(outbound.optString("protocol"))) { proxy=new JSONObject(outbound.toString());break; }
        }
        if(proxy==null)throw new IllegalArgumentException("Unsupported transport");
        JSONObject stream=proxy.optJSONObject("streamSettings");
        if(stream==null||!"reality".equals(stream.optString("security")))throw new IllegalArgumentException("An authenticated REALITY transport is required");
        JSONArray servers=proxy.getJSONObject("settings").getJSONArray("vnext");
        if(servers.length()!=1)throw new IllegalArgumentException("Invalid server");
        JSONObject server=servers.getJSONObject(0);int port=server.getInt("port");
        String host=server.getString("address");
        if(host.isBlank()||host.contains("/")||port<1||port>65535)throw new IllegalArgumentException("Invalid server endpoint");
        JSONArray users=server.getJSONArray("users");
        if(users.length()!=1||users.getJSONObject(0).optString("id").isBlank())throw new IllegalArgumentException("Missing credentials");
        // Vision's default deliberately rejects UDP/443. Android browsers and
        // apps may keep using QUIC after advertising it, producing a protocol
        // error instead of falling back to TCP. The client-only suffix permits
        // encrypted UDP/443 while retaining the same server flow and REALITY.
        JSONObject user=users.getJSONObject(0);
        if("xtls-rprx-vision".equals(user.optString("flow")))
            user.put("flow","xtls-rprx-vision-udp443");
        proxy.put("tag","gv-proxy");proxy.remove("proxySettings");proxy.remove("sendThrough");
        JSONObject sock=stream.optJSONObject("sockopt");if(sock!=null){sock.remove("interface");sock.remove("mark");}
        JSONObject tun=new JSONObject().put("tag","gv-tun").put("protocol","tun")
            .put("settings",new JSONObject().put("name","gv-phone").put("mtu",1500));
        // Inspect only protocol headers for routing; never record payloads or
        // override the application's real destination. Unknown traffic stays proxied.
        if(mode==ConnectionMode.SMART)tun.put("sniffing",new JSONObject().put("enabled",true)
            .put("destOverride",new JSONArray().put("http").put("tls").put("quic")).put("routeOnly",true));
        // Match the VPN's IPv4-only compatibility path. Android blocks IPv6
        // at the VPN boundary until node IPv6 egress has been validated.
        // Global DNS stays inside the VPN. Smart mode resolves recognised
        // domestic domains locally over HTTPS, so CDN selection follows the
        // user's domestic connection instead of the selected overseas node.
        JSONObject dns=new JSONObject().put("tag","gv-dns-query")
            .put("queryStrategy","UseIPv4")
            .put("servers",new JSONArray().put("https://1.1.1.1/dns-query").put("https://8.8.8.8/dns-query"));
        if(mode==ConnectionMode.SMART){
            JSONArray remoteDomains=new JSONArray().put("geosite:geolocation-!cn");
            JSONArray domesticDomains=new JSONArray().put("geosite:cnexplicit");
            dns.put("disableFallbackIfMatch",true)
                // Prevent DNS bootstrap from recursively resolving its own
                // HTTPS endpoint. The URL hostname still validates TLS.
                .put("hosts",new JSONObject().put("doh.pub",new JSONArray().put("1.12.12.12").put("120.53.53.53")))
                .put("servers",SmartRoutingDomains.priorityResolvers()
                    .put(new JSONObject().put("address","https://1.1.1.1/dns-query").put("domains",remoteDomains)
                        .put("tag","gv-dns-query").put("skipFallback",true))
                    // GeoSite categories overlap. A remote-domain failure
                    // must never fall through to the domestic resolver.
                    .put(new JSONObject().put("address","https://8.8.8.8/dns-query").put("domains",remoteDomains)
                        .put("tag","gv-dns-query").put("skipFallback",true).put("finalQuery",true))
                    .put(new JSONObject().put("address","https://223.5.5.5/dns-query").put("domains",domesticDomains)
                        .put("tag","gv-dns-cn").put("skipFallback",true))
                    .put(new JSONObject().put("address","https://doh.pub/dns-query").put("domains",domesticDomains)
                        .put("tag","gv-dns-cn").put("skipFallback",true))
                    .put("https://1.1.1.1/dns-query").put("https://8.8.8.8/dns-query"));
        }
        JSONObject dnsOutbound=new JSONObject().put("tag","gv-dns").put("protocol","dns");
        JSONArray rules=new JSONArray()
            .put(new JSONObject().put("type","field").put("inboundTag",new JSONArray().put("gv-tun"))
                .put("network","tcp,udp").put("port","53").put("outboundTag","gv-dns"));
        if(mode==ConnectionMode.SMART)rules.put(new JSONObject().put("type","field")
            .put("inboundTag",new JSONArray().put("gv-dns-cn")).put("network","tcp").put("port","443")
            .put("outboundTag","gv-direct"));
        rules.put(new JSONObject().put("type","field").put("inboundTag",new JSONArray().put("gv-dns-query"))
            .put("outboundTag","gv-proxy"));
        JSONArray safeOutbounds=new JSONArray().put(proxy).put(dnsOutbound);
        if(mode==ConnectionMode.SMART){
            safeOutbounds.put(new JSONObject().put("tag","gv-direct").put("protocol","freedom")
                .put("settings",new JSONObject().put("domainStrategy","UseIPv4")));
            rules.put(new JSONObject().put("type","field").put("domain",SmartRoutingDomains.protectedForeign()).put("outboundTag","gv-proxy"));
            rules.put(new JSONObject().put("type","field").put("domain",SmartRoutingDomains.domesticAssets()).put("outboundTag","gv-direct"));
            rules.put(new JSONObject().put("type","field").put("domain",new JSONArray().put("geosite:geolocation-!cn")).put("outboundTag","gv-proxy"));
            rules.put(new JSONObject().put("type","field").put("domain",new JSONArray().put("geosite:cnexplicit")).put("outboundTag","gv-direct"));
            rules.put(new JSONObject().put("type","field").put("ip",new JSONArray().put("geoip:cn")).put("outboundTag","gv-direct"));
        }
        // Explicit fallback prevents unclassified destinations becoming direct.
        rules.put(new JSONObject().put("type","field").put("network","tcp,udp").put("outboundTag","gv-proxy"));
        return new JSONObject().put("log",new JSONObject().put("loglevel","none"))
            .put("inbounds",new JSONArray().put(tun)).put("outbounds",safeOutbounds)
            .put("dns",dns)
            // AsIs cannot classify an unlisted domain by its Chinese IP;
            // IPIfNonMatch also never runs once the explicit fallback matches.
            .put("routing",new JSONObject().put("domainStrategy",mode==ConnectionMode.SMART?"IPOnDemand":"AsIs").put("rules",rules))
            .put("stats",new JSONObject())
            .put("policy",new JSONObject().put("system",new JSONObject().put("statsOutboundUplink",true).put("statsOutboundDownlink",true)))
            .toString();
    }
}
