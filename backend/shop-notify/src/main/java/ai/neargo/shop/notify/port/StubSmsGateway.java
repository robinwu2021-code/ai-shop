package ai.neargo.shop.notify.port;

import ai.neargo.shop.spi.notify.SendResult;
import ai.neargo.shop.spi.notify.SmsPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 短信桩：不真发，只记下来。
 *
 * <p><b>默认启用</b>（{@code shop.sms.stub} 默认 true），与支付通道相反 ——
 * 支付的桩默认关闭，因为「假装支付成功」是资金事故；短信反过来：
 * 默认发真短信意味着**本地跑一次测试就在花钱**，而且会真的骚扰到测试手机号。
 *
 * <p><b>为什么记进内存而不是只打日志</b>：测试要断言「发给谁、发了什么」。
 * 靠日志断言是不可靠的（缓冲、轮转、并发混叠），而这批改造里
 * 「码有没有真的发出去」正是要验的东西。
 */
@Component("smsGateway")
@ConditionalOnProperty(name = "shop.sms.stub", havingValue = "true", matchIfMissing = true)
public class StubSmsGateway implements SmsPort {

    private static final Logger log = LoggerFactory.getLogger(StubSmsGateway.class);

    /** 保留最近若干条，够测试断言即可 —— 无上限的话长跑的实例会把内存吃掉 */
    private static final int KEEP = 200;

    private final Deque<Sent> sent = new ArrayDeque<>();

    /** @param code 验证码明文。**只在桩里保留** */
    public record Sent(String phone, String code) {
    }

    @Override
    public SendResult sendOtp(String phone, String code) {
        synchronized (sent) {
            sent.addLast(new Sent(phone, code));
            while (sent.size() > KEEP) {
                sent.pollFirst();
            }
        }
        /*
         * **debug 而不是 info**：验证码明文进日志等于把它交给日志采集、转发与留存
         * （安全整改方案 §2.4 点名的两处之一）。本地联调把这个包的级别调到 debug 即可。
         */
        log.debug("[sms-stub] otp to {} = {}", phone, code);
        return SendResult.none();
    }

    /** 供测试断言：最近发出的一条。 */
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
