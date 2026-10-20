package com.globalvillager.mobile;

/** Bounded recovery of an established, still protected VPN, never an access bypass. */
final class ReconnectPolicy {
    private int failures;
    synchronized void reset(){failures=0;}
    synchronized long nextDelayMs(boolean established,boolean protectedInterface,boolean retryable){
        if(!established||!protectedInterface||!retryable||failures>=3)return -1;
        return new long[]{2000,5000,12000}[failures++];
    }
    static boolean transientError(Exception error){
        if(error instanceof GvApi.ApiException){int code=((GvApi.ApiException)error).status;return code==408||code==429||code>=500;}
        return error instanceof java.io.IOException||error instanceof java.util.concurrent.TimeoutException
            ||error instanceof IllegalStateException;
    }
}
