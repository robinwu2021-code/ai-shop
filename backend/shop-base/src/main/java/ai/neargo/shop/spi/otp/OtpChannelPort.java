package ai.neargo.shop.spi.otp;

import ai.neargo.shop.common.otp.service.OtpSendService.Channel;
import java.time.Duration;
import java.util.Map;

/** Channel OTP lifecycle including existing controlled test identities; never makes captcha decisions. */
public interface OtpChannelPort {
    void requireAvailable(String scene, Channel channel, String recipient);
    DeliveryResult deliver(DeliveryCommand command);
    boolean verifyAndConsume(VerificationCommand command);
    record DeliveryCommand(String scene, Channel channel, String recipient, String storageKey,
                           Duration ttl, String locale, String logicalTemplateKey, Map<String, String> parameters) {}
    record VerificationCommand(String scene, Channel channel, String recipient, String storageKey, String code) {}
    record DeliveryResult(long expiresInSeconds) {}
}
