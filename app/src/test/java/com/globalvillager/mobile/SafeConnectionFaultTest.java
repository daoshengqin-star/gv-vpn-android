package com.globalvillager.mobile;
import org.junit.Test;import static org.junit.Assert.*;
public class SafeConnectionFaultTest {
 @Test public void knownStageAndError(){assertEquals("stage=verifying error=TimeoutException reasons=",SafeConnectionFault.summarize("verifying","TimeoutException",null));}
 @Test public void credentialsAndRawMessageNeverIncluded(){String raw="Bearer abc_PRIVATE_TOKEN password=secret user@example.com vless://11111111-2222-3333-4444-555555555555@private-host";String v=SafeConnectionFault.summarize("verifying","error",raw);assertFalse(v.contains("secret"));assertFalse(v.contains("Bearer"));assertFalse(v.contains("@"));assertFalse(v.contains("11111111"));}
 @Test public void fixedReasonsOnly(){assertEquals("stage=starting error=error reasons=timeout,deadline,tls",SafeConnectionFault.summarize("starting","error","TLS deadline? no, timeout token=hidden"));}
 @Test public void invalidStageRedacted(){assertTrue(SafeConnectionFault.summarize("user-private-stage","Error","").startsWith("stage=unknown "));}
 @Test public void invalidClassRedacted(){assertTrue(SafeConnectionFault.summarize("checking","token=private","").contains("error=Unknown "));}
 @Test public void nullInputsSafe(){assertEquals("stage=unknown error=Unknown reasons=",SafeConnectionFault.summarize(null,null,null));}
}
