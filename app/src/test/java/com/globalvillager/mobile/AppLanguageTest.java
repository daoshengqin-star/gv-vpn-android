package com.globalvillager.mobile;

import org.junit.Test;
import java.util.Locale;
import static org.junit.Assert.*;

public class AppLanguageTest {
    @Test public void traditionalRegionsUseTraditionalChinese(){
        for(String tag:new String[]{"zh-TW","zh-HK","zh-MO","zh-Hant-CN"})assertEquals("zh-Hant",AppLanguage.supported(Locale.forLanguageTag(tag)));
    }
    @Test public void explicitSimplifiedScriptOverridesRegion(){
        assertEquals("zh-Hans",AppLanguage.supported(Locale.forLanguageTag("zh-Hans-HK")));
        assertEquals("zh-Hans",AppLanguage.supported(Locale.forLanguageTag("zh-CN")));
        assertEquals("zh-Hans",AppLanguage.supported(Locale.forLanguageTag("zh-SG")));
    }
    @Test public void allEnglishRegionsShareTheEnglishTranslation(){
        for(String tag:new String[]{"en-US","en-GB","en-AU"})assertEquals("en",AppLanguage.supported(Locale.forLanguageTag(tag)));
    }
    @Test public void unsupportedLanguageHasAnEnglishFallback(){
        assertEquals("en",AppLanguage.supported(Locale.GERMAN));
        assertEquals("en",AppLanguage.supported(Locale.ROOT));
    }
}
