package com.globalvillager.mobile;

import org.junit.Test;
import org.json.JSONObject;
import org.json.JSONArray;
import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.zip.DeflaterOutputStream;
import java.nio.charset.StandardCharsets;

public class ConfigCodecTest {
    @Test public void globalModeNeverContainsADirectOutbound()throws Exception {
        JSONObject global=new JSONObject(ConfigCodec.forAndroid(profile(),ConnectionMode.GLOBAL));
        assertFalse(global.toString().contains("freedom"));assertFalse(global.getJSONArray("inbounds").getJSONObject(0).has("sniffing"));
    }
    @Test public void smartModeKeepsDnsAndUnknownDestinationsEncrypted()throws Exception {
        JSONObject smart=new JSONObject(ConfigCodec.forAndroid(profile(),ConnectionMode.SMART));
        JSONArray rules=smart.getJSONObject("routing").getJSONArray("rules");
        assertEquals("gv-proxy",rules.getJSONObject(2).getString("outboundTag"));
        assertEquals("gv-proxy",rules.getJSONObject(rules.length()-1).getString("outboundTag"));
        assertEquals("none",smart.getJSONObject("log").getString("loglevel"));
        JSONObject sniff=smart.getJSONArray("inbounds").getJSONObject(0).getJSONObject("sniffing");
        assertTrue(sniff.getBoolean("routeOnly"));
    }
    @Test public void domesticDnsUsesHttpsWithoutMakingUnknownQueriesDirect()throws Exception {
        JSONObject smart=new JSONObject(ConfigCodec.forAndroid(profile(),ConnectionMode.SMART));
        JSONObject dns=smart.getJSONObject("dns");JSONArray servers=dns.getJSONArray("servers");
        assertTrue(dns.getBoolean("disableFallbackIfMatch"));
        assertEquals("IPOnDemand",smart.getJSONObject("routing").getString("domainStrategy"));
        int domestic=0;
        for(int i=0;i<servers.length();i++){
            Object server=servers.get(i);
            if(server instanceof String){assertTrue(((String)server).startsWith("https://"));continue;}
            JSONObject resolver=(JSONObject)server;
            assertTrue(resolver.getString("address").startsWith("https://"));
            assertFalse(resolver.getString("address").contains("+local"));
            if("gv-dns-cn".equals(resolver.getString("tag"))){
                domestic++;assertTrue(resolver.getBoolean("skipFallback"));
                assertFalse(resolver.getJSONArray("domains").toString().contains("domain:cn\""));
            }
        }
        assertEquals(4,domestic);
        JSONObject local=smart.getJSONObject("routing").getJSONArray("rules").getJSONObject(1);
        assertEquals("gv-dns-cn",local.getJSONArray("inboundTag").getString(0));
        assertEquals("443",local.getString("port"));assertEquals("tcp",local.getString("network"));
        assertEquals("gv-direct",local.getString("outboundTag"));
    }
    @Test public void overlappingForeignCategoriesCannotFallBackToDomesticDns()throws Exception {
        JSONArray resolvers=new JSONObject(ConfigCodec.forAndroid(profile(),ConnectionMode.SMART)).getJSONObject("dns").getJSONArray("servers");
        for(int i=0;i<2;i++){
            JSONObject resolver=resolvers.getJSONObject(i);
            assertTrue(resolver.getJSONArray("domains").toString().contains("domain:google.com"));
            assertEquals("gv-dns-query",resolver.getString("tag"));
        }
        assertTrue(resolvers.getJSONObject(1).getBoolean("finalQuery"));
        assertEquals("gv-dns-cn",resolvers.getJSONObject(2).getString("tag"));
    }
    private String profile() throws Exception {
        JSONObject vless=new JSONObject().put("tag","proxy").put("protocol","vless")
            .put("settings",new JSONObject().put("vnext",new JSONArray().put(new JSONObject().put("address","192.0.2.1").put("port",443)
                .put("users",new JSONArray().put(new JSONObject().put("id","00000000-0000-4000-8000-000000000001"))))))
            .put("streamSettings",new JSONObject().put("network","tcp").put("security","reality")
                .put("realitySettings",new JSONObject().put("serverName","example.org").put("publicKey","TEST_ONLY")));
        return new JSONObject().put("outbounds",new JSONArray().put(vless)).toString();
    }
    private String compressed(String json) throws Exception {
        byte[] bytes=json.getBytes(StandardCharsets.UTF_8);ByteArrayOutputStream out=new ByteArrayOutputStream();
        out.write(new byte[]{(byte)(bytes.length>>24),(byte)(bytes.length>>16),(byte)(bytes.length>>8),(byte)bytes.length});
        try(DeflaterOutputStream z=new DeflaterOutputStream(out)){z.write(bytes);}
        return "vpn://"+Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray());
    }
    @Test public void decodesBackendQtCompressedProfile() throws Exception {
        String nested=new JSONObject().put("containers",new JSONArray().put(new JSONObject()
            .put("container","amnezia-xray").put("xray_config_data",new JSONObject().put("config",profile())))).toString();
        assertEquals("vless",ConfigCodec.decode(compressed(nested)).getJSONArray("outbounds").getJSONObject(0).getString("protocol"));
    }
    @Test public void removesServerListenersAndUnsafeTopLevelSettings() throws Exception {
        JSONObject p=new JSONObject(profile()).put("api",new JSONObject().put("tag","remote-admin"))
            .put("inbounds",new JSONArray().put(new JSONObject().put("listen","0.0.0.0").put("protocol","http").put("port",9999)));
        JSONObject safe=new JSONObject(ConfigCodec.forAndroid(p.toString()));
        assertFalse(safe.has("api"));assertEquals(1,safe.getJSONArray("inbounds").length());
        assertEquals("tun",safe.getJSONArray("inbounds").getJSONObject(0).getString("protocol"));
        assertEquals("gv-proxy",safe.getJSONArray("outbounds").getJSONObject(0).getString("tag"));
    }
    @Test public void rejectsUnauthenticatedTransport() throws Exception {
        JSONObject p=new JSONObject(profile());p.getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings").put("security","none");
        assertThrows(IllegalArgumentException.class,()->ConfigCodec.forAndroid(p.toString()));
    }
    @Test public void rejectsUnsupportedProtocolInsteadOfPretendingToConnect() throws Exception {
        assertThrows(IllegalArgumentException.class,()->ConfigCodec.forAndroid("{\"outbounds\":[{\"protocol\":\"freedom\"}]}"));
    }
    @Test public void rejectsCorruptQtLengthHeader() throws Exception {
        String good=compressed(profile());byte[] b=Base64.getUrlDecoder().decode(good.substring(6));b[3]^=1;
        assertThrows(IllegalArgumentException.class,()->ConfigCodec.decode("vpn://"+Base64.getUrlEncoder().encodeToString(b)));
    }
    @Test public void rejectsOversizeProfileBeforeParsing() throws Exception {
        assertThrows(IllegalArgumentException.class,()->ConfigCodec.decode("x".repeat(2*1024*1024+1)));
    }
    @Test public void rejectsInvalidServerPort() throws Exception {
        JSONObject p=new JSONObject(profile());p.getJSONArray("outbounds").getJSONObject(0).getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).put("port",65536);
        assertThrows(IllegalArgumentException.class,()->ConfigCodec.forAndroid(p.toString()));
    }
    @Test public void dnsAnswersUseValidatedIpv4AndResolversStayInsideProxy() throws Exception {
        JSONObject safe=new JSONObject(ConfigCodec.forAndroid(profile()));
        JSONObject dns=safe.getJSONObject("dns");
        assertEquals("UseIPv4",dns.getString("queryStrategy"));
        JSONArray servers=dns.getJSONArray("servers");
        assertEquals(2,servers.length());
        for(int i=0;i<servers.length();i++) {
            assertTrue(servers.getString(i).startsWith("https://"));
            assertFalse(servers.getString(i).contains("local"));
        }
        JSONArray rules=safe.getJSONObject("routing").getJSONArray("rules");
        JSONObject redirect=rules.getJSONObject(0);
        assertEquals("gv-tun",redirect.getJSONArray("inboundTag").getString(0));
        assertEquals("tcp,udp",redirect.getString("network"));
        assertEquals("53",redirect.getString("port"));
        assertEquals("gv-dns",redirect.getString("outboundTag"));
        assertEquals(dns.getString("tag"),rules.getJSONObject(1).getJSONArray("inboundTag").getString(0));
        assertEquals("gv-proxy",rules.getJSONObject(1).getString("outboundTag"));
    }
    @Test public void compatibilityDnsDoesNotIntroduceDirectTrafficOrRemoteListeners() throws Exception {
        JSONObject safe=new JSONObject(ConfigCodec.forAndroid(profile()));
        JSONArray out=safe.getJSONArray("outbounds");
        assertEquals("vless",out.getJSONObject(0).getString("protocol"));
        assertEquals("dns",out.getJSONObject(1).getString("protocol"));
        assertEquals(2,out.length());
        assertFalse(safe.toString().contains("freedom"));
        assertFalse(safe.getJSONArray("inbounds").getJSONObject(0).has("listen"));
    }
    @Test public void visionPermitsBrowserQuicWithoutChangingAuthentication() throws Exception {
        JSONObject original=new JSONObject(profile());
        JSONObject proxy=original.getJSONArray("outbounds").getJSONObject(0);
        JSONObject user=proxy.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getJSONArray("users").getJSONObject(0);
        user.put("flow","xtls-rprx-vision");user.put("encryption","none");
        JSONObject safe=new JSONObject(ConfigCodec.forAndroid(original.toString())).getJSONArray("outbounds").getJSONObject(0);
        JSONObject adapted=safe.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getJSONArray("users").getJSONObject(0);
        assertEquals("xtls-rprx-vision-udp443",adapted.getString("flow"));
        assertEquals(user.getString("id"),adapted.getString("id"));
        assertEquals("none",adapted.getString("encryption"));
        assertEquals(proxy.getJSONObject("streamSettings").toString(),safe.getJSONObject("streamSettings").toString());
        assertEquals("xtls-rprx-vision",user.getString("flow"));
    }
    @Test public void otherFlowsAreNotSilentlyReplaced() throws Exception {
        for(String flow:new String[]{"","xtls-rprx-vision-udp443","unsupported-future-flow"}){
            JSONObject original=new JSONObject(profile());
            original.getJSONArray("outbounds").getJSONObject(0).getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
                .getJSONArray("users").getJSONObject(0).put("flow",flow);
            JSONObject safe=new JSONObject(ConfigCodec.forAndroid(original.toString()));
            assertEquals(flow,safe.getJSONArray("outbounds").getJSONObject(0).getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
                .getJSONArray("users").getJSONObject(0).getString("flow"));
        }
    }
}
