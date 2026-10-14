package ai.neargo.shop.notify.port;

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
 * <p>模板号给固定值 {@code STUB_TPL_*}：额度记账（{@code notify_subscribe}）按模板号对账，
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

    @Override
    public SendResult sendNewGoods(String openId, String goodsTitle, String goodsDesc,
                                   long onSaleAt, String page, String tip) {
        // 把 tip 记进摘要，理由同上一条：一次授权只够一条，
        // 「引导续订那句话有没有传下去」在桩世界里也要看得见
        return record(openId, SCENE_NEW_GOODS,
                "新品「" + goodsTitle + "」" + (tip == null || tip.isBlank() ? "" : "/" + tip)
                        + " -> " + page);
    }

    @Override
    public SendResult sendElecQuoted(String openId, String rfqNo, String summary, String resultText, String page,
                                     String tip) {
        return record(openId, SCENE_ELEC_QUOTED, "询价" + rfqNo + " " + summary + " " + resultText + " -> " + page);
    }

    @Override
    public SendResult sendWaybill(String openId, String scene, WaybillNotice n, String page) {
        return record(openId, scene, n.carrierName() + " " + n.waybillNo() + " " + n.statusText()
                + (n.tip() == null || n.tip().isBlank() ? "" : "/" + n.tip()) + " -> " + page);
    }

    @Override
    public SendResult sendFielded(String openId, String scene, java.util.Map<String, String> values, String page) {
        // 语义键按字母序拼进摘要：测试要能断言「理由 / 时限 / 退款那句真的传下去了」
        return record(openId, scene, new java.util.TreeMap<>(values) + " -> " + page);
    }

    private synchronized SendResult record(String openId, String scene, String summary) {
        sent.addLast(new Sent(openId, scene, summary));
        while (sent.size() > KEEP) {
            sent.removeFirst();
        }
        // openId 是能长期指向某个人的标识，日志方案 §7 把它与手机号同档处理。
        // 这一条是 **info**：桩一开就打，比 sms 桩那条（debug）更容易进到留存里。
        log.info("[wxsub-stub] {} openId={} {}", scene, maskOpenId(openId), summary);
        return SendResult.of(null, templateId(scene));
    }

    /** openId 打码：留头 6 尾 4，够在日志里认出是同一个人，又不是完整标识。 */
    private static String maskOpenId(String openId) {
        if (openId == null || openId.length() < 12) {
            return "***";
        }
        return openId.substring(0, 6) + "****" + openId.substring(openId.length() - 4);
    }

    public synchronized List<Sent> sent() {
        return List.copyOf(sent);
    }

    public synchronized void clear() {
        sent.clear();
    }
}
