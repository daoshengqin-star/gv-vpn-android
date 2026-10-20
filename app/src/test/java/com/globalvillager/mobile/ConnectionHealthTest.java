package com.globalvillager.mobile;

import org.junit.Test;
import java.net.SocketTimeoutException;
import static org.junit.Assert.*;

public class ConnectionHealthTest {
    @Test public void oneTimeoutRetainsTunnelAndARecoveryResetsTheWindow(){
        ConnectionHealth health=new ConnectionHealth();
        assertTrue(health.retry(new SocketTimeoutException()));
        assertTrue(health.rechecking());assertEquals(10000,health.intervalMs());
        health.success();assertFalse(health.rechecking());assertEquals(30000,health.intervalMs());
        assertTrue(health.retry(new SocketTimeoutException()));
    }
    @Test public void persistentFailureStopsAfterThreeChecks(){
        ConnectionHealth health=new ConnectionHealth();
        assertTrue(health.retry(new SocketTimeoutException()));
        assertTrue(health.retry(new SocketTimeoutException()));
        assertFalse(health.retry(new SocketTimeoutException()));
    }
    @Test public void RevokedLoginDeviceOrInvalidRequestIsNotRetried(){
        for(int code:new int[]{400,401,403,404,409})
            assertFalse(new ConnectionHealth().retry(new GvApi.ApiException(code,"")));
    }
    @Test public void serverOverloadAndRateLimitHaveTheSameBoundedWindow(){
        for(int code:new int[]{408,429,500,503}) {
            ConnectionHealth health=new ConnectionHealth();
            assertTrue(health.retry(new GvApi.ApiException(code,"")));
            assertTrue(health.retry(new GvApi.ApiException(code,"")));
            assertFalse(health.retry(new GvApi.ApiException(code,"")));
        }
    }
}
