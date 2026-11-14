package ai.neargo.shop.config;

import ai.neargo.shop.auth.captcha.CaptchaProofService;
import ai.neargo.shop.auth.captcha.JdbcCaptchaStore;
import ai.neargo.shop.common.otp.policy.InMemoryOtpFrequency;
import ai.neargo.shop.common.otp.policy.OtpScenePolicies;
import ai.neargo.shop.common.otp.policy.OtpSettings;
import ai.neargo.shop.common.otp.service.OtpSendService;
import ai.neargo.shop.common.otp.service.impl.OtpSendServiceImpl;
import ai.neargo.shop.common.ratelimit.OtpSendGuard;
import ai.neargo.shop.spi.otp.OtpChannelPort;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OtpSettings.class)
public class OtpConfiguration {
    @Bean JdbcCaptchaStore jdbcCaptchaStore(JdbcTemplate jdbc,
            @Qualifier("transactionManager") PlatformTransactionManager transactions) {
        return new JdbcCaptchaStore(jdbc, transactions, Clock.systemUTC());
    }
    @Bean CaptchaProofService captchaProofService(JdbcCaptchaStore store) { return new CaptchaProofService(store); }
    @Bean OtpScenePolicies otpScenePolicies(OtpSettings settings,
            @Value("${shop.auth.captcha.enabled:false}") boolean enabled) { return new OtpScenePolicies(settings, enabled); }
    @Bean InMemoryOtpFrequency otpFrequency(OtpSettings settings) {
        return new InMemoryOtpFrequency(Clock.systemUTC(), settings.getMaximumKeys(), settings.getPlatformHourly(), settings.getPlatformDaily());
    }
    @Bean OtpSendService otpSendService(OtpScenePolicies policies, InMemoryOtpFrequency frequency,
            CaptchaProofService proofs, OtpSendGuard guard, OtpChannelPort channel) {
        return new OtpSendServiceImpl(policies, frequency, proofs, guard, channel);
    }
}
