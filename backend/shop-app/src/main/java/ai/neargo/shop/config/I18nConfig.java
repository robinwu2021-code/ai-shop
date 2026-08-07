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
 * 服务端文案本地化：{@code Accept-Language} → zh-CN / en / ar，回落链 zh-CN（[矩阵 C-17.2]）。
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

    @Bean
    LocaleResolver localeResolver() {
        var resolver = new AcceptHeaderLocaleResolver();
        resolver.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);
        resolver.setSupportedLocales(List.of(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH, Locale.forLanguageTag("ar")));
        return resolver;
    }
}
