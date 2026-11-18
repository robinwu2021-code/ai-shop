package ai.neargo.shop.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.neargo.shop.spi.user.MerchantQueryPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Locale;

/**
 * 请求语言解析（TDD-子行业与能力端验收 B3，166 冒烟发现）：繁体请求要解析成繁体，
 * 否则建店页的行业 / 子行业名永远是简体。其余语言与改动前一致。
 */
class I18nConfigTest {
    private final org.springframework.web.servlet.LocaleResolver resolver = new I18nConfig().localeResolver();

    private Locale resolve(String header) {
        var request = new MockHttpServletRequest();
        if (header != null) request.addHeader("Accept-Language", header);
        return resolver.resolveLocale(request);
    }

    @Test
    @DisplayName("繁体：zh-TW / zh-Hant / zh-Hant-TW / zh-HK / zh-MO 都解析成繁体")
    void traditionalChineseRequestsResolveToTraditional() {
        for (String tag : new String[] {"zh-TW", "zh-Hant", "zh-Hant-TW", "zh-HK", "zh-MO", "zh-TW,zh;q=0.9,en;q=0.8"}) {
            assertEquals(Locale.TRADITIONAL_CHINESE, resolve(tag), tag);
        }
    }

    @Test
    @DisplayName("其余照旧：简体、英文、阿拉伯文、没带头、未知语言")
    void everythingElseIsUnchanged() {
        assertEquals(Locale.SIMPLIFIED_CHINESE, resolve("zh-CN"));
        assertEquals(Locale.SIMPLIFIED_CHINESE, resolve("zh"));
        assertEquals(Locale.SIMPLIFIED_CHINESE, resolve("zh-SG"));
        assertEquals(Locale.SIMPLIFIED_CHINESE, resolve("zh-Hans-CN"));
        assertEquals(Locale.ENGLISH, resolve("en-US"));
        assertEquals(Locale.forLanguageTag("ar"), resolve("ar"));
        assertEquals(Locale.SIMPLIFIED_CHINESE, resolve(null));
        assertEquals(Locale.SIMPLIFIED_CHINESE, resolve("ja-JP"));
    }

    @Test
    @DisplayName("端到端口径：繁体请求下行业名取繁体")
    void resolvedTraditionalLocalePicksTheTraditionalIndustryName() {
        var breakfast = new MerchantQueryPort.IndustryRow("FOOD", "FOOD_CHINESE", "FOOD", "中式餐馆", "中式餐館",
                "Chinese restaurant", 20);
        assertEquals("中式餐館", breakfast.displayName(resolve("zh-TW")));
        assertEquals("中式餐馆", breakfast.displayName(resolve("zh-CN")));
    }
}
