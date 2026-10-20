package com.globalvillager.mobile;
import org.json.JSONObject;
/** A claim response never substitutes for authoritative available access. */
final class TrialReview {
    static int message(JSONObject claim,GvApi.Entitlement access){
        if(claim==null)return 0;
        String state=claim.optString("state");
        if("granted".equals(state)||"already_received".equals(state)){
            if(access.usable()&&"trial_2h".equals(access.plan))return R.string.trial_ready;
            return access.usable()?0:R.string.trial_unavailable;
        }
        if("device_already_received".equals(state))return R.string.trial_device_used;
        if("registration_window_ended".equals(state))return R.string.trial_window_ended;
        if("existing_access".equals(state))return 0;
        return R.string.trial_unavailable;
    }
}
