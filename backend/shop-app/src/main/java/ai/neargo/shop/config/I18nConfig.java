package ai.neargo.shop.config;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * 服务端文案本地化：{@code Accept-Language} → zh-CN / zh-TW / en / ar，回落链 zh-CN（[矩阵 C-17.2]）。
 *
 * <p>回落到 zh-CN 而不是系统默认 Locale：服务器 Locale 取决于容器镜像，
 * 同一份代码在不同环境会回落到不同语言 —— 这类问题只在海外环境才暴露。
 */
@Configuration
public class I18nConfig {

    @Bean
    MessageSource messageSource() {
        var source = new ReloadableResourceBundleMessageSource();
        source.setBasename("classpath:i18n/messages");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);
        source.setFallbackToSystemLocale(false);
        return source;
    }

    /**
     * 繁体请求（{@code zh-TW / zh-HK / zh-MO}，或书写体系 {@code Hant}）解析成 {@link Locale#TRADITIONAL_CHINESE}：
     * 内置的按语言 + 地区匹配认不出 {@code zh-Hant-TW} 这类写法，而 {@code zh_TW} 不在列表里时一律回落简体，
     * 台湾门店的建店页行业名因此永远是简体（TDD-子行业与能力端验收 B3，166 冒烟发现）。
     * 没有 {@code messages_zh_TW}：服务端文案照旧回落 {@code messages.properties}（简体），与改动前一致。
     */
    @Bean
    LocaleResolver localeResolver() {
        var resolver = new AcceptHeaderLocaleResolver() {
            @Override
            public Locale resolveLocale(jakarta.servlet.http.HttpServletRequest request) {
                if (request.getHeader("Accept-Language") != null) {
                    for (var range : Locale.LanguageRange.parse(request.getHeader("Accept-Language"))) {
                        var locale = Locale.forLanguageTag(range.getRange());
                        if (!"zh".equals(locale.getLanguage())) break;
                        if ("Hant".equalsIgnoreCase(locale.getScript())
                                || TRADITIONAL_REGIONS.contains(locale.getCountry())) {
                            return Locale.TRADITIONAL_CHINESE;
                        }
                        break;
                    }
                }
                return super.resolveLocale(request);
            }
        };
        resolver.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);
        resolver.setSupportedLocales(List.of(Locale.SIMPLIFIED_CHINESE, Locale.TRADITIONAL_CHINESE, Locale.ENGLISH,
                Locale.forLanguageTag("ar")));
        return resolver;
    }

    private static final java.util.Set<String> TRADITIONAL_REGIONS = java.util.Set.of("TW", "HK", "MO");
}
