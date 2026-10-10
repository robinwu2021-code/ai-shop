package ai.neargo.shop.message.notify;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.notify.NotifyBizType;
import ai.neargo.shop.spi.trade.SubOrderBuyerPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 来单 → 商家自己的企微群（TDD-商家企微群来单通知 §2.4）。
 *
 * <p>一条四行的 markdown：门店、金额、商品、单号。够商家判断「要不要现在去备货」，
 * 又不把买家信息带进群 —— 群里人多，手机号与收货地址不进去。
 *
 * <p><b>整条链路失败不抛</b>：来单提醒是尽力而为的，不能把站内信与 App 推送一起拖掉
 * （{@code NotificationConsumer} 的那三条出口各自独立）。发不出去的痕迹在
 * {@code sys_notify_log} 的 WEBHOOK 行里，由 {@link WeComBotSender} 写。
 */
@Component
public class WeComOrderAlert {

    private static final Logger log = LoggerFactory.getLogger(WeComOrderAlert.class);

    private final MerchantNotifyRecipients recipients;
    private final WeComBotSender sender;
    private final MerchantQueryPort merchantPort;
    private final SubOrderBuyerPort subOrderPort;

    public WeComOrderAlert(MerchantNotifyRecipients recipients, WeComBotSender sender,
                           MerchantQueryPort merchantPort, SubOrderBuyerPort subOrderPort) {
        this.recipients = recipients;
        this.sender = sender;
        this.merchantPort = merchantPort;
        this.subOrderPort = subOrderPort;
    }

    /**
     * 付款成功的一张子单。
     *
     * @param entityNo    商家主体号 —— 只用来在查不到门店名时回落主体名
     * @param storeNo     门店号 —— **决定发到哪个群**（2026-10-10 订正：群按门店不按主体）
     * @param subOrderNo  子单号
     * @param payAmountMinor 实付（分）
     * @return 真的发出去了 true。商家没配群、发送失败都是 false（都不抛）
     */
    public boolean paid(String entityNo, String storeNo, String subOrderNo, long payAmountMinor) {
        /*
         * **先解析 webhook，再去补查门店名与商品**：绝大多数门店没配群，
         * 那时候补查是白跑两条 SQL。来单是全站最高频的通知之一，这个顺序不是洁癖。
         */
        Optional<String> url = recipients.wecomWebhook(storeNo);
        if (url.isEmpty()) {
            return false;
        }
        try {
            return sender.sendMarkdown(NotifyBizType.TRADE_NOTIFY,
                    content(entityNo, storeNo, subOrderNo, payAmountMinor), url.get());
        } catch (RuntimeException e) {
            // sendMarkdown 自己已经吞了发送异常；这里兜的是补查门店名/商品时的意外
            log.warn("来单推企微群失败 subOrderNo={} {}", subOrderNo, e.toString());
            return false;
        }
    }

    /**
     * 同一份内容的**纯文本版**，给邮件用（主题在调用方拼）。
     *
     * <p>两边共用一份排版而不是各写一份：同一张单在群里与邮件里说的话不一致，
     * 是最难查的那种不一致 —— 两处都「看起来对」。这里只是把 markdown 的
     * 引用符去掉，其余一个字不改。
     */
    public String plainText(String entityNo, String storeNo, String subOrderNo, long payAmountMinor) {
        return content(entityNo, storeNo, subOrderNo, payAmountMinor)
                .replace("**", "").replace("> ", "");
    }

    /** 包可见供单测直接断言排版，不必起 HTTP */
    String content(String entityNo, String storeNo, String subOrderNo, long payAmountMinor) {
        StringBuilder md = new StringBuilder("**新订单**\n");
        storeLabel(entityNo, storeNo).ifPresent(n -> md.append("> 门店：").append(n).append('\n'));
        md.append("> 金额：").append(yuan(payAmountMinor)).append('\n');
        itemsLabel(subOrderNo).ifPresent(s -> md.append("> 商品：").append(s).append('\n'));
        md.append("> 单号：").append(subOrderNo);
        return md.toString();
    }

    /**
     * 门店名 → 主体名 → 空。
     *
     * <p>⚠️ <b>要绕数据域</b>：调用方是 outbox 消费者，没有会话，而
     * {@link MerchantQueryPort#storeNames} 故意不自己解域（它假定调用方传进来的门店号已在权限内）。
     * 不绕的话 fail-closed 把查询拼成 {@code 1=0}，门店名恒空 —— 而那一行只是「少显示一行」，
     * 没有任何报错。
     */
    private Optional<String> storeLabel(String entityNo, String storeNo) {
        if (storeNo != null && !storeNo.isBlank()) {
            String name = DataScopeContext.executeWithoutScope(
                    () -> merchantPort.storeNames(List.of(storeNo))).get(storeNo);
            if (name != null && !name.isBlank()) {
                return Optional.of(name);
            }
        }
        if (entityNo == null || entityNo.isBlank()) {
            return Optional.empty();
        }
        return DataScopeContext.executeWithoutScope(() -> merchantPort.find(entityNo))
                .map(MerchantQueryPort.MerchantBrief::merchantName)
                .filter(n -> n != null && !n.isBlank());
    }

    /** 「土豆 等 3 件」；只有一件时就「土豆」—— 不写「等 1 件」 */
    private Optional<String> itemsLabel(String subOrderNo) {
        return subOrderPort.itemsOf(subOrderNo).map(b -> {
            String first = b.firstGoodsName() == null || b.firstGoodsName().isBlank()
                    ? "商品" : b.firstGoodsName();
            return b.itemCount() <= 1 ? first : first + " 等 " + b.itemCount() + " 件";
        });
    }

    /** 分 → 「￥12.34」。群里给人看的，不是给机器解析的 */
    private static String yuan(long minor) {
        return "￥%.2f".formatted(minor / 100.0);
    }
}
