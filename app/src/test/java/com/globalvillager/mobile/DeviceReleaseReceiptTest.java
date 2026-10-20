package com.globalvillager.mobile;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public class DeviceReleaseReceiptTest {
    JSONObject valid()throws Exception{return new JSONObject().put("device_id","device-a").put("request_id","request-a").put("status","completed").put("seat_released",true).put("node_revocation","completed");}
    @Test public void exactCompletedReceiptFreesSeat()throws Exception{assertTrue(DeviceReleaseReceipt.completed(valid(),"device-a","request-a"));}
    @Test public void wrongDeviceNeverFreesSeat()throws Exception{assertFalse(DeviceReleaseReceipt.completed(valid(),"device-b","request-a"));}
    @Test public void wrongRequestNeverFreesSeat()throws Exception{assertFalse(DeviceReleaseReceipt.completed(valid(),"device-a","request-b"));}
    @Test public void pendingCleanupNeverFreesSeat()throws Exception{assertFalse(DeviceReleaseReceipt.completed(valid().put("node_revocation","deferred"),"device-a","request-a"));}
    @Test public void partialReceiptNeverFreesSeat()throws Exception{assertFalse(DeviceReleaseReceipt.completed(new JSONObject().put("status","completed"),"device-a","request-a"));}
    @Test public void requestPersistenceIsScopedToAccountAndDevice()throws Exception{assertNotEquals(DeviceReleaseReceipt.key("a@example.com","phone"),DeviceReleaseReceipt.key("b@example.com","phone"));assertNotEquals(DeviceReleaseReceipt.key("a@example.com","phone"),DeviceReleaseReceipt.key("a@example.com","desktop"));assertEquals(DeviceReleaseReceipt.key(" A@example.com ","phone"),DeviceReleaseReceipt.key("a@example.com","phone"));}
}
