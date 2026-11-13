package ai.neargo.shop.config;

import ai.neargo.shop.auth.captcha.BizCaptchaService;
import ai.neargo.shop.auth.captcha.JdbcCaptchaStore;
import ai.neargo.shop.auth.captcha.TianaiSliderEngine;

import java.time.Clock;
import ai.neargo.shop.user.service.EmailOtpService;
import ai.neargo.shop.auth.captcha.impl.BizCaptchaServiceImpl;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@Profile("api")
public class BizCaptchaConfiguration {
    @Bean BizCaptchaService bizCaptchaService(JdbcTemplate jdbc,
            @Qualifier("transactionManager") PlatformTransactionManager transactions, ObjectMapper json,
            @Value("${shop.auth.captcha.enabled:false}") boolean enabled) {
        return new BizCaptchaServiceImpl(new JdbcCaptchaStore(jdbc, transactions, Clock.systemUTC()),
                new TianaiSliderEngine(), json, enabled, EmailOtpService::normalize);
    }
}
