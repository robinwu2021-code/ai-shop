package ai.neargo.shop.config;

import ai.neargo.shop.auth.captcha.BizCaptchaService;
import ai.neargo.shop.auth.captcha.JdbcCaptchaStore;
import ai.neargo.shop.auth.captcha.TianaiSliderEngine;

import ai.neargo.shop.user.service.EmailOtpService;
import ai.neargo.shop.auth.captcha.impl.BizCaptchaServiceImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@Profile("api")
public class BizCaptchaConfiguration {
    @Bean BizCaptchaService bizCaptchaService(JdbcCaptchaStore store, ObjectMapper json,
            @Value("${shop.auth.captcha.enabled:false}") boolean enabled) {
        return new BizCaptchaServiceImpl(store,
                new TianaiSliderEngine(), json, enabled, EmailOtpService::normalize);
    }
}
