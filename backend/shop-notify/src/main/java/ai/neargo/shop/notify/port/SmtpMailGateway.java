package ai.neargo.shop.notify.port;

import ai.neargo.shop.spi.notify.MailPort;
import ai.neargo.shop.spi.notify.SendResult;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * SMTP 邮件（Office365）。消费方是**运营端密码交付**。
 *
 * <p><b>⚠️ 发件地址必须等于认证账号</b>，除非在 M365 后台授了「发送为（Send As）」权限。
 * 实测（2026-08-13）：用 {@code platform@neargo.ai} 认证、以 {@code system@neargo.ai}
 * 发信，认证**成功**而投递被拒：
 * <pre>554 5.2.252 SendAsDenied; platform@neargo.ai not allowed to send as system@neargo.ai</pre>
 * <b>认证与发件权限是两件事</b> —— 只做登录探测会以为通道没问题，等真发信才炸。
 * 所以这里在启动时就把两者不一致的情况打成 WARN。
 *
 * <p><b>邮箱开了 MFA 时普通密码不可用</b>，要用「应用密码」或改走 OAuth2。
 *
 * <p><b>用 jakarta.mail 而不是 JavaMailSender</b>：只需要 Session + Transport，
 * 且本地仓里 {@code spring-boot-starter-mail} 的版本与 Boot BOM 对不上（离线构建会挂）。
 */
@Component("mailGateway")
@ConditionalOnProperty(name = "shop.mail.stub", havingValue = "false")
public class SmtpMailGateway implements MailPort {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailGateway.class);

    private final Session session;
    private final String from;

    public SmtpMailGateway(@Value("${shop.mail.host:smtp.office365.com}") String host,
                           @Value("${shop.mail.port:587}") int port,
                           @Value("${shop.mail.username:}") String username,
                           @Value("${shop.mail.password:}") String password,
                           @Value("${shop.mail.from:}") String from,
                           @Value("${shop.mail.protocols:TLSv1.2}") String protocols) {
        require(username, "MAIL_USERNAME");
        require(password, "MAIL_PASSWORD（若邮箱开了 MFA，这里要填「应用密码」而不是登录密码）");

        this.from = (from == null || from.isBlank()) ? username : from;
        if (!this.from.equalsIgnoreCase(username)) {
            log.warn("[mail] 发件地址 {} 与认证账号 {} 不一致 —— Office365 需要在后台授"
                            + "「发送为」权限，否则**认证会成功而投递被拒**（554 SendAsDenied）。"
                            + "已实测过一次，别再踩。",
                    this.from, username);
        }

        Properties props = new Properties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(port));
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.starttls.required", "true");
        props.put("mail.smtp.ssl.protocols", protocols);
        /*
         * **EHLO 用发件域，而不是本机 IP。**
         *
         * <p>不设这一项时 JavaMail 拿本机地址顶上，于是两处都变成私网 IP：
         * EHLO 报的名字，以及 Message-ID 的域部分（实测收到过
         * {@code <...@[10.221.117.8]>}）。两者都是垃圾邮件过滤器的扣分项 ——
         * 正规发件方不会用 IP 字面量自报家门。
         *
         * <p>症状很难查：SMTP 返回成功、发送记录里有 Message-ID、
         * **但收件人只在垃圾箱里找得到**，而链路上每一步看起来都是对的。
         */
        props.put("mail.smtp.localhost", domainOf(from));
        /*
         * **Message-ID 的域也要是发件域。**
         *
         * <p>上面那行只管 EHLO —— Message-ID 是 JavaMail 另外生成的
         * （{@code UniqueValue.getUniqueMessageIDValue}），它看的是 {@code mail.from}，
         * 取不到才退回本机地址。实测第一版只设了 localhost，记录里的 ID 仍是
         * {@code <...@[10.221.117.8]>}，白改了一半。
         *
         * <p>这一列不只是好看：M365 的邮件跟踪按 Message-ID 查，
         * 一个 IP 字面量的 ID 在那里很难对上。
         */
        props.put("mail.from", from);
        // 三个超时都要设：不设的话通道不可达时线程会挂很久，而调用方是同步等待的
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "15000");
        props.put("mail.smtp.writetimeout", "15000");

        this.session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(username, password);
            }
        });
        log.info("[mail] SMTP 已启用 {}:{} from={}", host, port, this.from);
    }

    private static void require(String v, String envName) {
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(
                    "邮件通道已开启（shop.mail.stub=false）但缺少配置：" + envName
                            + " —— 直接失败而不是退回桩：退回桩会让运营端「初始密码已发送」"
                            + "的提示照常出现，而新同事永远收不到那封信");
        }
    }

    /** 取发件地址的域部分。取不到就退回一个可解析的常量 —— 总好过报 IP。 */
    private static String domainOf(String address) {
        int at = address == null ? -1 : address.indexOf('@');
        return at > 0 && at < address.length() - 1 ? address.substring(at + 1) : "localhost";
    }

    @Override
    public SendResult send(String to, String subject, String body) {
        try {
            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(from));
            msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            msg.setSubject(subject, StandardCharsets.UTF_8.name());
            // 纯文本：密码与重置链接不需要排版，而 HTML 邮件更容易被判成垃圾邮件
            msg.setText(body, StandardCharsets.UTF_8.name());
            msg.saveChanges();
            Transport.send(msg);
            return SendResult.of(msg.getMessageID());
        } catch (jakarta.mail.AuthenticationFailedException e) {
            throw new MailException("SMTP 认证失败 —— 若邮箱开了 MFA，需要用应用密码", e);
        } catch (Exception e) {
            /*
             * **不吞**：吞掉会让 createStaff 以为邮件发出去了，于是留下一个
             * 「已建号但没人知道密码」的账号 —— 它看起来正常，只是永远没人登得进去。
             */
            throw new MailException("邮件发送失败：" + e.getMessage(), e);
        }
    }
}
