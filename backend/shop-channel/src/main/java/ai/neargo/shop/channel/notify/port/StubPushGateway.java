package ai.neargo.shop.channel.notify.port;

import ai.neargo.shop.spi.notify.PushPort;
import ai.neargo.shop.spi.notify.SendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 推送桩：不真推，只记下来。**默认启用**（{@code shop.push.stub} 默认 true），
 * 理由同 {@link StubSmsGateway} —— 默认真推意味着本地跑一次测试就在震别人的手机，
 * 而推送比短信更打扰：它会在夜里把人吵醒。
 */
@Component("pushGateway")
@ConditionalOnProperty(name = "shop.push.stub", havingValue = "true", matchIfMissing = true)
public class StubPushGateway implements PushPort {

    private static final Logger log = LoggerFactory.getLogger(StubPushGateway.class);

    private static final int KEEP = 200;

    private final Deque<Sent> sent = new ArrayDeque<>();

    /** @param level NORMAL / RING —— 测试要断言「新订单是不是响铃级别」 */
    public record Sent(String clientId, String title, String link, String level) {
    }

    @Override
    public synchronized SendResult push(String clientId, String title, String body,
                                        String link, String level) {
        sent.addLast(new Sent(clientId, title, link, level));
        while (sent.size() > KEEP) {
            sent.removeFirst();
        }
        log.info("[push-stub] {} cid={} {} -> {}", level, clientId, title, link);
        return SendResult.of(null, level);
    }

    public synchronized List<Sent> sent() {
        return List.copyOf(sent);
    }

    public synchronized void clear() {
        sent.clear();
    }
}
