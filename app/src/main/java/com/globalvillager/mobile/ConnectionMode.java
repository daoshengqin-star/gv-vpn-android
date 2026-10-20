package com.globalvillager.mobile;

/** Unknown/stale preferences always use the stricter global policy. */
public enum ConnectionMode {
    GLOBAL("global"), SMART("smart");
    public final String value;
    ConnectionMode(String value){this.value=value;}
    public static ConnectionMode from(String value){return "smart".equals(value)?SMART:GLOBAL;}
}
