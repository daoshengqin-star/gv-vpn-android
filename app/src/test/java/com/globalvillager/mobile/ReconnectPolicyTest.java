package com.globalvillager.mobile;
import org.junit.Test;
import static org.junit.Assert.*;
public class ReconnectPolicyTest {
    @Test public void retriesAreBoundedAndStableSuccessRestoresBudget(){
        ReconnectPolicy policy=new ReconnectPolicy();
        assertEquals(2000,policy.nextDelayMs(true,true,true));assertEquals(5000,policy.nextDelayMs(true,true,true));
        assertEquals(12000,policy.nextDelayMs(true,true,true));assertEquals(-1,policy.nextDelayMs(true,true,true));
        policy.reset();assertEquals(2000,policy.nextDelayMs(true,true,true));
    }
    @Test public void firstConnectionAndReleasedInterfaceNeverAutoReconnect(){
        ReconnectPolicy policy=new ReconnectPolicy();
        assertEquals(-1,policy.nextDelayMs(false,true,true));assertEquals(-1,policy.nextDelayMs(true,false,true));
        assertEquals(-1,policy.nextDelayMs(true,true,false));assertEquals(2000,policy.nextDelayMs(true,true,true));
    }
    @Test public void deniedExpiredOrInvalidAccessNeverRetries(){
        for(int code:new int[]{400,401,403,404,409})assertFalse(ReconnectPolicy.transientError(new GvApi.ApiException(code,"")));
        for(int code:new int[]{408,429,500,503})assertTrue(ReconnectPolicy.transientError(new GvApi.ApiException(code,"")));
        assertFalse(ReconnectPolicy.transientError(new IllegalArgumentException("bad config")));
        assertTrue(ReconnectPolicy.transientError(new java.io.IOException("network changed")));
    }
}
