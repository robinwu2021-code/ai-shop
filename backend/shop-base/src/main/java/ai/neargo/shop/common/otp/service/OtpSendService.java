package ai.neargo.shop.common.otp.service;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

/** Platform OTP capability. Callers retain business authorization and state transitions. */
public interface OtpSendService {
    OtpSendResult send(@Valid SendCommand command, @Valid OtpContext context);
    boolean verifyAndConsume(@Valid VerifyCommand command, @Valid OtpContext context);
    enum Channel { SMS, EMAIL }
    record SendCommand(@NotBlank String scene, @NotNull Channel channel,
                       @NotBlank @Size(max=191) String recipient,
                       @Size(max=191) String businessKey, @Size(max=16) String locale,
                       Map<String, String> parameters, @Size(max=128) String captchaToken) implements Recipient {}
    record VerifyCommand(@NotBlank String scene, @NotNull Channel channel,
                         @NotBlank @Size(max=191) String recipient,
                         @Size(max=191) String businessKey, @NotBlank @Size(max=32) String code) implements Recipient {}
    /** Channel-specific input format stays in Bean Validation at the public service boundary. */
    interface Recipient {
        Channel channel();
        String recipient();
        @Email @Size(max=191)
        default String getEmailRecipient() { return channel() == Channel.EMAIL ? recipient() : null; }
        @Size(max=32)
        default String getSmsRecipient() { return channel() == Channel.SMS ? recipient() : null; }
    }
    record OtpContext(@NotBlank String entryCode, @Size(max=64) String clientIp,
                      @Size(max=64) String senderKey) {}
    record OtpSendResult(long expiresInSeconds, int resendAfterSeconds) {}
}
