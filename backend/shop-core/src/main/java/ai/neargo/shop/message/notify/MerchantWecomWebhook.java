package ai.neargo.shop.message.notify;

import ai.neargo.shop.message.entity.NotifyChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * 「这个商家的企微群在哪」—— 按商家号解析出群机器人的 Webhook
 * （TDD-商家企微群来单通知 §2.2）。
 *
 * <p>真源是 {@code notify_channel} 的 {@code scope=MERCHANT} 行：
 * {@code owner_no=商家主体号}、{@code channel_type=WEBHOOK}、{@code provider=WECOM}，
 * URL 作为凭据存在加密列 {@code secret_cipher} 里（{@link NotifyCredCipher}）。
 *
 * <p><b>这个类就是那条接缝。</b>自营阶段只有一家、那一家的群恰好是运营自己的群，
 * 看起来和「全部播到平台那条 env」没差别 —— 但那两种写法在第二个商家接进来的那天
 * 分叉：按商家解析只多一行数据，而「播到平台那条」要改每一个调用点。
 *
 * <p><b>没配就返回空，绝不回落到平台那条 env</b>。回落的话，第二个商家接进来的那天，
 * 他的订单会默默发进运营群 —— 那是信息泄露，而且**零症状**：群里有消息、
 * {@code sys_notify_log} 里是 SENT，没有任何一处显示「发错了人」。
 * 不发反而留得下痕迹（该有的那条 WEBHOOK 行不存在）。
 */
@Component
public class MerchantWecomWebhook {

    private static final Logger log = LoggerFactory.getLogger(MerchantWecomWebhook.class);

    private final MerchantChannelService channels;
    private final ObjectMapper json;

    public MerchantWecomWebhook(MerchantChannelService channels, ObjectMapper json) {
        this.channels = channels;
        this.json = json;
    }

    /**
     * @param entityNo 商家主体号（{@code owner_no}）。空 → 空
     * @return 那个商家的群 Webhook；没登记、被停用、没存密钥、或密文坏了都返回空。
     *         <b>返回值是凭据</b> —— 调用方只许交给 {@link WeComBotSender}，不进日志、不进响应体
     */
    public Optional<String> of(String entityNo) {
        if (entityNo == null || entityNo.isBlank()) {
            return Optional.empty();
        }
        NotifyChannel ch = channels.listForOwner(entityNo).stream()
                .filter(c -> NotifyChannel.TYPE_WEBHOOK.equals(c.getChannelType()))
                .filter(c -> NotifyChannel.PROV_WECOM.equals(c.getProvider()))
                .filter(c -> Boolean.TRUE.equals(c.getEnabled()))
                .findFirst().orElse(null);
        if (ch == null) {
            return Optional.empty();
        }
        /*
         * 解密失败**不抛**：来单提醒发不出去不该把站内信和 App 推送一起拖掉。
         * 但一定要留一行 WARN —— 密钥被换过（SHOP_NOTIFY_CRED_KEY 一改，存量全失配）
         * 是这里唯一会长期静默的失败模式，没有日志的话症状只是「商家再也收不到群消息」。
         */
        String plain;
        try {
            plain = channels.decryptSecret(ch);
        } catch (RuntimeException e) {
            log.warn("商家 {} 的企微群凭据解密失败（密钥换过？）：{}", entityNo, e.toString());
            return Optional.empty();
        }
        if (plain == null || plain.isBlank()) {
            return Optional.empty();
        }
        try {
            String url = json.readTree(plain).path("webhook").asString("");
            return url.isBlank() ? Optional.empty() : Optional.of(url);
        } catch (RuntimeException e) {
            log.warn("商家 {} 的企微群凭据解析不出 webhook 字段：{}", entityNo, e.toString());
            return Optional.empty();
        }
    }
}
