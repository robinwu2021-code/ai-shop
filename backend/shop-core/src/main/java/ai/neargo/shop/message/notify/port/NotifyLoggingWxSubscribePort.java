package ai.neargo.shop.message.notify.port;

import ai.neargo.shop.message.entity.SysNotifyLog;
import ai.neargo.shop.message.notify.NotifyLogWriter;
import ai.neargo.shop.spi.notify.NotifyBizType;
import ai.neargo.shop.spi.notify.SendResult;
import ai.neargo.shop.spi.notify.WxSubscribePort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 给订阅消息通道套一层发送记录。模式与 {@link NotifyLoggingSmsPort} 完全一致：
 * 失败也记、先记后抛 —— 「他为什么没收到」正是这张表存在的全部理由。
 *
 * <p>{@code target} 传 openid，由 {@link NotifyLogWriter} 统一掩码
 * （非邮件通道走手机号口径：留头三尾四，对 28 位的 openid 同样适用）。
 */
@Component
@Primary
public class NotifyLoggingWxSubscribePort implements WxSubscribePort {

    private final WxSubscribePort delegate;
    private final NotifyLogWriter writer;
    private final ai.neargo.shop.spi.platform.SettingPort settingPort;

    public NotifyLoggingWxSubscribePort(@Qualifier("wxSubscribeGateway") WxSubscribePort delegate,
                                        NotifyLogWriter writer,
                                        ai.neargo.shop.spi.platform.SettingPort settingPort) {
        this.delegate = delegate;
        this.writer = writer;
        this.settingPort = settingPort;
    }

    /**
     * 场景 → 模板号：**运营配置优先，回落通道自己那份（环境变量/桩）**。
     *
     * <p>覆盖读在这一层而不是网关里：模板号映射是**领域配置**（运营在页面上改），
     * 通道只该认自己的配置、不碰数据库。放在这里还有一个实际好处 ——
     * 走桩时同样生效，运营在本地/联调环境改了模板号能立刻看出效果，
     * 而不是「只有接了真通道才知道有没有生效」。
     *
     * <p>为什么模板号可以进 DB 而 appsecret 不行：模板号不是凭据（拿到也发不出东西），
     * 而换模板是运营行为不是运维行为，为它发一次版不合理。密钥的边界不变。
     */
    @Override
    public String templateId(String scene) {
        String override = configured(scene);
        if (override != null && !override.isBlank()) {
            return override;
        }
        // 纯查询，不是发送动作，不留痕
        return delegate.templateId(scene);
    }

    /** 运营配的覆盖值。读不到/解析失败一律当没配 —— 配置坏了不该让通道整个哑掉。 */
    private String configured(String scene) {
        String key = switch (scene) {
            case SCENE_ORDER_ARRIVED -> "orderArrived";
            case SCENE_REFUNDED -> "refunded";
            default -> null;
        };
        if (key == null) {
            return null;
        }
        String json = settingPort.get(TEMPLATES_SETTING_KEY, "{}");
        var m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(json == null ? "" : json);
        return m.find() ? m.group(1) : null;
    }

    @Override
    public SendResult sendOrderArrived(String openId, int orderCount, String page, String tip) {
        return logged(openId, SCENE_ORDER_ARRIVED,
                () -> delegate.sendOrderArrived(openId, orderCount, page, tip));
    }

    @Override
    public SendResult sendRefunded(String openId, String amountText, String page, String tip) {
        return logged(openId, SCENE_REFUNDED,
                () -> delegate.sendRefunded(openId, amountText, page, tip));
    }

    /**
     * 场景 → **我们自己的**模板号。与 {@code delegate.templateId(scene)} 不同：
     * 那个返回微信侧报备的 id（会随重新报备而变），这个是库里那份可查可改的模板。
     */
    private static String bizTemplateOf(String scene) {
        return WxSubscribePort.SCENE_REFUNDED.equals(scene) ? "TPL_WX_REFUNDED" : "TPL_WX_ARRIVED";
    }

    private SendResult logged(String openId, String scene, java.util.function.Supplier<SendResult> call) {
        try {
            SendResult r = call.get();
            writer.write(SysNotifyLog.WXSUB, NotifyBizType.TRADE_NOTIFY, openId,
                    r.templateCode(), bizTemplateOf(scene),
                    SysNotifyLog.SENT, null, r.providerMsgId(), null);
            return r;
        } catch (RuntimeException e) {
            writer.write(SysNotifyLog.WXSUB, NotifyBizType.TRADE_NOTIFY, openId,
                    delegate.templateId(scene), bizTemplateOf(scene),
                    SysNotifyLog.FAILED, e.getMessage(), null, null);
            throw e;
        }
    }
}
