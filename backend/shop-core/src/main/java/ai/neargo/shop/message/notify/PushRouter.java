package ai.neargo.shop.message.notify;

import ai.neargo.shop.message.entity.SysNotifyLog;
import ai.neargo.shop.spi.notify.NotifyBizType;
import ai.neargo.shop.spi.notify.PushGateway;
import ai.neargo.shop.spi.notify.PushPort;
import ai.neargo.shop.spi.notify.PushProvider;
import ai.neargo.shop.spi.notify.SendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 多供应商推送路由（设计：多渠道推送与运营端触达配置 · 需求 2）。
 *
 * <p>按设备的 {@code provider} 把一条推送分发到对应 {@link PushGateway}：
 * 个推 / FCM / APNs。加一家供应商 = 加一个 gateway 实现 + 一个条件开关，路由主逻辑不动。
 *
 * <p><b>桩优先</b>：本地/测试默认桩，在场的只有一个 {@code stub()} gateway，
 * 所有 provider 的推送都交给它（发不出去也发不出，只是记下来）。
 *
 * <p><b>发送记录在这里落</b>（原 {@code NotifyLoggingPushPort} 的职责搬来）：
 * 失败也记、先记后抛。用户说「我没收到」时，靠 {@code sys_notify_log} 分清
 * 「没推」「推了厂商没送达」「送达了没看见」。
 */
@Component
public class PushRouter {

    private static final Logger log = LoggerFactory.getLogger(PushRouter.class);

    /** 推送只有一条通用模板（标题+正文），场景差异体现在内容而非模板。 */
    private static final String TPL_PUSH = "TPL_PUSH_TEST";

    private final Map<String, PushGateway> byProvider;
    private final PushGateway stubGateway;
    private final NotifyLogWriter writer;

    public PushRouter(List<PushGateway> gateways, NotifyLogWriter writer) {
        this.writer = writer;
        this.stubGateway = gateways.stream().filter(PushGateway::stub).findFirst().orElse(null);
        // 真实 gateway 按 provider 建索引；同一 provider 多个实现视为装配错误，取第一个并告警
        this.byProvider = gateways.stream().filter(g -> !g.stub())
                .collect(Collectors.toMap(PushGateway::provider, Function.identity(), (a, b) -> {
                    log.warn("[push] provider={} 有多个 gateway，取 {}", a.provider(),
                            a.getClass().getSimpleName());
                    return a;
                }));
        log.info("[push] 路由就绪 providers={} stub={}", byProvider.keySet(), stubGateway != null);
    }

    /**
     * 发一条推送到指定供应商的设备。找不到对应 gateway 也找不到桩时抛出，由调用方留痕放行。
     *
     * @param provider {@link PushProvider}；null/未知回落 GETUI
     */
    public SendResult push(String provider, String clientId,
                           String title, String body, String link, String level) {
        try {
            // select 也放进 try：找不到 gateway（如 FCM 尚在 P3）同样记一条 FAILED，
            // 「他为什么没收到」才分得清是「没配通道」还是「通道拒绝」
            SendResult r = select(provider).push(clientId, title, body, link, level);
            writer.write(SysNotifyLog.PUSH, NotifyBizType.TRADE_NOTIFY, clientId,
                    r.templateCode(), TPL_PUSH, SysNotifyLog.SENT, null, r.providerMsgId(), null);
            return r;
        } catch (RuntimeException e) {
            writer.write(SysNotifyLog.PUSH, NotifyBizType.TRADE_NOTIFY, clientId,
                    level, TPL_PUSH, SysNotifyLog.FAILED, e.getMessage(), null, null);
            throw e;
        }
    }

    /** 桩在场就一律走桩（顶所有 provider）；否则按 provider 选真实 gateway。 */
    private PushGateway select(String provider) {
        if (stubGateway != null) {
            return stubGateway;
        }
        String p = PushProvider.normalize(provider);
        PushGateway g = byProvider.get(p);
        if (g == null) {
            // 该 provider 的 gateway 未启用（如 FCM/APNs 尚在 P3）：显式失败，站内信兜底
            throw new PushPort.PushException("无可用推送 gateway：provider=" + p, false);
        }
        return g;
    }
}
