package com.globalvillager.mobile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.net.Uri;
import android.net.VpnService;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.*;
import java.util.concurrent.*;

/** Native phone UI: portrait-friendly, scrollable at large text sizes, no work on the UI thread. */
public final class MainActivity extends Activity {
    private static final int VPN_DISCLOSURE_VERSION=2;
    private final int INK=Color.rgb(19,54,77),TEAL=Color.rgb(12,157,139),PALE=Color.rgb(243,249,251);
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private SecureSession session;
    private ApiTransport api;
    private boolean busy,destroyed,registered,signup,home,accountSignup;
    private ConnectionMode selectedMode=ConnectionMode.GLOBAL;
    private String selectedCountry="",selectedNode="",token="",challenge="",challengeEmail="";
    private long resendAt;
    private String renderedLanguage="";
    private volatile int authEpoch;
    private volatile int trialCheckedEpoch=-1,trialAttempts;
    private GvApi.Entitlement plan;
    private LinearLayout page,shellRoot,homeContent,planContent,settingsContent,planOptions;
    private TextView planSummary,planMessage;
    private String selectedUiPage="home";
    private Button navHome,navPlans,navSettings;
    private PlanCatalog.Result planCatalog;
    private boolean catalogBusy;
    private long catalogFetchedAt;
    private TextView feedback,expiry,phase,node,duration,traffic,modeHelp;
    private Button submit,connect,sendCode,routeButton,releaseBlock,modeButton,billingButton;
    private int billingAttempts;
    private final Runnable billingPoll=this::refreshBillingReturn;
    private EditText identifier,password,code;
    private CheckBox accept;
    private AlertDialog passwordDialog;
    private AlertDialog routeDialog;
    private AlertDialog devicesDialog,deviceProgress;
    private boolean routeRequested,accountRefreshing,deviceBusy;
    private int routeFailure;
    private static final class LoginDraft {
        final String id,pass,otp,challenge,email;final boolean signup,accepted,accountSignup;final long resendAt;
        LoginDraft(MainActivity activity){
            id=activity.identifier.getText().toString();pass=activity.password.getText().toString();signup=activity.signup;
            otp=signup&&activity.code!=null?activity.code.getText().toString():"";
            accepted=signup&&activity.accept!=null&&activity.accept.isChecked();
            challenge=activity.challenge;email=activity.challengeEmail;resendAt=activity.resendAt;
            accountSignup=activity.accountSignup;
        }
    }
    private final List<RouteCandidates.Route> routes=new ArrayList<>();
    private final BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){updateConnection();}};
    private final Runnable clock=new Runnable(){public void run(){refreshLanguageUi();if(home){updateConnection();updatePlan();}else updateCodeButton();main.postDelayed(this,1000);}};

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);api=new GvApi(this);session=new SecureSession(this);
        selectedCountry=getSharedPreferences("gv-ui",MODE_PRIVATE).getString("country","");
        selectedNode=getSharedPreferences("gv-ui",MODE_PRIVATE).getString("node","");
        selectedMode=ConnectionMode.from(getSharedPreferences("gv-ui",MODE_PRIVATE).getString("mode","global"));
        try{token=session.token();}catch(Exception ignored){session.clear();}
        if(token.isBlank()){
            Object retained=getLastNonConfigurationInstance();
            if(retained instanceof LoginDraft draft){accountSignup=draft.accountSignup;showLogin(draft.signup);restoreDraft(draft);}
            else showLogin(false);
        }else {showHome();refreshAccount();}
    }
    // API 28-32 do not expose RECEIVER_NOT_EXPORTED; the legacy receiver only
    // refreshes our in-process snapshot and never accepts broadcast payloads.
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override public void onResume(){super.onResume();if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,new IntentFilter(GvVpnService.UPDATE),Context.RECEIVER_NOT_EXPORTED);else registerReceiver(receiver,new IntentFilter(GvVpnService.UPDATE));registered=true;billingAttempts=0;main.post(clock);updateConnection();refreshBillingReturn();}
    @Override public void onPause(){PasswordInput.hideAll(page);if(passwordDialog!=null&&passwordDialog.getWindow()!=null)PasswordInput.hideAll(passwordDialog.getWindow().getDecorView());super.onPause();main.removeCallbacks(clock);main.removeCallbacks(billingPoll);if(registered){unregisterReceiver(receiver);registered=false;}}
    @Override public void onDestroy(){destroyed=true;if(passwordDialog!=null)passwordDialog.dismiss();dismissRouteDialog();if(devicesDialog!=null)devicesDialog.dismiss();if(deviceProgress!=null)deviceProgress.dismiss();api.cancel();io.shutdownNow();main.removeCallbacksAndMessages(null);super.onDestroy();}
    @Override public Object onRetainNonConfigurationInstance(){return !home&&identifier!=null?new LoginDraft(this):null;}
    @Override public void onConfigurationChanged(Configuration config){super.onConfigurationChanged(config);refreshLanguageUi();}
    private String tr(int id){return AppLanguage.text(this,id);}
    private void chooseLanguage(){
        if(busy){if(feedback!=null)feedback.setText(tr(R.string.language_wait));return;}
        String[] labels={tr(R.string.language_system),"简体中文","繁體中文","English"};
        int selected=java.util.Arrays.asList(AppLanguage.CHOICES).indexOf(AppLanguage.selection(this));
        new AlertDialog.Builder(this).setTitle(tr(R.string.language)).setSingleChoiceItems(labels,selected,(dialog,index)->{
            dialog.dismiss();AppLanguage.select(this,AppLanguage.CHOICES[index]);refreshLanguageUi();
        }).setNegativeButton(tr(R.string.cancel),null).show();
    }
    private void refreshLanguageUi(){
        if(destroyed||busy||page==null||AppLanguage.renderKey(this).equals(renderedLanguage))return;
        if(home){showHome();return;}
        // Keep credentials and email verification only in memory while relabelling the screen.
        LoginDraft draft=new LoginDraft(this);showLogin(draft.signup);restoreDraft(draft);
    }
    private void restoreDraft(LoginDraft draft){
        identifier.setText(draft.id);password.setText(draft.pass);resendAt=draft.resendAt;
        if(draft.signup){code.setText(draft.otp);accept.setChecked(draft.accepted);challenge=draft.challenge;challengeEmail=draft.email;}updateCodeButton();
    }
    private int dp(float value){return (int)(value*getResources().getDisplayMetrics().density+0.5f);}
    private TextView text(String value,int size,boolean bold) {TextView t=new TextView(this);t.setText(value);t.setTextColor(INK);t.setTextSize(size);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);t.setPadding(0,dp(4),0,dp(4));return t;}
    private GradientDrawable shape(int color,int radius) {GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private LinearLayout card(int color) {LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(20),dp(16),dp(20),dp(16));l.setBackground(shape(color,24));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(10),0,dp(10));page.addView(l,p);return l;}
    private Button button(String label,boolean primary,Runnable action) {Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(16);b.setTextColor(primary?Color.WHITE:INK);b.setMinHeight(dp(52));b.setFilterTouchesWhenObscured(true);b.setBackground(new RippleDrawable(ColorStateList.valueOf(primary?0x33ffffff:0x22069b8b),shape(primary?TEAL:Color.WHITE,18),shape(Color.WHITE,18)));b.setStateListAnimator(null);b.setElevation(0);b.setPadding(dp(12),dp(10),dp(12),dp(10));b.setOnClickListener(v->action.run());LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(7),0,dp(7));b.setLayoutParams(p);return b;}
    private ScrollView primaryScroll;
    private void shell() {
        renderedLanguage=AppLanguage.renderKey(this);
        ScrollView scroll=new ScrollView(this);primaryScroll=scroll;scroll.setFillViewport(true);scroll.setBackgroundColor(PALE);
        page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(24),dp(12),dp(24),dp(28));
        scroll.addView(page,new ScrollView.LayoutParams(-1,-2));
        shellRoot=new LinearLayout(this);shellRoot.setOrientation(LinearLayout.VERTICAL);shellRoot.setBackgroundColor(PALE);
        shellRoot.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        shellRoot.setOnApplyWindowInsetsListener((v,i)-> {v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom());return i;});
        setContentView(shellRoot);shellRoot.requestApplyInsets();
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.gv_brand_orb);logo.setContentDescription("Global Villager");header.addView(logo,new LinearLayout.LayoutParams(dp(52),dp(52)));
        LinearLayout title=new LinearLayout(this);title.setOrientation(LinearLayout.VERTICAL);title.setPadding(dp(12),0,0,0);
        title.addView(text("GLOBAL VILLAGER",20,true));title.addView(text(tr(R.string.phone_subtitle),12,false));header.addView(title,new LinearLayout.LayoutParams(0,-2,1));page.addView(header);
        if(!home)page.addView(button(AppLanguage.text(this,R.string.language_button,AppLanguage.name(this)),false,this::chooseLanguage));
    }
    private EditText input(LinearLayout parent,String hint,boolean secret) {
        if(secret){
            PasswordInput field=new PasswordInput(this,hint,INK,PALE);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(54));p.setMargins(0,dp(7),0,dp(7));
            parent.addView(field,p);return field.editText();
        }
        EditText e=new EditText(this);e.setHint(hint);e.setTextSize(16);e.setSingleLine(true);e.setFilterTouchesWhenObscured(true);e.setPadding(dp(14),dp(10),dp(14),dp(10));
        // Credentials/OTP remain only in the in-memory draft during rotation.
        // Do not include their text in Android's saved view hierarchy.
        e.setSaveEnabled(false);
        e.setBackground(shape(PALE,12));e.setInputType(secret?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        if(secret){e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);e.setAutofillHints(View.AUTOFILL_HINT_PASSWORD);}else e.setAutofillHints(View.AUTOFILL_HINT_USERNAME);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(54));p.setMargins(0,dp(7),0,dp(7));parent.addView(e,p);return e;
    }
    private void showLogin(boolean register) {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        home=false;selectedUiPage="home";planCatalog=null;catalogFetchedAt=0;catalogBusy=false;signup=register;challenge="";shell();LinearLayout box=card(Color.WHITE);
        box.addView(text(register?tr(R.string.signup_title):tr(R.string.welcome_back),26,true));box.addView(text(tr(R.string.shared_account),14,false));
        if(register){
            box.addView(button(accountSignup?tr(R.string.signup_switch_email):tr(R.string.signup_switch_account),false,()->{
                if(busy)return;String priorPassword=password.getText().toString();accountSignup=!accountSignup;showLogin(true);password.setText(priorPassword);
            }));
            box.addView(text(accountSignup?tr(R.string.account_signup_help):tr(R.string.email_signup_help),13,false));
        }
        identifier=input(box,register?(accountSignup?tr(R.string.account_name_hint):tr(R.string.email_hint)):tr(R.string.identifier_hint),false);identifier.setText(register?"":session.email());
        if(register&&accountSignup)identifier.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        password=input(box,register?tr(R.string.new_password_hint):tr(R.string.password_hint),true);
        if(register) {
            code=input(box,tr(R.string.email_code_hint),false);code.setInputType(InputType.TYPE_CLASS_NUMBER);code.setAutofillHints("emailOTPCode");code.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(6)});
            sendCode=button(tr(R.string.send_email_code),false,this::emailCode);box.addView(sendCode);
            if(accountSignup){code.setVisibility(View.GONE);sendCode.setVisibility(View.GONE);}
            accept=new CheckBox(this);accept.setFilterTouchesWhenObscured(true);accept.setText(tr(R.string.accept_terms));accept.setTextColor(INK);box.addView(accept);
            box.addView(button(tr(R.string.view_terms),false,()->new AlertDialog.Builder(this).setTitle(tr(R.string.read_terms))
                .setItems(new String[]{tr(R.string.terms),tr(R.string.privacy)},(d,i)->legal(i==0?"terms":"privacy")).show()));
        }
        feedback=text("",14,false);feedback.setTextColor(Color.rgb(174,58,55));box.addView(feedback);
        submit=button(register?(accountSignup?tr(R.string.register_account):tr(R.string.register)):tr(R.string.login),true,this::login);box.addView(submit);
        page.addView(button(register?tr(R.string.existing_account):tr(R.string.new_account),false,()->{if(!busy)showLogin(!signup);}));
        if(!register)page.addView(button(tr(R.string.forgot_password),false,()->new AlertDialog.Builder(this)
            .setTitle(tr(R.string.forgot_password)).setMessage(tr(R.string.recovery_help))
            .setPositiveButton(tr(R.string.help),(d,w)->support())
            .setNegativeButton(tr(R.string.close),null).show()));
        page.addView(button(tr(R.string.help),false,this::support));
        page.addView(text(tr(R.string.password_not_saved),12,false));updateCodeButton();
    }
    private void emailCode() {
        if(busy||SystemClock.elapsedRealtime()<resendAt)return;
        String email=identifier.getText().toString().trim().toLowerCase(Locale.ROOT);
        if(!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()){feedback.setText(tr(R.string.invalid_email));return;}
        resendAt=SystemClock.elapsedRealtime()+60000;setBusy(true);
        io.submit(()-> {
            try {
                JSONObject r=api.call("POST","/api/auth/email/code",new JSONObject().put("email",email).put("delivery_version",2),"").object();
                String id=r.getString("challenge_id");if(id.length()<32)throw new IllegalStateException("Invalid challenge");
                ui(()-> {challenge=id;challengeEmail=email;feedback.setText(tr(R.string.code_sent));setBusy(false);});
            }catch(Exception e){ui(()->{if(!(e instanceof GvApi.ApiException&&((GvApi.ApiException)e).status==429))resendAt=0;feedback.setText(GvApi.friendly(this,e));setBusy(false);});}
        });
    }
    private void login() {
        if(busy)return;
        String id=identifier.getText().toString().trim(),pass=password.getText().toString();
        String otp=signup?code.getText().toString().trim():"";
        if(id.length()<3||pass.isBlank()){feedback.setText(tr(R.string.credentials_required));return;}
        if(signup&&(!RegistrationInput.password(pass)||!accept.isChecked())) {
            feedback.setText(tr(R.string.signup_common_requirements));return;
        }
        if(signup&&accountSignup&&!RegistrationInput.accountName(id)){feedback.setText(tr(R.string.account_signup_help));return;}
        if(signup&&!accountSignup&&(!id.equalsIgnoreCase(challengeEmail)||!otp.matches("[0-9]{6}")||challenge.isEmpty())) {
            feedback.setText(tr(R.string.signup_requirements));return;
        }
        setBusy(true);((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(password.getWindowToken(),0);
        boolean register=signup,byAccount=signup&&accountSignup;String challengeId=challenge;
        io.submit(()-> {
            try {
                // Login accepts usernames and GV codes in the historical email
                // field; account_name is only the username registration field.
                JSONObject body=new JSONObject().put(register&&byAccount?"account_name":"email",id).put("password",pass);
                if(register&&!byAccount)body.put("challenge_id",challengeId).put("code",otp);
                JSONObject response=api.call("POST",byAccount?"/api/auth/account/register":register?"/api/auth/email/register":"/api/login",body,"").object();
                String received=response.getString("token");if(received.isBlank())throw new IllegalStateException("Invalid session");
                session.save(received,id);token=received;
                if(BuildConfig.DEBUG)android.util.Log.i("GvPhoneQA","login_success");
                ui(()->{password.setText("");setBusy(false);showHome();refreshAccount();});
            }catch(Exception e){ui(()->{feedback.setText(GvApi.friendly(this,e));setBusy(false);});}
        });
    }
    private void setBusy(boolean b){busy=b;if(submit!=null)submit.setEnabled(!b);if(identifier!=null)identifier.setEnabled(!b);if(password!=null)password.setEnabled(!b);updateCodeButton();}
    private void updateCodeButton(){
        if(home||!signup||accountSignup||sendCode==null)return;
        long seconds=Math.max(0,(resendAt-SystemClock.elapsedRealtime()+999)/1000);
        setText(sendCode,seconds>0?AppLanguage.text(this,R.string.resend_countdown,seconds):tr(R.string.send_email_code));
        sendCode.setEnabled(!busy&&seconds==0);
    }
    private static void setText(TextView view,String value){if(view!=null&&!value.contentEquals(view.getText()))view.setText(value);}
    private void billingBusy(boolean value){busy=value;if(billingButton!=null){billingButton.setEnabled(!value);setText(billingButton,value?tr(R.string.billing_opening):planEntryLabel());}}
    private void updateSelection(){setText(routeButton,routeSelectionLabel());setText(modeButton,tr(R.string.connection_mode)+" · "+modeName(selectedMode)+" ›");setText(modeHelp,tr(selectedMode==ConnectionMode.GLOBAL?R.string.global_help:R.string.smart_help));}
    private void ui(Runnable r){main.post(()->{if(!destroyed)r.run();});}

    private String planEntryLabel(){return tr(plan!=null&&plan.usable()&&plan.deadline==0?R.string.ui_view_my_plan:BuildConfig.EXTERNAL_BILLING_ALLOWED?R.string.ui_subscribe:R.string.ui_view_plans);}
    // Empty status fields must not reserve a row. Becoming non-empty makes an error or timer visible immediately.
    private TextView optionalText(String value,int size,boolean bold){
        TextView v=text(value,size,bold);v.setVisibility(value.isEmpty()?View.GONE:View.VISIBLE);
        v.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){v.setVisibility(s.length()==0?View.GONE:View.VISIBLE);}public void afterTextChanged(android.text.Editable s){}});return v;
    }
    private LinearLayout section(){LinearLayout value=new LinearLayout(this);value.setOrientation(LinearLayout.VERTICAL);return value;}
    private void showHome() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        String restorePage=selectedUiPage;home=true;shell();LinearLayout body=page;
        feedback=optionalText("",14,false);feedback.setTextColor(Color.rgb(174,58,55));feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);body.addView(feedback);
        homeContent=section();body.addView(homeContent);page=homeContent;
        LinearLayout account=card(Color.rgb(224,245,241));account.addView(text(tr(R.string.remaining_time),13,false));expiry=text(tr(R.string.loading_plan),24,true);account.addView(expiry);
        LinearLayout connection=card(Color.WHITE);connection.setGravity(Gravity.CENTER_HORIZONTAL);connection.setPadding(dp(20),dp(12),dp(20),dp(12));
        ImageView emblem=new ImageView(this);emblem.setImageResource(R.drawable.gv_power);emblem.setContentDescription(tr(R.string.connect));
        android.graphics.drawable.GradientDrawable powerCircle=new android.graphics.drawable.GradientDrawable();powerCircle.setShape(android.graphics.drawable.GradientDrawable.OVAL);powerCircle.setColor(PALE);powerCircle.setStroke(dp(2),TEAL);emblem.setBackground(powerCircle);emblem.setPadding(dp(18),dp(18),dp(18),dp(18));
        LinearLayout.LayoutParams powerLayout=new LinearLayout.LayoutParams(dp(60),dp(60));powerLayout.setMargins(0,dp(4),0,dp(4));connection.addView(emblem,powerLayout);
        phase=text(tr(R.string.disconnected),24,true);phase.setGravity(Gravity.CENTER);connection.addView(phase);
        node=text(tr(R.string.ready_to_connect),14,false);node.setGravity(Gravity.CENTER);node.setPadding(0,0,0,0);connection.addView(node);
        duration=optionalText("",14,false);duration.setGravity(Gravity.CENTER);duration.setPadding(0,dp(2),0,dp(2));connection.addView(duration);
        routeButton=button(routeSelectionLabel(),false,this::chooseRoute);connection.addView(routeButton);
        connect=button(tr(R.string.connect),true,this::toggle);connect.setTextSize(22);connect.setTag("gvMainConnectButton");connection.addView(connect);
        billingButton=button(planEntryLabel(),true,()->showSignedPage("plan"));billingButton.setTextSize(22);billingButton.setTag("gvHomePlanPurchaseEntry");connection.addView(billingButton);
        TextView allowance=text(tr(R.string.ui_shared_devices),13,false);allowance.setGravity(Gravity.CENTER);connection.addView(allowance);
        releaseBlock=button(tr(R.string.release_block),false,this::disconnectVpn);connection.addView(releaseBlock);
        modeButton=button(tr(R.string.connection_mode)+" · "+modeName(selectedMode)+" ›",false,this::chooseMode);page.addView(modeButton);
        modeHelp=text(selectedMode==ConnectionMode.GLOBAL?tr(R.string.global_help):tr(R.string.smart_help),12,false);page.addView(modeHelp);
        traffic=text(tr(R.string.initial_traffic),13,false);traffic.setGravity(Gravity.CENTER);page.addView(traffic);
        page=body;planContent=section();body.addView(planContent);buildPlansPage();settingsContent=section();body.addView(settingsContent);buildSettingsPage();
        LinearLayout nav=new LinearLayout(this);nav.setGravity(Gravity.CENTER);nav.setPadding(dp(16),dp(6),dp(16),dp(6));
        navHome=button(tr(R.string.ui_nav_home),false,()->showSignedPage("home"));navPlans=button(tr(R.string.ui_nav_plans),false,()->showSignedPage("plan"));navSettings=button(tr(R.string.settings),false,()->showSignedPage("settings"));
        for(Button b:new Button[]{navHome,navPlans,navSettings}){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(52),1);lp.setMargins(dp(3),0,dp(3),0);b.setTextSize(15);nav.addView(b,lp);}shellRoot.addView(nav,new LinearLayout.LayoutParams(-1,-2));
        updatePlan();updateConnection();showSignedPage(restorePage);
    }
    private void showSignedPage(String selected){
        if(!home||homeContent==null)return;
        selectedUiPage=java.util.Arrays.asList("home","plan","settings").contains(selected)?selected:"home";
        homeContent.setVisibility(selectedUiPage.equals("home")?View.VISIBLE:View.GONE);planContent.setVisibility(selectedUiPage.equals("plan")?View.VISIBLE:View.GONE);settingsContent.setVisibility(selectedUiPage.equals("settings")?View.VISIBLE:View.GONE);
        Button[] buttons={navHome,navPlans,navSettings};String[] pages={"home","plan","settings"};
        for(int i=0;i<buttons.length;i++)if(buttons[i]!=null){boolean active=selectedUiPage.equals(pages[i]);buttons[i].setTextColor(active?Color.WHITE:INK);buttons[i].setBackground(shape(active?TEAL:Color.WHITE,12));buttons[i].setSelected(active);}
        if(selectedUiPage.equals("plan")){renderNativePlans();loadNativePlans(false);}
        if(primaryScroll!=null)primaryScroll.post(()->primaryScroll.scrollTo(0,0));
    }
    private void buildPlansPage(){
        planContent.addView(text(tr(R.string.ui_nav_plans),26,true));planSummary=text(tr(R.string.loading_plan),20,true);planContent.addView(planSummary);planContent.addView(text(tr(R.string.ui_shared_devices),14,false));
        planContent.addView(button(tr(R.string.refresh_plan),false,()->{billingAttempts=0;main.removeCallbacks(billingPoll);refreshAccount();loadNativePlans(true);}));
        planMessage=text("",14,false);planContent.addView(planMessage);planOptions=section();planContent.addView(planOptions);
        if(BuildConfig.EXTERNAL_BILLING_ALLOWED){planContent.addView(text(tr(R.string.ui_website_plan_note),13,false));planContent.addView(button(tr(R.string.ui_view_website_plans),false,this::openBilling));}
        else planContent.addView(text(tr(R.string.store_access_help),14,false));
        planContent.addView(button(tr(R.string.my_orders),false,this::orders));planContent.addView(button(tr(R.string.privacy),false,()->legal("privacy")));
    }
    private void renderNativePlans(){
        if(planOptions==null||!home)return;planOptions.removeAllViews();if(!BuildConfig.EXTERNAL_BILLING_ALLOWED)return;
        if(planCatalog==null){if(!catalogBusy&&planMessage.getText().length()==0)planMessage.setText(tr(R.string.ui_catalog_loading));return;}
        for(PlanCatalog.Item item:planCatalog.items()){
            LinearLayout card=section();card.setPadding(dp(18),dp(16),dp(18),dp(16));card.setBackground(shape(Color.WHITE,16));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(7),0,dp(7));planOptions.addView(card,lp);
            card.addView(text(AppLanguage.text(this,R.string.ui_months,item.months()),20,true));card.addView(text(String.format(java.util.Locale.US,"USD $%.2f",item.cents()/100.0),26,true));card.addView(text(tr(R.string.ui_shared_devices),13,false));
            Button choose=button(tr(R.string.ui_subscribe),true,()->openBilling(item.code()));choose.setEnabled(planCatalog.checkoutEnabled());card.addView(choose);
        }
    }
    private void loadNativePlans(boolean force){
        if(!BuildConfig.EXTERNAL_BILLING_ALLOWED||catalogBusy||token.isBlank()||!home)return;
        if(!force&&planCatalog!=null&&SystemClock.elapsedRealtime()-catalogFetchedAt<60000)return;
        catalogBusy=true;planMessage.setText(tr(R.string.ui_catalog_loading));int epoch=authEpoch;String access=token;ApiTransport accountApi=api;
        io.submit(()->{try{PlanCatalog.Result value=PlanCatalog.parse(accountApi.call("GET","/api/website/plans",null,access).object());ui(()->{if(epoch!=authEpoch||!home)return;catalogBusy=false;planCatalog=value;catalogFetchedAt=SystemClock.elapsedRealtime();planMessage.setText(value.items().isEmpty()?tr(R.string.ui_catalog_unavailable):"");renderNativePlans();});}
        catch(Exception e){ui(()->{if(epoch!=authEpoch||!home)return;catalogBusy=false;planCatalog=null;planMessage.setText(tr(R.string.ui_catalog_unavailable));renderNativePlans();if(e instanceof GvApi.ApiException&&((GvApi.ApiException)e).status==401)logout(false);});}});
    }
    private void buildSettingsPage(){
        settingsContent.addView(text(tr(R.string.settings),26,true));settingsContent.addView(text(session.email(),14,false));settingsContent.addView(text(tr(R.string.ui_shared_devices),14,false));
        settingsContent.addView(button(tr(R.string.account_devices),false,this::devices));settingsContent.addView(button(tr(R.string.change_password),false,this::changePassword));settingsContent.addView(button(tr(R.string.connection_privacy),false,this::privacyControls));
        settingsContent.addView(button(AppLanguage.text(this,R.string.language_button,AppLanguage.name(this)),false,this::chooseLanguage));settingsContent.addView(button(tr(R.string.background_vpn),false,this::backgroundVpn));settingsContent.addView(button(tr(R.string.help),false,this::support));settingsContent.addView(button(tr(R.string.privacy),false,()->legal("privacy")));settingsContent.addView(button(tr(R.string.licenses),false,this::licenses));settingsContent.addView(button(tr(R.string.delete_account_request),false,this::deletionRequest));settingsContent.addView(button(tr(R.string.logout),false,this::confirmLogout));
        settingsContent.addView(text(tr(BuildConfig.DEBUG?R.string.test_version_prefix:R.string.version_prefix)+BuildConfig.VERSION_NAME+" ("+BuildConfig.VERSION_CODE+")",13,false));
    }
    private void refreshAccount() {
        if(busy||token.isBlank())return;busy=true;accountRefreshing=true;routeFailure=0;
        int epoch=authEpoch;String accountToken=token;ApiTransport accountApi=api;
        io.submit(()-> {
            try {
                // The same server controls the registration-based experience
                // window. A claim receipt is never shown as active access.
                JSONObject claim=null;
                GvApi.Entitlement value=new GvApi.Entitlement(accountApi.call("GET","/api/entitlement",null,accountToken));
                if(!value.usable()&&trialCheckedEpoch!=epoch&&trialAttempts<2){
                    trialAttempts++;
                    try{claim=accountApi.call("POST","/api/trial/claim",new JSONObject().put("device_id",session.deviceId()),accountToken).object();trialCheckedEpoch=epoch;}
                    catch(GvApi.ApiException e){if(e.status==401)throw e;if(e.status!=429&&e.status<500)trialCheckedEpoch=epoch;}
                    catch(java.io.IOException transientFailure){}
                    catch(Exception invalidClaim){if(invalidClaim instanceof InterruptedException)throw invalidClaim;}
                    if(claim!=null)value=new GvApi.Entitlement(accountApi.call("GET","/api/entitlement",null,accountToken));
                }
                GvApi.Entitlement accountPlan=value;
                int trialMessage=TrialReview.message(claim,value);
                int routeError=0;
                try {
                    if(value.usable()) {
                        String device=session.deviceId(),path="/api/connect-candidates?device_id="+Uri.encode(device)+"&mode=tcp";
                        JSONArray nodes;
                        try{nodes=accountApi.call("GET",path,null,accountToken).object().getJSONArray("candidates");}
                        catch(GvApi.ApiException e){
                            if(e.status!=403||!"device not registered".equals(e.detail))throw e;
                            accountApi.call("POST","/api/devices",new JSONObject().put("device_id",device)
                                .put("device_name","Global Villager Android · "+Build.MODEL),accountToken);
                            nodes=accountApi.call("GET",path,null,accountToken).object().getJSONArray("candidates");
                        }
                        List<RouteCandidates.Route> available=RouteCandidates.parse(nodes);
                        if(BuildConfig.DEBUG)android.util.Log.i("GvPhoneQA","available_nodes="+available.stream().map(r->r.node).collect(java.util.stream.Collectors.joining(",")));
                        ui(()->{if(authEpoch==epoch){routes.clear();routes.addAll(available);if(home&&routeButton!=null)routeButton.setText(routeSelectionLabel());}});
                    }
                    else {routeError=R.string.no_active_plan;ui(()->{if(authEpoch==epoch)routes.clear();});}
                }catch(Exception e){if(e instanceof GvApi.ApiException&&((GvApi.ApiException)e).status==401)throw e;routeError=errorResource(e);ui(()->{if(authEpoch==epoch)routes.clear();});}
                int finalRouteError=routeError;
                ui(()->{if(authEpoch!=epoch||!home)return;plan=accountPlan;busy=false;accountRefreshing=false;routeFailure=finalRouteError;feedback.setText(finalRouteError!=0?tr(finalRouteError):trialMessage==0?"":tr(trialMessage));updatePlan();finishRouteLoad();refreshBillingReturn();});
                if(BuildConfig.DEBUG&&authEpoch==epoch)logBillingCheck(accountToken,accountPlan);
            }catch(Exception e){ui(()->{if(authEpoch!=epoch||!home)return;busy=false;accountRefreshing=false;routeFailure=errorResource(e);feedback.setText(tr(routeFailure));if(e instanceof GvApi.ApiException&&((GvApi.ApiException)e).status==401)logout(false);else{finishRouteLoad();refreshBillingReturn();}});}
        });
    }
    private void updatePlan() {
        if(!home||expiry==null)return;
        if(plan==null)setText(expiry,tr(R.string.loading_plan));
        else if(!plan.usable())setText(expiry,tr(R.string.no_active_plan));
        else if(plan.deadline==0)setText(expiry,tr(R.string.active_plan));
        else {long seconds=Math.max(0,(plan.deadline-SystemClock.elapsedRealtime())/1000);setText(expiry,AppLanguage.text(this,R.string.plan_time_format,seconds/86400,(seconds/3600)%24,(seconds/60)%60,seconds%60));}
        setText(planSummary,expiry.getText().toString());if(billingButton!=null&&!busy)setText(billingButton,planEntryLabel());
    }
    private void refreshBillingReturn() {
        if(destroyed||!registered||!home||token.isBlank()||billingAttempts>=6)return;
        android.content.SharedPreferences prefs=getSharedPreferences("gv-ui",MODE_PRIVATE);
        if(!prefs.getBoolean("billing-return-pending",false))return;
        if(busy){main.removeCallbacks(billingPoll);main.postDelayed(billingPoll,1500);return;}
        busy=true;int epoch=authEpoch;String accountToken=token;
        int attempt=++billingAttempts;feedback.setText(tr(R.string.billing_return_checking));
        String saved=prefs.getString("billing-baseline","");
        io.submit(()->{try{
            // GET only: neither a return nor an active old plan authorizes a charge.
            JSONArray list=api.call("GET","/api/billing/orders",null,accountToken).object().getJSONArray("orders");
            GvApi.Entitlement value=new GvApi.Entitlement(api.call("GET","/api/entitlement",null,accountToken));
            JSONObject baseline=null;try{if(!saved.isBlank())baseline=new JSONObject(saved);}catch(Exception ignored){}
            BillingReview.Outcome outcome=BillingReview.inspect(baseline,list,value.usable());
            ui(()->{if(authEpoch!=epoch||!home)return;plan=value;busy=false;updatePlan();
                feedback.setText(tr(billingMessage(outcome)));
                boolean resolved=outcome==BillingReview.Outcome.CONFIRMED||outcome==BillingReview.Outcome.NEEDS_REVIEW||outcome==BillingReview.Outcome.SCHEDULED;
                if(resolved)prefs.edit().remove("billing-return-pending").remove("billing-baseline").apply();
                else if(outcome!=BillingReview.Outcome.UNKNOWN)scheduleBillingPoll(attempt);
                if(BuildConfig.DEBUG)android.util.Log.i("GvPhoneQA","billing_return="+outcome.name());
            });
        }catch(Exception e){ui(()->{if(authEpoch!=epoch||!home)return;busy=false;
            feedback.setText(tr(R.string.billing_return_failed));
            if(e instanceof GvApi.ApiException&&((GvApi.ApiException)e).status==401)logout(false);
            else scheduleBillingPoll(attempt);
        });}});
    }
    private void scheduleBillingPoll(int attempt){
        if(!registered||attempt>=6)return;
        main.removeCallbacks(billingPoll);main.postDelayed(billingPoll,new long[]{2000,4000,6000,10000,15000}[attempt-1]);
    }
    private static int billingMessage(BillingReview.Outcome outcome){return switch(outcome){
        case CONFIRMED -> R.string.billing_confirmed;
        case PAID_SYNCING -> R.string.billing_paid_syncing;
        case SCHEDULED -> R.string.billing_scheduled;
        case PENDING -> R.string.billing_pending;
        case NEEDS_REVIEW -> R.string.billing_needs_review;
        case NO_CHANGE -> R.string.billing_no_change;
        case UNKNOWN -> R.string.billing_return_unconfirmed;
    };}
    private void orders(){
        if(busy||token.isBlank())return;busy=true;int epoch=authEpoch;String accountToken=token;
        feedback.setText(tr(R.string.orders_loading));
        io.submit(()->{try{
            JSONArray list=api.call("GET","/api/billing/orders",null,accountToken).object().getJSONArray("orders");
            StringBuilder detail=new StringBuilder(tr(BuildConfig.EXTERNAL_BILLING_ALLOWED?R.string.orders_help:R.string.store_orders_help));
            if(list.length()==0)detail.append("\n\n").append(tr(R.string.orders_empty));
            for(int i=0;i<list.length();i++){
                JSONObject item=list.getJSONObject(i);String state=item.optString("payment_state");
                int label=switch(state){case "paid"->R.string.order_paid;case "paid_not_started"->R.string.billing_scheduled;case "pending"->R.string.order_pending;case "expired"->R.string.order_expired;case "revoked"->R.string.order_revoked;default->R.string.order_unknown;};
                String date="";try{date=java.time.Instant.parse(item.optString("created_at")).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString();}catch(Exception ignored){}
                detail.append("\n\n").append(tr(label)).append(" · ").append(date);
            }
            ui(()->{if(authEpoch!=epoch||!home)return;busy=false;feedback.setText("");
                ScrollView scroll=new ScrollView(this);TextView content=text(detail.toString(),14,false);content.setPadding(dp(20),dp(10),dp(20),dp(10));scroll.addView(content);
                AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle(tr(R.string.my_orders)).setView(scroll).setPositiveButton(tr(R.string.close),null);
                if(BuildConfig.EXTERNAL_BILLING_ALLOWED)dialog.setNeutralButton(tr(R.string.website_orders),(d,w)->openBilling());
                dialog.show();});
        }catch(Exception e){ui(()->{if(authEpoch!=epoch||!home)return;busy=false;feedback.setText(GvApi.friendly(this,e));if(e instanceof GvApi.ApiException&&((GvApi.ApiException)e).status==401)logout(false);});}});
    }
    private void logBillingCheck(String accountToken,GvApi.Entitlement value) {
        try {
            JSONArray orders=api.call("GET","/api/billing/orders",null,accountToken).object().getJSONArray("orders");
            String state="none",created="unknown";
            if(orders.length()>0){
                JSONObject latest=orders.getJSONObject(0);String raw=latest.optString("payment_state");
                state=java.util.Arrays.asList("paid","pending","expired","revoked").contains(raw)?raw:"unknown";
                try{created=java.time.Instant.parse(latest.optString("created_at")).toString();}catch(Exception ignored){}
            }
            // Test-build diagnostics expose only a state and date, never account,
            // order, subscription, token or payment-method identifiers.
            android.util.Log.i("GvPhoneQA","billing_check latest="+state+" created="+created+" entitlement_usable="+value.usable());
        }catch(Exception e){android.util.Log.i("GvPhoneQA","billing_check unavailable="+e.getClass().getSimpleName());}
    }
    private void toggle() {
        GvVpnService.Snapshot s=GvVpnService.status;
        if(s.connected()||s.busy()){disconnectVpn();return;}
        if(plan==null||!plan.usable()){feedback.setText(tr(R.string.refresh_before_connect));refreshAccount();return;}
        requestVpnConsent();
    }
    private void requestVpnConsent(){
        if(getSharedPreferences("gv-ui",MODE_PRIVATE).getInt("vpn-disclosure-version",0)!=VPN_DISCLOSURE_VERSION){
            ScrollView scroll=new ScrollView(this);TextView content=text(tr(R.string.vpn_disclosure_body),14,false);content.setPadding(dp(20),dp(8),dp(20),dp(8));scroll.addView(content);
            AlertDialog notice=new AlertDialog.Builder(this).setTitle(tr(R.string.vpn_disclosure_title)).setView(scroll)
                .setPositiveButton(tr(R.string.vpn_disclosure_accept),(d,w)->{getSharedPreferences("gv-ui",MODE_PRIVATE).edit().putInt("vpn-disclosure-version",VPN_DISCLOSURE_VERSION).apply();requestVpnConsent();})
                .setNegativeButton(tr(R.string.not_now),(d,w)->feedback.setText(tr(R.string.vpn_disclosure_declined))).show();
            notice.getButton(AlertDialog.BUTTON_POSITIVE).setFilterTouchesWhenObscured(true);return;
        }
        Intent permission=VpnService.prepare(this);
        if(permission!=null)startActivityForResult(permission,71);else startVpn();
    }
    private void startVpn() {
        if(getSharedPreferences("gv-ui",MODE_PRIVATE).getInt("vpn-disclosure-version",0)!=VPN_DISCLOSURE_VERSION){requestVpnConsent();return;}
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},72);
        startForegroundService(new Intent(this,GvVpnService.class).setAction(GvVpnService.START).putExtra("country",selectedCountry).putExtra("node",selectedNode).putExtra("mode",selectedMode.value));
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==71&&result==RESULT_OK)startVpn();else if(request==71&&feedback!=null)feedback.setText(tr(R.string.vpn_consent_missing));}
    private void updateConnection() {
        if(!home||phase==null)return;
        GvVpnService.Snapshot s=GvVpnService.status;
        // Failure guidance is longer than the normal status. Keep the route and
        // recovery controls reachable without enlarging the whole status card.
        phase.setTextSize(s.blocking()||"error".equals(s.phase)?16:24);
        setText(phase,s.message(this));setText(node,s.node.isBlank()?tr(R.string.system_vpn):s.node+" · "+modeName(s.mode)+" · "+countryName(s.country));
        setText(connect,(s.connected()||s.busy())&&GvVpnService.systemManaged?tr(R.string.system_vpn_settings):s.connected()?tr(R.string.disconnect):s.busy()?tr(R.string.cancel_connection):tr(R.string.connect));
        releaseBlock.setVisibility(s.blocking()?View.VISIBLE:View.GONE);
        if(s.connected())setText(duration,tr(R.string.session_time_prefix)+formatDuration(SystemClock.elapsedRealtime()-s.connectedAt));
        else if(s.busy()&&s.startedAt>0)setText(duration,tr(R.string.preparing_time_prefix)+formatDuration(SystemClock.elapsedRealtime()-s.startedAt));else setText(duration,"");
        setText(traffic,tr(R.string.upload_prefix)+bytes(s.up)+tr(R.string.download_prefix)+bytes(s.down));
    }
    private String routeLabel(RouteCandidates.Route route) {
        RouteHealthStore health=new RouteHealthStore(this);long delay=health.delay(route.node);
        String timing=health.recentlyFailed(route.node)?tr(R.string.route_recent_failure):delay>=0?AppLanguage.text(this,R.string.latency_verified,delay):"";
        return (route.country.isEmpty()?"":countryName(route.country)+" · ")+route.node+(timing.isEmpty()?"":"\n"+timing);
    }
    private String routeSelectionLabel() {
        String label;
        if(!selectedNode.isEmpty())label=(selectedCountry.isEmpty()?"":countryName(selectedCountry)+" · ")+selectedNode;
        else label=selectedCountry.isEmpty()?tr(R.string.auto_recommended):countryName(selectedCountry)+" · "+tr(R.string.country_auto);
        return tr(R.string.route_prefix)+label+" ›";
    }
    private void chooseRoute() {
        if(GvVpnService.status.busy()){new AlertDialog.Builder(this).setTitle(tr(R.string.choose_route)).setMessage(tr(R.string.wait_or_cancel)).setPositiveButton(tr(R.string.close),null).show();return;}
        routeRequested=true;
        if(routes.isEmpty()){
            if(!busy&&routeFailure==0)refreshAccount();
            showRouteStatus();return;
        }
        showRouteChoices();
    }
    private static int errorResource(Exception e){return e instanceof GvApi.ApiException?((GvApi.ApiException)e).friendlyResource():R.string.network_request_failed;}
    private void dismissRouteDialog(){if(routeDialog!=null){routeDialog.setOnDismissListener(null);routeDialog.dismiss();routeDialog=null;}}
    private void finishRouteLoad(){if(routeRequested&&home){if(routes.isEmpty())showRouteStatus();else showRouteChoices();}}
    private void showRouteStatus(){
        dismissRouteDialog();boolean loading=accountRefreshing||busy;
        int reason=loading?R.string.routes_loading:routeFailure!=0?routeFailure:R.string.routes_empty;
        AlertDialog.Builder builder=new AlertDialog.Builder(this).setTitle(tr(R.string.choose_route)).setMessage(tr(reason)).setNegativeButton(tr(R.string.close),null);
        if(!loading){
            boolean deviceIssue=routeFailure==R.string.api_device_limit||routeFailure==R.string.api_device_disabled||routeFailure==R.string.device_release_pending;
            builder.setPositiveButton(tr(deviceIssue?R.string.account_devices:R.string.retry),(d,w)->{routeRequested=false;if(deviceIssue)devices();else{routeFailure=0;chooseRoute();}});
            builder.setNeutralButton(tr(R.string.logout),(d,w)->{routeRequested=false;confirmLogout();});
        }
        routeDialog=builder.create();AlertDialog shown=routeDialog;shown.setOnDismissListener(d->{if(routeDialog==shown){routeRequested=false;routeDialog=null;}});shown.show();
    }
    private void showRouteChoices(){
        dismissRouteDialog();
        List<RouteCandidates.Route> choices=new ArrayList<>(routes);
        String[] labels=new String[choices.size()+1];labels[0]=tr(R.string.auto_recommended);
        int checked=selectedNode.isEmpty()&&selectedCountry.isEmpty()?0:-1;
        for(int i=0;i<choices.size();i++){labels[i+1]=routeLabel(choices.get(i));if(choices.get(i).node.equals(selectedNode))checked=i+1;}
        routeDialog=new AlertDialog.Builder(this).setTitle(tr(R.string.choose_route)).setSingleChoiceItems(labels,checked,(d,index)-> {
            d.dismiss();String nextNode=index==0?"":choices.get(index-1).node;
            String nextCountry=index==0?"":choices.get(index-1).country;
            boolean changed=!nextNode.equals(selectedNode)||!nextCountry.equals(selectedCountry);
            selectedNode=nextNode;selectedCountry=nextCountry;
            getSharedPreferences("gv-ui",MODE_PRIVATE).edit().putString("country",selectedCountry).putString("node",selectedNode).apply();
            boolean reconnect=changed&&GvVpnService.status.connected();updateSelection();
            if(reconnect)startVpn();
        }).setNegativeButton(tr(R.string.cancel),null).show();
        AlertDialog shown=routeDialog;shown.setOnDismissListener(d->{if(routeDialog==shown){routeRequested=false;routeDialog=null;}});
        routeDialog.getListView().setFilterTouchesWhenObscured(true);
    }
    private void confirmLogout(){new AlertDialog.Builder(this).setTitle(tr(R.string.logout)).setMessage(tr(R.string.logout_explanation))
        .setPositiveButton(tr(R.string.logout_confirm),(d,w)->logout(true)).setNegativeButton(tr(R.string.cancel),null).show();}
    private void settings() {showSignedPage("settings");}
    private void devices() {
        if(deviceBusy||token.isBlank())return;deviceBusy=true;int epoch=authEpoch;String accountToken=token;ApiTransport deviceApi=api;
        AlertDialog loading=new AlertDialog.Builder(this).setTitle(tr(R.string.account_devices)).setMessage(tr(R.string.devices_loading)).setNegativeButton(tr(R.string.close),null).show();
        io.submit(()-> {
            try {
                String thisDevice=session.deviceId();
                JSONArray list=(JSONArray)deviceApi.call("GET","/api/devices",null,accountToken).data;
                ui(()->{loading.dismiss();if(authEpoch!=epoch||!home)return;deviceBusy=false;showDevices(list,thisDevice);});
            }catch(Exception e){ui(()->{loading.dismiss();if(authEpoch!=epoch||!home)return;deviceBusy=false;new AlertDialog.Builder(this).setTitle(tr(R.string.account_devices)).setMessage(GvApi.friendly(this,e)).setPositiveButton(tr(R.string.retry),(d,w)->devices()).setNegativeButton(tr(R.string.close),null).show();if(e instanceof GvApi.ApiException&&((GvApi.ApiException)e).status==401)logout(false);});}
        });
    }
    private void showDevices(JSONArray list,String thisDevice){
        try{
            String prefix=DeviceReleaseReceipt.scope(session.email());
            for(java.util.Map.Entry<String,?> item:getSharedPreferences("gv-ui",MODE_PRIVATE).getAll().entrySet())if(item.getKey().startsWith(prefix)&&item.getKey().endsWith("-device")&&item.getValue() instanceof String key){
                boolean found=false;for(int n=0;n<list.length();n++)if(key.equals(list.getJSONObject(n).optString("device_id"))){found=true;break;}
                if(!found)list.put(new JSONObject().put("device_id",key).put("device_name",tr(R.string.device)).put("status","pending"));
            }
        }catch(Exception ignored){}
        ScrollView scroll=new ScrollView(this);LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(20),dp(8),dp(20),dp(8));scroll.addView(box);
        box.addView(text(session.email(),14,true));box.addView(text(tr(R.string.device_management_help),13,false));
        if(list.length()==0)box.addView(text(tr(R.string.devices_empty),14,false));
        for(int i=0;i<list.length();i++){
            JSONObject device=list.optJSONObject(i);if(device==null)continue;
            String key=device.optString("device_id");boolean current=key.equals(thisDevice),active="active".equals(device.optString("status"));
            String label=device.optString("device_name",tr(R.string.device))+(current?" · "+tr(R.string.this_device):"")+(key.length()>6?" · "+key.substring(key.length()-6):"");
            box.addView(text(label,15,true));box.addView(text(tr("pending".equals(device.optString("status"))?R.string.device_removing:active?R.string.device_active:R.string.device_disabled),12,false));
            try{if(getSharedPreferences("gv-ui",MODE_PRIVATE).contains(DeviceReleaseReceipt.key(session.email(),key)))box.addView(button(tr(R.string.check_status),false,()->{if(devicesDialog!=null)devicesDialog.dismiss();releaseDevice(key,current,true);}));}catch(Exception ignored){}
            if(!key.isBlank()&&!"pending".equals(device.optString("status")))box.addView(button(tr(R.string.rename_device),false,()->renameDevice(key,device.optString("device_name",""))));
            if((active||"disabled".equals(device.optString("status")))&&!key.isBlank())box.addView(button(tr(R.string.remove_device),false,()->new AlertDialog.Builder(this).setTitle(tr(R.string.remove_device)).setMessage(label+"\n\n"+tr(current?R.string.remove_current_device_help:R.string.remove_device_help))
                .setPositiveButton(tr(R.string.remove_device),(d,w)->{if(devicesDialog!=null)devicesDialog.dismiss();releaseDevice(key,current,false);}).setNegativeButton(tr(R.string.cancel),null).show()));
        }
        devicesDialog=new AlertDialog.Builder(this).setTitle(tr(R.string.account_devices)).setView(scroll).setPositiveButton(tr(R.string.close),null).setNeutralButton(tr(R.string.refresh_plan),(d,w)->devices()).show();
    }
    private void renameDevice(String device,String name){
        if(deviceBusy)return;EditText input=new EditText(this);input.setText(name);input.setSingleLine(true);input.setFilterTouchesWhenObscured(true);input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(32)});
        new AlertDialog.Builder(this).setTitle(tr(R.string.rename_device)).setView(input).setPositiveButton(tr(R.string.save),(d,w)->{
            String value=input.getText().toString().trim();if(value.isEmpty()){Toast.makeText(this,tr(R.string.device_name_required),Toast.LENGTH_SHORT).show();return;}
            deviceBusy=true;int epoch=authEpoch;String accountToken=token;ApiTransport deviceApi=api;io.submit(()->{try{
                deviceApi.call("POST","/api/devices/"+Uri.encode(device)+"/rename",new JSONObject().put("device_name",value),accountToken);
                ui(()->{if(authEpoch!=epoch||!home)return;deviceBusy=false;if(devicesDialog!=null)devicesDialog.dismiss();devices();});
            }catch(Exception e){ui(()->{if(authEpoch!=epoch||!home)return;deviceBusy=false;new AlertDialog.Builder(this).setTitle(tr(R.string.rename_device)).setMessage(GvApi.friendly(this,e)).setPositiveButton(tr(R.string.close),null).show();});}});
        }).setNegativeButton(tr(R.string.cancel),null).show();
    }
    private void releaseDevice(String device,boolean current,boolean checkOnly){
        if(deviceBusy||token.isBlank())return;
        final String storeKey,request;
        try{
            storeKey=DeviceReleaseReceipt.key(session.email(),device);android.content.SharedPreferences prefs=getSharedPreferences("gv-ui",MODE_PRIVATE);
            String saved=prefs.getString(storeKey,"");request=saved.isEmpty()?UUID.randomUUID().toString():saved;
            if(!prefs.edit().putString(storeKey,request).putString(storeKey+"-device",device).commit())throw new IllegalStateException("Pending request unavailable");
        }catch(Exception e){new AlertDialog.Builder(this).setTitle(tr(R.string.remove_device)).setMessage(tr(R.string.device_remove_failed)).setPositiveButton(tr(R.string.close),null).show();return;}
        deviceBusy=true;int epoch=authEpoch;String accountToken=token;ApiTransport deviceApi=api;
        AlertDialog progress=new AlertDialog.Builder(this).setTitle(tr(R.string.remove_device)).setMessage(tr(R.string.device_removing)).setNegativeButton(tr(R.string.close),null).show();
        deviceProgress=progress;
        io.submit(()->{
            try{
                String endpoint="/api/devices/"+Uri.encode(device);
                JSONObject receipt=deviceApi.call(checkOnly?"GET":"POST",endpoint+(checkOnly?"/release-status?request_id="+request:"/release"),checkOnly?null:new JSONObject().put("request_id",request),accountToken).object();
                if("unknown".equals(receipt.optString("status")))throw new GvApi.ApiException(404,"release request not found");
                if(!device.equals(receipt.optString("device_id"))||!request.equals(receipt.optString("request_id")))throw new java.io.IOException("Receipt binding mismatch");
                boolean done=DeviceReleaseReceipt.completed(receipt,device,request);
                ui(()->{progress.dismiss();if(authEpoch!=epoch||!home)return;deviceBusy=false;
                    if(done){getSharedPreferences("gv-ui",MODE_PRIVATE).edit().remove(storeKey).remove(storeKey+"-device").apply();if(current)logout(false);else refreshAccount();new AlertDialog.Builder(this).setTitle(tr(R.string.remove_device)).setMessage(tr(R.string.device_removed)).setPositiveButton(tr(R.string.close),null).show();}
                    else new AlertDialog.Builder(this).setTitle(tr(R.string.remove_device)).setMessage(tr(R.string.device_release_pending)).setPositiveButton(tr(R.string.check_status),(d,w)->releaseDevice(device,current,true)).setNegativeButton(tr(R.string.close),null).show();
                });
            }catch(Exception e){ui(()->{progress.dismiss();if(authEpoch!=epoch||!home)return;deviceBusy=false;
                if(e instanceof GvApi.ApiException&&((GvApi.ApiException)e).status==401){logout(false);return;}
                int message=e instanceof GvApi.ApiException&&((GvApi.ApiException)e).status==503?R.string.device_release_unavailable:R.string.device_remove_failed;
                new AlertDialog.Builder(this).setTitle(tr(R.string.remove_device)).setMessage(tr(message)).setPositiveButton(tr(R.string.retry),(d,w)->releaseDevice(device,current,false)).setNegativeButton(tr(R.string.close),null).show();
            });}
        });
    }
    private void logout(boolean revoke) {
        if(GvVpnService.status.connected()||GvVpnService.status.busy()||GvVpnService.status.blocking())startService(new Intent(this,GvVpnService.class).setAction(GvVpnService.STOP));
        dismissRouteDialog();routeRequested=false;routes.clear();routeFailure=0;accountRefreshing=false;deviceBusy=false;
        if(devicesDialog!=null){devicesDialog.dismiss();devicesDialog=null;}if(deviceProgress!=null){deviceProgress.dismiss();deviceProgress=null;}
        if(api instanceof GvApi){api.cancel();api=new GvApi(this);}ApiTransport logoutApi=api;
        authEpoch++;trialCheckedEpoch=-1;trialAttempts=0;String previous=token;token="";session.clear();getSharedPreferences("gv-ui",MODE_PRIVATE).edit().remove("billing-return-pending").remove("billing-baseline").apply();main.removeCallbacks(billingPoll);billingAttempts=0;plan=null;busy=false;showLogin(false);
        if(revoke&&!previous.isBlank())io.submit(()->{try{logoutApi.call("POST","/api/logout",new JSONObject(),previous);}catch(Exception ignored){}});
    }
    private void changePassword(){
        if(busy||token.isBlank()||(passwordDialog!=null&&passwordDialog.isShowing()))return;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(20),dp(8),dp(20),dp(8));
        box.addView(text(tr(R.string.password_change_help),13,false));
        EditText current=input(box,tr(R.string.current_password),true),next=input(box,tr(R.string.new_password_hint),true),confirmation=input(box,tr(R.string.confirm_password),true);
        next.setAutofillHints("newPassword");confirmation.setAutofillHints("newPassword");
        TextView result=text("",14,false);box.addView(result);
        ScrollView scroll=new ScrollView(this);scroll.addView(box);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(tr(R.string.change_password)).setView(scroll)
            .setPositiveButton(tr(R.string.change_password),null).setNegativeButton(tr(R.string.cancel),null).create();
        passwordDialog=dialog;dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.setOnDismissListener(d->{passwordDialog=null;current.setText("");next.setText("");confirmation.setText("");if(home)getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);});
        dialog.show();Button proceed=dialog.getButton(AlertDialog.BUTTON_POSITIVE);proceed.setFilterTouchesWhenObscured(true);
        proceed.setOnClickListener(v->{
            if(busy)return;String old=current.getText().toString(),newPass=next.getText().toString();
            if(old.isBlank()||!RegistrationInput.password(newPass)||!newPass.equals(confirmation.getText().toString())){result.setText(tr(R.string.password_change_requirements));return;}
            busy=true;int epoch=authEpoch;String accountToken=token;
            current.setEnabled(false);next.setEnabled(false);confirmation.setEnabled(false);proceed.setEnabled(false);dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);dialog.setCancelable(false);result.setText(tr(R.string.password_changing));
            io.submit(()->{try{
                api.call("POST","/api/password/change",new JSONObject().put("current_password",old).put("new_password",newPass),accountToken);
                ui(()->{if(authEpoch!=epoch)return;busy=false;dialog.dismiss();logout(false);feedback.setText(tr(R.string.password_changed));});
            }catch(Exception e){ui(()->{if(authEpoch!=epoch)return;busy=false;proceed.setEnabled(true);current.setEnabled(true);next.setEnabled(true);confirmation.setEnabled(true);dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);dialog.setCancelable(true);
                result.setText(tr(e instanceof GvApi.ApiException&&((GvApi.ApiException)e).status==400?R.string.password_incorrect:R.string.password_change_unconfirmed));
                if(e instanceof GvApi.ApiException&&((GvApi.ApiException)e).status==401){dialog.dismiss();logout(false);}
            });}});
        });
    }
    private void openBilling(){openBilling("");}
    private void openBilling(String selectedPlan){
        if(!selectedPlan.isEmpty()&&!PlanCatalog.allowedCode(selectedPlan)){feedback.setText(tr(R.string.ui_catalog_unavailable));return;}
        if(!BuildConfig.EXTERNAL_BILLING_ALLOWED){feedback.setText(tr(R.string.store_access_help));return;}
        if(busy||token.isEmpty())return;billingBusy(true);feedback.setText(tr(R.string.billing_opening));
        main.removeCallbacks(billingPoll);int epoch=authEpoch;String accountToken=token;
        android.content.SharedPreferences prefs=getSharedPreferences("gv-ui",MODE_PRIVATE);
        boolean priorPending=prefs.getBoolean("billing-return-pending",false);
        io.submit(()->{try{
            String baseline=prefs.getString("billing-baseline","");
            if(!priorPending){try{baseline=BillingReview.capture(api.call("GET","/api/billing/orders",null,accountToken).object().getJSONArray("orders")).toString();}catch(GvApi.ApiException e){if(e.status==401)throw e;baseline="";}catch(Exception ignored){baseline="";}}
            JSONObject reply=api.call("POST","/api/website/desktop-handoff",new JSONObject(),accountToken).object();
            String ticket=reply.optString("ticket");
            if(!ticket.matches("[A-Za-z0-9_-]{43}")||reply.optInt("expires_in")!=90)throw new IllegalStateException("Invalid website handoff");
            String captured=baseline;
            ui(()->{if(authEpoch!=epoch||!home)return;billingBusy(false);billingAttempts=0;
                // Mark before launching so a fast resume cannot miss the return.
                prefs.edit().putBoolean("billing-return-pending",true).putString("billing-baseline",captured).apply();
                if(open(AppLanguage.website(this,"billing/"+(selectedPlan.isEmpty()?"":"?plan="+Uri.encode(selectedPlan))+"#gv_handoff="+ticket)))feedback.setText(tr(R.string.billing_browser_open));
                else{if(!priorPending)prefs.edit().remove("billing-return-pending").remove("billing-baseline").apply();feedback.setText(tr(R.string.browser_required));}
            });
        }catch(Exception e){ui(()->{if(authEpoch!=epoch||!home)return;billingBusy(false);feedback.setText(tr(R.string.billing_unavailable));if(e instanceof GvApi.ApiException&&((GvApi.ApiException)e).status==401)logout(false);});}});
    }
    private void licenses(){
        String[] names={"AndroidLibXrayLite · LGPL-3.0","GNU GPL-3.0","Xray-core · MPL-2.0",tr(R.string.other_notices),tr(R.string.view_core_source)};
        String[] paths={"LICENSE-AndroidLibXrayLite-LGPL-3.0.txt","LICENSE-GPL-3.0.txt","LICENSE-Xray-core-MPL-2.0.txt","THIRD-PARTY-NOTICES.txt"};
        new AlertDialog.Builder(this).setTitle(tr(R.string.licenses)).setItems(names,(dialog,index)->{
            if(index==4){open("https://github.com/daoshengqin-star/gv-vpn-android/tree/android-0.1.24-26-fdroid-candidate2/engine-reference");return;}
            io.submit(()->{try(java.io.InputStream in=getAssets().open("legal/"+paths[index]);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
                byte[] block=new byte[8192];int n;while((n=in.read(block))!=-1){if(out.size()+n>1024*1024)throw new java.io.IOException("Notice too large");out.write(block,0,n);}
                String notice=new String(out.toByteArray(),java.nio.charset.StandardCharsets.UTF_8);
                ui(()->{ScrollView scroll=new ScrollView(this);TextView t=text(notice,12,false);t.setPadding(dp(18),dp(12),dp(18),dp(12));t.setTextIsSelectable(true);scroll.addView(t);
                    new AlertDialog.Builder(this).setTitle(names[index]).setView(scroll).setPositiveButton(tr(R.string.close),null).show();});
            }catch(Exception e){ui(()->Toast.makeText(this,tr(R.string.license_unavailable),Toast.LENGTH_SHORT).show());}});
        }).setNegativeButton(tr(R.string.close),null).show();
    }
    private void support(){
        if(BuildConfig.EXTERNAL_BILLING_ALLOWED){open(AppLanguage.website(this,"support/"));return;}
        new AlertDialog.Builder(this).setTitle(tr(R.string.help)).setMessage(tr(R.string.store_support_help)+"\n\nglobalvillager@worldcitizenvpn.com")
            .setPositiveButton(tr(R.string.contact_email),(d,w)->contactSupport(tr(R.string.help)))
            .setNeutralButton(tr(R.string.copy_support_email),(d,w)->copySupportEmail()).setNegativeButton(tr(R.string.close),null).show();
    }
    private void deletionRequest(){
        // The consumption-only Play build keeps deletion guidance in the app.
        // The public website has purchase navigation, so it is not a Play help link.
        if(!BuildConfig.EXTERNAL_BILLING_ALLOWED){
            new AlertDialog.Builder(this).setTitle(tr(R.string.delete_account_request)).setMessage(tr(R.string.delete_account_help)+"\n\nglobalvillager@worldcitizenvpn.com")
                .setPositiveButton(tr(R.string.contact_email),(d,w)->contactSupport(tr(R.string.delete_account_request)))
                .setNeutralButton(tr(R.string.copy_support_email),(d,w)->copySupportEmail()).setNegativeButton(tr(R.string.close),null).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle(tr(R.string.delete_account_request)).setMessage(tr(R.string.delete_account_help)+"\n\nglobalvillager@worldcitizenvpn.com")
            .setPositiveButton(tr(R.string.view_deletion_steps),(d,w)->open(AppLanguage.website(this,"account-deletion/")))
            .setNegativeButton(tr(R.string.contact_email),(d,w)->contactSupport(tr(R.string.delete_account_request)))
            .setNeutralButton(tr(R.string.copy_support_email),(d,w)->copySupportEmail()).show();
    }
    private void contactSupport(String subject){
        Uri uri=Uri.parse("mailto:globalvillager@worldcitizenvpn.com?subject="+Uri.encode("Global Villager · "+subject));
        try{startActivity(new Intent(Intent.ACTION_SENDTO,uri));}catch(ActivityNotFoundException e){copySupportEmail();}
    }
    private void copySupportEmail(){getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Global Villager support","globalvillager@worldcitizenvpn.com"));Toast.makeText(this,tr(R.string.support_email_copied),Toast.LENGTH_SHORT).show();}
    private void legal(String pageName){
        if(BuildConfig.EXTERNAL_BILLING_ALLOWED){open(AppLanguage.website(this,pageName+"/"));return;}
        String language=AppLanguage.locale(this).toLanguageTag();String locale=language.startsWith("zh")?(language.contains("Hant")||language.contains("TW")||language.contains("HK")?"zh-hant":"zh"):"en";
        io.submit(()->{try(java.io.InputStream in=getAssets().open("legal/store-"+pageName+"-"+locale+".txt")){
            java.io.ByteArrayOutputStream data=new java.io.ByteArrayOutputStream();byte[] block=new byte[8192];int count;
            while((count=in.read(block))!=-1){if(data.size()+count>1024*1024)throw new java.io.IOException("Policy too large");data.write(block,0,count);}
            String value=new String(data.toByteArray(),java.nio.charset.StandardCharsets.UTF_8);
            ui(()->{ScrollView scroll=new ScrollView(this);TextView content=text(value,14,false);content.setPadding(dp(20),dp(8),dp(20),dp(8));content.setTextIsSelectable(true);scroll.addView(content);
                new AlertDialog.Builder(this).setTitle(tr(pageName.equals("privacy")?R.string.privacy:R.string.terms)).setView(scroll).setPositiveButton(tr(R.string.close),null).show();});
        }catch(Exception e){ui(()->Toast.makeText(this,tr(R.string.license_unavailable),Toast.LENGTH_SHORT).show());}});
    }
    private boolean open(String url){try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));return true;}catch(ActivityNotFoundException e){Toast.makeText(this,tr(R.string.browser_required),Toast.LENGTH_SHORT).show();return false;}}
    private String modeName(ConnectionMode mode){return tr(mode==ConnectionMode.SMART?R.string.mode_smart:R.string.mode_global);}
    private void chooseMode(){
        if(busy){feedback.setText(tr(R.string.routes_loading));return;}
        if(GvVpnService.status.busy()){feedback.setText(tr(R.string.wait_or_cancel));return;}
        AlertDialog modeDialog=new AlertDialog.Builder(this).setTitle(tr(R.string.connection_mode))
            .setSingleChoiceItems(new String[]{tr(R.string.mode_global),tr(R.string.mode_smart)},selectedMode==ConnectionMode.GLOBAL?0:1,(dialog,index)->{
                dialog.dismiss();ConnectionMode next=index==0?ConnectionMode.GLOBAL:ConnectionMode.SMART;
                boolean reconnect=next!=selectedMode&&GvVpnService.status.connected();selectedMode=next;
                if(BuildConfig.DEBUG)android.util.Log.i("GvPhoneQA","mode_selected="+next.value);
                getSharedPreferences("gv-ui",MODE_PRIVATE).edit().putString("mode",next.value).apply();updateSelection();if(reconnect)startVpn();
            }).setNegativeButton(tr(R.string.cancel),null).show();
        modeDialog.getListView().setFilterTouchesWhenObscured(true);
    }
    private void disconnectVpn(){
        if(GvVpnService.systemManaged){backgroundVpn();return;}
        new BackgroundVpnState(this).stop();
        startService(new Intent(this,GvVpnService.class).setAction(GvVpnService.STOP));
    }
    private void backgroundVpn(){
        ScrollView scroll=new ScrollView(this);TextView explanation=text(tr(R.string.background_vpn_help),14,false);
        explanation.setPadding(dp(20),dp(10),dp(20),dp(10));scroll.addView(explanation);
        new AlertDialog.Builder(this).setTitle(tr(R.string.background_vpn)).setView(scroll)
            .setPositiveButton(tr(R.string.system_vpn_settings),(d,w)->{
                try{startActivity(new Intent(android.provider.Settings.ACTION_VPN_SETTINGS));}
                catch(ActivityNotFoundException unsupported){new AlertDialog.Builder(this).setMessage(tr(R.string.system_vpn_settings_missing)).setPositiveButton(tr(R.string.close),null).show();}
            }).setNegativeButton(tr(R.string.close),null).show();
    }
    private void privacyControls(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(20),dp(8),dp(20),dp(8));
        box.addView(text(tr(R.string.privacy_controls_help),14,false));
        CheckBox shield=new CheckBox(this);shield.setFilterTouchesWhenObscured(true);shield.setText(tr(R.string.privacy_guard));
        shield.setChecked(getSharedPreferences("gv-ui",MODE_PRIVATE).getBoolean("privacy-guard",true));box.addView(shield);
        box.addView(text(tr(R.string.privacy_guard_help),13,false));
        new AlertDialog.Builder(this).setTitle(tr(R.string.connection_privacy)).setView(box).setPositiveButton(tr(R.string.close),(d,w)->{
            getSharedPreferences("gv-ui",MODE_PRIVATE).edit().putBoolean("privacy-guard",shield.isChecked()).apply();
        }).setNeutralButton(tr(R.string.release_block),(d,w)->{
            if(GvVpnService.status.blocking())disconnectVpn();
        }).show();
    }
    private String countryName(String code){return switch(code){case ""->tr(R.string.country_auto);case "US"->tr(R.string.country_us);case "JP"->tr(R.string.country_jp);case "SG"->tr(R.string.country_sg);case "HK"->tr(R.string.country_hk);case "TW"->tr(R.string.country_tw);case "KR"->tr(R.string.country_kr);case "GB"->tr(R.string.country_gb);case "DE"->tr(R.string.country_de);case "CA"->tr(R.string.country_ca);case "AU"->tr(R.string.country_au);default->code;};}
    private String formatDuration(long millis){long s=Math.max(0,millis/1000);return String.format(AppLanguage.locale(this),"%d:%02d",s/60,s%60);}
    private String bytes(long n){if(n<1024)return n+" B";if(n<1024*1024)return String.format(AppLanguage.locale(this),"%.1f KB",n/1024.0);return String.format(AppLanguage.locale(this),"%.1f MB",n/(1024.0*1024));}
}
