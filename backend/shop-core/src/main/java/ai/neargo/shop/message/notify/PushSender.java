package ai.neargo.shop.message.notify;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.message.entity.MsgPushToken;
import ai.neargo.shop.message.mapper.MessageMappers.PushTokenMapper;
import ai.neargo.shop.spi.notify.PushPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * App 推送编排：查设备 → 逐台推。
 *
 * <p><b>没绑设备就静默跳过</b>，不是错误：绝大多数用户走小程序，从没装过 App。
 *
 * <p><b>单台失败不影响其它台</b>：一个人可能同时装了手机与平板，
 * 一台的 cid 失效（卸载/换机）不该让另一台也收不到。
 *
 * <p><b>全部失败也吞掉</b>（装饰器已把 FAILED 写进 {@code sys_notify_log}）：
 * 调用方在 Outbox 消费链路里，抛出去会让整条事件反复重试，而站内信在同一次消费里
 * 已经落库 —— 推送是加速通道，不是必达通道（免费档下还受厂商离线配额约束，ADR-018）。
 */
@Component
public class PushSender {

    private static final Logger log = LoggerFactory.getLogger(PushSender.class);

    private final PushPort port;
    private final PushTokenMapper tokenMapper;

    public PushSender(PushPort port, PushTokenMapper tokenMapper) {
        this.port = port;
        this.tokenMapper = tokenMapper;
    }

    /** 常规通知。 */
    public void notify(String receiverType, String receiverNo,
                       String title, String body, String link) {
        send(receiverType, receiverNo, title, body, link, PushPort.LEVEL_NORMAL);
    }

    /** 高优先提醒（响铃）。只给「商家必须立刻知道」的事 —— 每条都响等于没有响。 */
    public void ring(String receiverType, String receiverNo,
                     String title, String body, String link) {
        send(receiverType, receiverNo, title, body, link, PushPort.LEVEL_RING);
    }

    private void send(String receiverType, String receiverNo,
                      String title, String body, String link, String level) {
        if (receiverNo == null || receiverNo.isBlank()) {
            return;
        }
        List<MsgPushToken> tokens = DataScopeContext.executeWithoutScope(() ->
                tokenMapper.selectList(Wrappers.<MsgPushToken>lambdaQuery()
                        .eq(MsgPushToken::getReceiverType, receiverType)
                        .eq(MsgPushToken::getReceiverNo, receiverNo)));
        for (MsgPushToken t : tokens) {
            try {
                port.push(t.getClientId(), title, body, link, level);
            } catch (RuntimeException e) {
                // 装饰器已留痕；这里保证一台失败不拖累其它台，也不让事件重试
                log.warn("[push] 发送失败（已留痕，不重试）receiver={} platform={}: {}",
                        receiverNo, t.getPlatform(), e.getMessage());
            }
        }
    }
}
