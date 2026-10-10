package ai.neargo.shop.message.notify;

import ai.neargo.shop.link.ShortLinkService;
import ai.neargo.shop.link.entity.ShortLink;
import ai.neargo.shop.spi.notify.SmsPort;
import ai.neargo.shop.spi.notify.WxUrlLinkPort;
import ai.neargo.shop.spi.trade.SubOrderBuyerPort;
import ai.neargo.shop.spi.trade.ShipTrackPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 发货后给**收件人**发一条带短链的物流短信（TDD-收件人物流触达与分享裂变 §2）。
 *
 * <p>把整条链收在一个类里，而不是摊在 {@link ai.neargo.shop.message.NotificationConsumer}
 * 的 SUB_ORDER_SHIPPED 分支里：这条链有四步（签票 → 唤起小程序/退回 H5 → 建短链 → 发短信），
 * 每一步都可能失败，而**任何一步失败都不该影响发货事件的其余通道**（站内信、推送已经发了）。
 * 消费者只管在快递单上调一次 {@link #notify}，吞掉异常。
 *
 * <h3>看件入口的两条路</h3>
 * 短信里的短链最终要把人带到看件页。首选微信 URL Link 唤起小程序；
 * 生成不出来（未接通 / 失败）就退回 H5 看件页 —— 对 SMS 收件人来说 H5 反而更通用，
 * 他未必是小程序用户。URL Link 是增强，不是前置。
 */
@Component
public class ShipRecipientNotify {

    private static final Logger log = LoggerFactory.getLogger(ShipRecipientNotify.class);

    private final ShipTrackPort token;
    private final WxUrlLinkPort urlLink;
    private final ShortLinkService shortLink;
    private final SmsPort smsPort;
    private final SubOrderBuyerPort buyerPort;
    /** 小程序看件页路径，不带前导斜杠 */
    private final String miniPath;
    /** H5 看件页前缀（hash 路由），token 以 {@code ?t=} 拼在后面 */
    private final String h5Base;
    /** 短链有效期（天），与看件令牌同寿 */
    private final long linkTtlDays;

    public ShipRecipientNotify(ShipTrackPort token, WxUrlLinkPort urlLink,
                               ShortLinkService shortLink, SmsPort smsPort,
                               SubOrderBuyerPort buyerPort,
                               @Value("${shop.ship.track-mini-path:pages/track/index}") String miniPath,
                               @Value("${shop.ship.track-h5-base:https://hxmall.top/c/#/pages/track/index}") String h5Base,
                               @Value("${shop.ship.track-ttl-days:30}") long linkTtlDays) {
        this.token = token;
        this.urlLink = urlLink;
        this.shortLink = shortLink;
        this.smsPort = smsPort;
        this.buyerPort = buyerPort;
        this.miniPath = miniPath;
        this.h5Base = h5Base;
        this.linkTtlDays = linkTtlDays > 0 ? linkTtlDays : 30;
    }

    /**
     * 给这张子单的收件人发货短信。**只对有收件人的单发**（自提单没有收件号就跳过）。
     *
     * <p><b>吞掉所有异常</b>：这是发货事件的附带出口，失败只留日志，
     * 不冒泡到 outbox 消费者（冒上去会判整条事件失败并重投，站内信就发第二遍）。
     * 留痕由 {@link NotifyLoggingSmsPort} 那层写（SMS × SHIP_NOTIFY），这里不重复记。
     */
    public void notify(String subOrderNo) {
        try {
            Optional<String> phone = buyerPort.receiverPhoneOf(subOrderNo);
            if (phone.isEmpty()) {
                return;   // 自提单等没有收件人，不发
            }
            String t = token.sign(subOrderNo);
            String target = urlLink.generate(miniPath, "t=" + t)
                    .orElseGet(() -> h5Base + "?t=" + t);
            LocalDateTime expiresAt = LocalDateTime.now().plusDays(linkTtlDays);
            String shortUrl = shortLink.shorten(target, ShortLink.BIZ_SHIP_TRACK, subOrderNo, expiresAt);
            smsPort.sendShipToRecipient(phone.get(), shortUrl);
        } catch (RuntimeException e) {
            // 发货短信失败不影响发货事件其余通道；留痕已在通道那层写，这里只补一条定位日志
            log.warn("[ship-notify] 发货短信发送失败 subOrderNo={} {}", subOrderNo, e.toString());
        }
    }
}
