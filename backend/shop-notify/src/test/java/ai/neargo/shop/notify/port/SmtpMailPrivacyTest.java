package ai.neargo.shop.notify.port;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

import ai.neargo.shop.spi.notify.MailPort;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Transport;
import org.junit.jupiter.api.Test;

class SmtpMailPrivacyTest {
    @Test
    void failureLogMessageDoesNotContainRecipientOrCode() {
        var gateway = new SmtpMailGateway("smtp.example.com", 587, "sender@example.com",
                "test-only-password", "sender@example.com", "TLSv1.2");
        var providerError = new MessagingException("rejected person@example.com code123456");
        try (var transport = mockStatic(Transport.class)) {
            transport.when(() -> Transport.send(any(Message.class))).thenThrow(providerError);
            assertThatThrownBy(() -> gateway.send("person@example.com", "Sign in", "code123456"))
                    .isInstanceOf(MailPort.MailException.class)
                    .hasMessage("邮件发送失败")
                    .hasCause(providerError);
        }
    }
}
