package com.globalvillager.mobile;

import android.app.LocaleManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;
import java.util.Locale;

/** Android 13 uses the system per-app preference; older phones store only a language tag. */
public final class AppLanguage {
    public static final String SYSTEM="system";
    public static final String[] CHOICES={SYSTEM,"zh-Hans","zh-Hant","en"};
    private AppLanguage(){}
    public static String selection(Context context){
        if(Build.VERSION.SDK_INT>=33){
            LocaleList selected=context.getSystemService(LocaleManager.class).getApplicationLocales();
            return selected.isEmpty()?SYSTEM:supported(selected.get(0));
        }
        String saved=context.getSharedPreferences("gv-ui",Context.MODE_PRIVATE).getString("language",SYSTEM);
        return valid(saved)?saved:SYSTEM;
    }
    private static boolean valid(String tag){for(String allowed:CHOICES)if(allowed.equals(tag))return true;return false;}
    public static String supported(Locale locale){
        if("zh".equals(locale.getLanguage())){
            String script=locale.getScript(),region=locale.getCountry();
            if("Hant".equalsIgnoreCase(script))return "zh-Hant";
            if("Hans".equalsIgnoreCase(script))return "zh-Hans";
            return "TW".equals(region)||"HK".equals(region)||"MO".equals(region)?"zh-Hant":"zh-Hans";
        }
        return "en";
    }
    public static Locale locale(Context context){
        String tag=selection(context);
        if(!SYSTEM.equals(tag))return Locale.forLanguageTag(tag);
        LocaleList system=Build.VERSION.SDK_INT>=33?context.getSystemService(LocaleManager.class).getSystemLocales():Resources.getSystem().getConfiguration().getLocales();
        // Match the first supported system language; use English when none is supported.
        for(int i=0;i<system.size();i++){
            Locale candidate=system.get(i);
            if("zh".equals(candidate.getLanguage())||"en".equals(candidate.getLanguage()))return Locale.forLanguageTag(supported(candidate));
        }
        return Locale.ENGLISH;
    }
    public static String renderKey(Context context){return selection(context)+":"+locale(context).toLanguageTag();}
    public static String text(Context context,int id,Object... args){
        Configuration config=new Configuration(context.getResources().getConfiguration());
        config.setLocales(new LocaleList(locale(context)));
        return context.createConfigurationContext(config).getString(id,args);
    }
    public static void select(Context context,String tag){
        if(!valid(tag))throw new IllegalArgumentException("Unsupported application language");
        if(Build.VERSION.SDK_INT>=33){
            context.getSystemService(LocaleManager.class).setApplicationLocales(SYSTEM.equals(tag)?LocaleList.getEmptyLocaleList():LocaleList.forLanguageTags(tag));
        }else context.getSharedPreferences("gv-ui",Context.MODE_PRIVATE).edit().putString("language",tag).apply();
    }
    public static String name(Context context){
        return switch(selection(context)){
            case "zh-Hans"->"简体中文";case "zh-Hant"->"繁體中文";case "en"->"English";
            default->text(context,R.string.language_system);
        };
    }
    public static String website(Context context,String path){
        String tag=locale(context).toLanguageTag();
        return "https://worldcitizenvpn.com/"+("zh-Hant".equals(tag)?"zh-hant/":"zh-Hans".equals(tag)?"zh/":"")+path;
    }
}
