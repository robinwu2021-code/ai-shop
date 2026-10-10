package ai.neargo.shop.message.notify;

import ai.neargo.shop.message.entity.NotifyChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * 「这家门店的企微群在哪」—— 按**门店号**解析出群机器人的 Webhook
 * （TDD-来单四渠道与商家通知设置 §2.4；最初按主体，2026-10-10 用户订正为门店）。
 *
 * <p>真源是 {@code notify_channel} 的 {@code scope=MERCHANT} 行：
 * {@code owner_no=门店号}、{@code channel_type=WEBHOOK}、{@code provider=WECOM}，
 * URL 作为凭据存在加密列 {@code secret_cipher} 里（{@link NotifyCredCipher}）。
 *
 * <p><b>{@code owner_no} 存门店号而不是主体号</b>：那一列的注释说的是
 * 「scope=MERCHANT 的商家号」，而群这件事天然是按店的 —— 自营一个主体下已有 4 家店，
 * 「粮油店的来单别往鲜果的群里发」在主体粒度下根本表达不了。
 * 这张表原本是为「商家自带短信/推送账号」设计的（那确实是主体级），
 * 群机器人借用同一套机制但用门店号做 owner —— 两种 owner 共存在一列里，
 * 靠 {@code channel_type} 区分，查的时候必须带上它。
 *
 * <p><b>这个类就是那条接缝。</b>自营阶段几家店共用一个群，
 * 看起来和「全部播到平台那条 env」没差别 —— 但那两种写法在店主想分开那天分叉：
 * 按门店解析只改一行数据，而「播到平台那条」要改每一个调用点。
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
     * @param storeNo 门店号（存在 {@code owner_no} 那一列）。空 → 空
     * @return 这家店的群 Webhook；没登记、被停用、没存密钥、或密文坏了都返回空。
     *         <b>返回值是凭据</b> —— 调用方只许交给 {@link WeComBotSender}，不进日志、不进响应体
     */
    public Optional<String> of(String storeNo) {
        if (storeNo == null || storeNo.isBlank()) {
            return Optional.empty();
        }
        NotifyChannel ch = channels.listForOwner(storeNo).stream()
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
            log.warn("门店 {} 的企微群凭据解密失败（密钥换过？）：{}", storeNo, e.toString());
            return Optional.empty();
        }
        if (plain == null || plain.isBlank()) {
            return Optional.empty();
        }
        try {
            String url = json.readTree(plain).path("webhook").asString("");
            return url.isBlank() ? Optional.empty() : Optional.of(url);
        } catch (RuntimeException e) {
            log.warn("门店 {} 的企微群凭据解析不出 webhook 字段：{}", storeNo, e.toString());
            return Optional.empty();
        }
    }
}
