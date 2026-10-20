package com.globalvillager.mobile;
import org.json.JSONObject;

/** Account transport boundary; production uses GvApi's bounded HTTPS client. */
interface ApiTransport {
    GvApi.Reply call(String method,String path,JSONObject body,String token) throws Exception;
    void cancel();
}
