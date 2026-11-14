package ai.neargo.shop.invbridge;

import ai.neargo.shop.trade.service.CloseRuleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 进销存预留（下单锁货）该活多久。
 *
 * <p><b>由「未支付自动关单」的窗口推出来，不写死。</b>此前镜像补记与进销存直连两处都是 30 分钟，
 * 而关单时长运营可配到 {@link CloseRuleService#MAX_UNPAID_MINUTES 1440 分钟}：配成 2 小时的话，
 * 下单 30 分钟后预留被回收、那批货被别人买走，买家在第 90 分钟照样付款成功 —— 付了钱的单没有货，
 * 而回收是静默的。预留比关单<b>长</b>的代价只是关单后多占一会儿（关单本身会释放），
 * 比关单<b>短</b>的代价是超卖，所以只往长了取。
 *
 * <p><b>余量</b>（{@link #MARGIN_SECONDS}）盖住关单任务的扫描间隔与两边时钟差 ——
 * 关单是定时任务扫出来的，不是到点即关，等长的话总有一段「单还能付、货已释放」。
 *
 * <p><b>封顶于进销存的预留上限</b>（{@code shop.inventory.reserve.max-ttl-seconds}，默认 24 小时）：
 * 超过上限进销存直接拒（BAD_REQUEST），下单就挂了。关单窗口配到上限附近时余量会被吃掉，
 * 那是运营把窗口拉到极限的代价，不该由下单失败来承担。
 *
 * <p><b>当面付 / 线下付款的单不会自动关单</b>：它们的预留同样按这个时长到期回收（最长就是上限）。
 * 到期之后单子还开着，货已回到可卖；之后商家确认线下收款时，进销存的 commit 会以 CONFLICT 拒绝
 * （预留已过期 = 货可能已被别人买走）—— 也就是说，这类单在锁货窗口之外是<b>不保货</b>的，
 * 而且是响的失败，不是静默超卖。要保货得让它们走关单，或在确认收款时重新扣，
 * 那是交易域的决定，不在这一层。
 *
 * <p>时长在<b>锁货那一刻</b>按当时的配置算，与 {@code pay_deadline_at} 的「下单时盖章」同一个口径：
 * 改配置不会回头改已有预留。
 */
@Component
public class ReservationHoldTtl {

    /** 关单任务扫描间隔 + 时钟差的余量。 */
    static final long MARGIN_SECONDS = 10 * 60L;

    /** 没装配上关单策略时（单测直接 new 消费方）的回落：等于改造前的写死值。 */
    static final long LEGACY_SECONDS = 30 * 60L;

    private final CloseRuleService closeRules;
    private final long maxTtlSeconds;

    @Autowired
    public ReservationHoldTtl(CloseRuleService closeRules,
                              @Value("${shop.inventory.reserve.max-ttl-seconds:86400}") long maxTtlSeconds) {
        this.closeRules = closeRules;
        this.maxTtlSeconds = maxTtlSeconds;
    }

    /** 本次锁货的预留秒数。 */
    public long seconds() {
        long window = closeRules.unpaidMinutes() * 60L;
        return Math.min(window + MARGIN_SECONDS, maxTtlSeconds);
    }

    /** 调用方的空值回落放在这里，免得两处各写一个常量再分叉。 */
    public static long secondsOf(ReservationHoldTtl ttl) {
        return ttl == null ? LEGACY_SECONDS : ttl.seconds();
    }
}
