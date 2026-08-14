package ai.neargo.shop.message.notify;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.message.entity.MsgSubscribe;
import ai.neargo.shop.message.mapper.MessageMappers.SubscribeMapper;
import ai.neargo.shop.spi.notify.WxSubscribePort;
import ai.neargo.shop.spi.user.UserIdentityPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 订阅消息发送编排：查 openid → 扣额度 → 交给通道。
 *
 * <p><b>三种情况静默跳过，都不是错误</b>：用户没有小程序 openid（纯 H5/App 用户）、
 * 场景没配模板号、额度为零（没授权或已用完）。订阅消息是加速通道，
 * 站内信才是必达的事实记录 —— 跳过的用户在消息中心照样能看到。
 *
 * <p><b>发送失败也吞掉</b>（记 WARN；装饰器已把 FAILED 写进 {@code sys_notify_log}）：
 * 调用方在 Outbox 消费链路里，抛出去会让整条事件反复重试，
 * 而站内信在同一次消费里已经落库 —— 重试只会撞 dedup，白耗投递器。
 *
 * <p><b>先扣额度后发送</b>：反过来（发完再扣）在事件重投的窗口里会发两条真消息给用户。
 * 先扣后发的最坏情况是通道抖动时**少发一条加速通知**，用户损失一次授权 ——
 * 骚扰用户和少提醒一次之间，选后者。
 */
@Component
public class WxSubscribeSender {

    private static final Logger log = LoggerFactory.getLogger(WxSubscribeSender.class);

    private final WxSubscribePort port;
    private final UserIdentityPort identityPort;
    private final SubscribeMapper subscribeMapper;

    public WxSubscribeSender(WxSubscribePort port, UserIdentityPort identityPort,
                             SubscribeMapper subscribeMapper) {
        this.port = port;
        this.identityPort = identityPort;
        this.subscribeMapper = subscribeMapper;
    }

    /**
     * 场景 → 微信模板号。给**额度预检**用（运营端模拟发送要先确认这个用户还有额度，
     * 否则发出去会被微信以 43101 拒，而运营看到的是一条无从下手的通道错误）。
     */
    public String templateIdOf(String scene) {
        return port.templateId(scene);
    }

    /** 到货通知（C-FF-02）。事件链路用默认话术。 */
    public void orderArrived(String userNo, int orderCount, String page) {
        orderArrived(userNo, orderCount, page, null);
    }

    /**
     * 到货通知，带自定义提示语。
     *
     * @param tip 微信模板里 {@code thing2} 那一格（≤20 字）。{@code null} = 用通道默认话术。
     *            运营端的模拟发送走这一条，与事件链路**共用同一条发送路径** ——
     *            两条路的话，「测通了、真发时不通」就会重新变得可能
     */
    public void orderArrived(String userNo, int orderCount, String page, String tip) {
        send(userNo, WxSubscribePort.SCENE_ORDER_ARRIVED,
                openId -> port.sendOrderArrived(openId, orderCount, page, tip));
    }

    /** 退款完成通知。 */
    public void refunded(String userNo, String amountText, String page) {
        refunded(userNo, amountText, page, null);
    }

    /** 带自定义提示语的重载，与 {@link #orderArrived(String, int, String, String)} 对称。 */
    public void refunded(String userNo, String amountText, String page, String tip) {
        send(userNo, WxSubscribePort.SCENE_REFUNDED,
                openId -> port.sendRefunded(openId, amountText, page, tip));
    }

    private void send(String userNo, String scene, java.util.function.Consumer<String> call) {
        String templateId = port.templateId(scene);
        if (templateId == null || templateId.isBlank()) {
            return;   // 场景没配模板：功能未开通，不是错误
        }
        var openId = identityPort.wxOpenIdMp(userNo);
        if (openId.isEmpty()) {
            return;   // 没从小程序登录过，没有可发的地址
        }
        if (!consumeQuota(userNo, templateId)) {
            log.debug("[wxsub] 无额度跳过 userNo={} scene={}", userNo, scene);
            return;
        }
        try {
            call.accept(openId.get());
        } catch (RuntimeException e) {
            // 装饰器已留痕 FAILED；这里只保证事件消费不被通道抖动拖进重试
            log.warn("[wxsub] 发送失败（已留痕，不重试）userNo={} scene={}: {}",
                    userNo, scene, e.getMessage());
        }
    }

    /**
     * 原子扣减：{@code quota > 0} 进 WHERE 条件，并发重投时只有一个能扣到。
     * 走 setSql 而不是读改写 —— 读改写在两个投递线程之间会把 1 扣成 -1 或漏扣。
     */
    private boolean consumeQuota(String userNo, String templateId) {
        return DataScopeContext.executeWithoutScope(() ->
                subscribeMapper.update(null, Wrappers.<MsgSubscribe>lambdaUpdate()
                        .setSql("quota = quota - 1")
                        .eq(MsgSubscribe::getUserNo, userNo)
                        .eq(MsgSubscribe::getTemplateId, templateId)
                        .gt(MsgSubscribe::getQuota, 0))) > 0;
    }
}
