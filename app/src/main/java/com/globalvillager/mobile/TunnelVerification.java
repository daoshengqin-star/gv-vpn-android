package com.globalvillager.mobile;

/** Verify real HTTPS through the tunnel without depending on one provider. */
final class TunnelVerification {
    interface Check { long verify(String url) throws Exception; }
    private static final String[] ENDPOINTS={
        "https://www.gstatic.com/generate_204",
        "https://www.cloudflare.com/cdn-cgi/trace"
    };
    private TunnelVerification() { }

    static long verify(Check check) throws Exception {
        Exception last=null;
        // Native checks are serial: starting another check cancels the former.
        // Each retains the native 4.5-second deadline and normal TLS validation.
        for(String endpoint:ENDPOINTS) {
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            try {
                long delay=check.verify(endpoint);
                if(delay<0)throw new IllegalStateException("HTTPS verification failed");
                return delay;
            } catch(InterruptedException interrupted) {
                Thread.currentThread().interrupt();throw interrupted;
            } catch(Exception failed) {
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                last=failed;
            }
        }
        throw last;
    }
}
