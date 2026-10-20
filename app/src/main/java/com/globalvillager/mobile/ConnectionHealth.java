package com.globalvillager.mobile;

/** Short, bounded retry window for transient checks on an already live tunnel. */
final class ConnectionHealth {
    private volatile int failures;
    void success(){failures=0;}
    boolean retry(Exception error){
        if(error instanceof GvApi.ApiException){
            int code=((GvApi.ApiException)error).status;
            if(code>=400&&code<500&&code!=408&&code!=429)return false;
        }
        return ++failures<3;
    }
    boolean rechecking(){return failures>0;}
    long intervalMs(){return rechecking()?10000:30000;}
}
