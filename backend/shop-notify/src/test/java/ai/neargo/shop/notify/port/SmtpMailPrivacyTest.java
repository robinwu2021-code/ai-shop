package ai.neargo.shop.notify.port;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

import ai.neargo.shop.spi.notify.MailPort;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class SmtpMailPrivacyTest {
    @Test
    void serializesPdfAsAnAttachmentWithItsOriginalBytes() {
        var gateway = new SmtpMailGateway("smtp.example.com", 587, "sender@example.com",
                "test-only-password", "sender@example.com", "TLSv1.2");
        byte[] pdf = "%PDF-1.7 original bytes".getBytes(StandardCharsets.US_ASCII);
        try (var transport = mockStatic(Transport.class)) {
            transport.when(() -> Transport.send(any(Message.class))).thenAnswer(call -> {
                MimeMessage message = call.getArgument(0);
                var wire = new ByteArrayOutputStream();
                message.writeTo(wire);
                var parsed = new MimeMessage(Session.getInstance(new Properties()),
                        new ByteArrayInputStream(wire.toByteArray()));
                assertThat(parsed.isMimeType("multipart/mixed")).isTrue();
                var parts = (Multipart) parsed.getContent();
                assertThat(parts.getCount()).isEqualTo(2);
                assertThat(parts.getBodyPart(0).getContent()).isEqualTo("請查看附件");
                var attachment = parts.getBodyPart(1);
                assertThat(attachment.getDisposition()).isEqualToIgnoringCase("attachment");
                assertThat(attachment.isMimeType("application/pdf")).isTrue();
                assertThat(attachment.getFileName()).isEqualTo("HR22644556-proof.pdf");
                try (var in = attachment.getInputStream()) {
                    assertThat(in.readAllBytes()).isEqualTo(pdf);
                }
                return null;
            });
            assertThat(gateway.sendAttachment("person@example.com", "發票", "請查看附件",
                    new MailPort.Attachment("HR22644556-proof.pdf", "application/pdf", pdf),
                    "INVOICE_DELIVERY", null).providerMsgId()).isNotBlank();
            transport.verify(() -> Transport.send(any(Message.class)));
        }
    }

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
