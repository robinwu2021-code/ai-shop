package ai.neargo.shop.channel.notify;

import ai.neargo.shop.spi.notify.MailPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 邮件桩：不真发，只记下来。
 *
 * <p>它让「运营端密码改走邮件」这件事**在拿到邮箱密码之前就能做完并测完** ——
 * 记下「发给谁、发了什么」，就足以断言接口响应里不再有明文密码。
 */
@Component
@ConditionalOnProperty(name = "shop.mail.stub", havingValue = "true", matchIfMissing = true)
public class StubMailGateway implements MailPort {

    private static final Logger log = LoggerFactory.getLogger(StubMailGateway.class);

    private static final int KEEP = 200;

    private final Deque<Sent> sent = new ArrayDeque<>();

    public record Sent(String to, String subject, String body) {
    }

    @Override
    public void send(String to, String subject, String body) {
        synchronized (sent) {
            sent.addLast(new Sent(to, subject, body));
            while (sent.size() > KEEP) {
                sent.pollFirst();
            }
        }
        // 正文可能含一次性密码或重置令牌，**不进日志**，只记收件人与主题
        log.debug("[mail-stub] to={} subject={}", to, subject);
    }

    public Sent last() {
        synchronized (sent) {
            return sent.peekLast();
        }
    }

    public List<Sent> all() {
        synchronized (sent) {
            return List.copyOf(sent);
        }
    }

    public void clear() {
        synchronized (sent) {
            sent.clear();
        }
    }
}
