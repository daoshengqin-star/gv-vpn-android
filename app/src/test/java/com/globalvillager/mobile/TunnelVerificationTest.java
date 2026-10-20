package com.globalvillager.mobile;

import org.junit.Test;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLHandshakeException;
import static org.junit.Assert.*;

public class TunnelVerificationTest {
    @Test public void aProviderOutageDoesNotRejectAWorkingTunnel() throws Exception {
        List<String> attempts=new ArrayList<>();
        assertEquals(640,TunnelVerification.verify(url->{
            attempts.add(url);
            if(attempts.size()==1)throw new SocketTimeoutException();
            return 640;
        }));
        assertEquals(2,attempts.size());
        assertNotEquals(attempts.get(0),attempts.get(1));
        for(String url:attempts)assertTrue(url.startsWith("https://"));
    }
    @Test public void healthyPrimaryDoesNotCreateAnExtraNetworkRequest() throws Exception {
        List<String> attempts=new ArrayList<>();
        assertEquals(0,TunnelVerification.verify(url->{attempts.add(url);return 0;}));
        assertEquals(1,attempts.size());
    }
    @Test public void failedTlsAtBothProvidersDoesNotReportConnected() {
        try {
            TunnelVerification.verify(url->{throw new SSLHandshakeException("invalid certificate");});
            fail("Both TLS checks failed");
        } catch(Exception expected) {assertTrue(expected instanceof SSLHandshakeException);}
    }
    @Test public void aNegativeNativeResultDoesNotReportConnected() {
        try {TunnelVerification.verify(url->-1);fail("No successful response");}
        catch(Exception expected) {assertTrue(expected instanceof IllegalStateException);}
    }
    @Test public void explicitCancellationNeverStartsAFallback() {
        List<String> attempts=new ArrayList<>();
        try {
            TunnelVerification.verify(url->{attempts.add(url);throw new InterruptedException();});
            fail("Cancelled check must stop");
        } catch(Exception expected) {
            assertTrue(expected instanceof InterruptedException);
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1,attempts.size());
        } finally {Thread.interrupted();}
    }
    @Test public void nativeFailureAfterCancellationNeverStartsAFallback() {
        List<String> attempts=new ArrayList<>();
        try {
            TunnelVerification.verify(url->{attempts.add(url);Thread.currentThread().interrupt();throw new SocketTimeoutException();});
            fail("Cancelled check must stop");
        } catch(Exception expected) {
            assertTrue(expected instanceof InterruptedException);
            assertEquals(1,attempts.size());
        } finally {Thread.interrupted();}
    }
}
