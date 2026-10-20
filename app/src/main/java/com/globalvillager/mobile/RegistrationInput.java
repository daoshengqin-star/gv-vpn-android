package com.globalvillager.mobile;

/** Local validation never trims or rewrites passwords. Server remains authoritative. */
public final class RegistrationInput {
    private RegistrationInput(){}
    public static boolean accountName(String value){return value!=null&&value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{2,31}");}
    public static boolean password(String value){return value!=null&&value.length()>=10&&value.length()<=256;}
}
