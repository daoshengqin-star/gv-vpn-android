package com.globalvillager.mobile;
import org.junit.Test;
import static org.junit.Assert.*;

public class RegistrationInputTest {
    @Test public void permitsAccountNamesWithoutRequiringAnEmail(){assertTrue(RegistrationInput.accountName("traveller_123"));assertTrue(RegistrationInput.accountName("alex.test"));}
    @Test public void rejectsAmbiguousOrOutOfBoundsNames(){for(String name:new String[]{"ab"," xiao","a@b.com",".abc","a".repeat(33),"用户名"})assertFalse(RegistrationInput.accountName(name));}
    @Test public void passwordBoundsMatchTheServer(){assertFalse(RegistrationInput.password("123456789"));assertTrue(RegistrationInput.password("1234567890"));assertTrue(RegistrationInput.password("x".repeat(256)));assertFalse(RegistrationInput.password("x".repeat(257)));}
    @Test public void staleModeCannotEnableDirectTraffic(){assertEquals(ConnectionMode.GLOBAL,ConnectionMode.from(null));assertEquals(ConnectionMode.GLOBAL,ConnectionMode.from("direct"));assertEquals(ConnectionMode.SMART,ConnectionMode.from("smart"));}
}
