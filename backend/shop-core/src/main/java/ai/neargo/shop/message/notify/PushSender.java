package ai.neargo.shop.message.notify;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.message.entity.MsgPushToken;
import ai.neargo.shop.message.mapper.MessageMappers.PushTokenMapper;
import ai.neargo.shop.spi.notify.PushPort;
import ai.neargo.shop.spi.notify.PushProvider;
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

    private final PushRouter router;
    private final PushTokenMapper tokenMapper;

    public PushSender(PushRouter router, PushTokenMapper tokenMapper) {
        this.router = router;
        this.tokenMapper = tokenMapper;
    }

    /**
     * 常规通知。
     *
     * @return 交给通道成功的设备数。<b>0 = 这个人一台登记过的设备都没有，或全部失败</b> ——
     *         调用方要据此判「发出去没有」，不能只看有没有抛异常：没设备时这里什么也不做、也不抛
     */
    public int notify(String receiverType, String receiverNo,
                      String title, String body, String link) {
        return send(receiverType, receiverNo, title, body, link, PushPort.LEVEL_NORMAL);
    }

    /**
     * 这批收件人里，谁至少登记过一台推送设备。<b>一次查完</b>，给群发前的试算用 ——
     * 没设备的人推了也收不到，要在「能发多少」里就扣掉，而不是发完才发现。
     */
    public java.util.Set<String> withDevice(String receiverType, java.util.Collection<String> receiverNos) {
        if (receiverNos == null || receiverNos.isEmpty()) {
            return java.util.Set.of();
        }
        return DataScopeContext.executeWithoutScope(() ->
                tokenMapper.selectList(Wrappers.<MsgPushToken>lambdaQuery()
                                .select(MsgPushToken::getReceiverNo)
                                .eq(MsgPushToken::getReceiverType, receiverType)
                                .in(MsgPushToken::getReceiverNo, receiverNos))
                        .stream().map(MsgPushToken::getReceiverNo)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    /** 高优先提醒（响铃）。只给「商家必须立刻知道」的事 —— 每条都响等于没有响。 */
    public void ring(String receiverType, String receiverNo,
                     String title, String body, String link) {
        send(receiverType, receiverNo, title, body, link, PushPort.LEVEL_RING);
    }

    /**
     * 只推**指定一台设备**（运营端「选择终端发起测试」用）：一个人多台设备时，
     * 要能对着某一台真机验证，而不是广播给他所有设备。失败留痕不抛（同 send）。
     */
    public void sendToDevice(String provider, String clientId,
                             String title, String body, String link, String level) {
        if (clientId == null || clientId.isBlank()) {
            return;
        }
        try {
            router.push(PushProvider.normalize(provider), clientId, title, body, link, level);
        } catch (RuntimeException e) {
            log.warn("[push] 定向设备发送失败（已留痕，不重试）provider={}: {}", provider, e.getMessage());
        }
    }

    private int send(String receiverType, String receiverNo,
                     String title, String body, String link, String level) {
        if (receiverNo == null || receiverNo.isBlank()) {
            return 0;
        }
        List<MsgPushToken> tokens = DataScopeContext.executeWithoutScope(() ->
                tokenMapper.selectList(Wrappers.<MsgPushToken>lambdaQuery()
                        .eq(MsgPushToken::getReceiverType, receiverType)
                        .eq(MsgPushToken::getReceiverNo, receiverNo)));
        int ok = 0;
        for (MsgPushToken t : tokens) {
            try {
                router.push(PushProvider.normalize(t.getProvider()), t.getClientId(),
                        title, body, link, level);
                ok++;
            } catch (RuntimeException e) {
                // 路由内已留痕；这里保证一台失败不拖累其它台，也不让事件重试
                log.warn("[push] 发送失败（已留痕，不重试）receiver={} platform={} provider={}: {}",
                        receiverNo, t.getPlatform(), t.getProvider(), e.getMessage());
            }
        }
        return ok;
    }
}
