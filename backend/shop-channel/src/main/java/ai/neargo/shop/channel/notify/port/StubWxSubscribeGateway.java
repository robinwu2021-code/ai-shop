package ai.neargo.shop.channel.notify.port;

import ai.neargo.shop.spi.notify.SendResult;
import ai.neargo.shop.spi.notify.WxSubscribePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 订阅消息桩：不真发，只记下来。**默认启用**（{@code shop.wx.subscribe.stub} 默认跟随
 * {@code shop.wx.stub}，也就是 true），
 * 理由同 {@link StubSmsGateway} —— 默认真发意味着本地跑一次测试就在骚扰真实用户的微信。
 *
 * <p>模板号给固定值 {@code STUB_TPL_*}：额度记账（{@code msg_subscribe}）按模板号对账，
 * 桩世界里前端上报授权、后端查扣额度用的是同一套假模板号，链路照样闭环可测。
 */
@Component("wxSubscribeGateway")
@ConditionalOnProperty(name = "shop.wx.subscribe.stub", havingValue = "true", matchIfMissing = true)
public class StubWxSubscribeGateway implements WxSubscribePort {

    private static final Logger log = LoggerFactory.getLogger(StubWxSubscribeGateway.class);

    /** 保留最近若干条，够测试断言即可。 */
    private static final int KEEP = 200;

    private final Deque<Sent> sent = new ArrayDeque<>();

    public record Sent(String openId, String scene, String summary) {
    }

    @Override
    public String templateId(String scene) {
        return "STUB_TPL_" + scene;
    }

    @Override
    public SendResult sendOrderArrived(String openId, int orderCount, String page, String tip) {
        // 提示语进桩记录：测试要能断言「运营填的那句真的传下去了」
        return record(openId, SCENE_ORDER_ARRIVED,
                orderCount + "件到货 -> " + page + (tip == null ? "" : " | " + tip));
    }

    @Override
    public SendResult sendRefunded(String openId, String amountText, String page, String tip) {
        // 把 tip 记进摘要：桩不记的话，「话术改了没生效」在桩世界里看不出来
        return record(openId, SCENE_REFUNDED,
                "退款" + amountText + (tip == null || tip.isBlank() ? "" : "/" + tip) + " -> " + page);
    }

    private synchronized SendResult record(String openId, String scene, String summary) {
        sent.addLast(new Sent(openId, scene, summary));
        while (sent.size() > KEEP) {
            sent.removeFirst();
        }
        log.info("[wxsub-stub] {} openId={} {}", scene, openId, summary);
        return SendResult.of(null, templateId(scene));
    }

    public synchronized List<Sent> sent() {
        return List.copyOf(sent);
    }

    public synchronized void clear() {
        sent.clear();
    }
}
