package com.globalvillager.mobile;

import org.json.JSONArray;
import org.json.JSONObject;

/** Preserve HTTP status even when a reverse proxy returns an HTML error page. */
final class ApiResponse {
    private ApiResponse() {}
    static Object parse(int status,String text)throws Exception {
        if(status<200||status>=300){
            String detail="";
            try{detail=new JSONObject(text).optString("detail","");}catch(Exception ignored){}
            throw new GvApi.ApiException(status,detail);
        }
        String body=text.trim();
        return body.startsWith("[")?new JSONArray(body):new JSONObject(body.isBlank()?"{}":body);
    }
}
