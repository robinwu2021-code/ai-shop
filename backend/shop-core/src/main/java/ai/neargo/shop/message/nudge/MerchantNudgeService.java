package ai.neargo.shop.message.nudge;

/**
 * 主动触达商家（M2）—— **最轻的干预**。
 *
 * <p>链条画像（M1）能指出「这家卡在哪一层」，但指出来之后运营在系统里做不了任何事：
 * 商家域②里唯一的动作是违规处置与封禁，而「你有 41 个品还在待审」与「封店」
 * 之间隔着一整个量级。缺的就是中间这一档。
 *
 * <h2>两条约束</h2>
 *
 * <ol>
 *   <li><b>一天一次</b>。运营点五次不该让商家收到五条 —— 一个能被连点的推送入口
 *       就是一个骚扰工具。幂等键按「商家 × 事由 × 日期」，而且
 *       <b>要能回答「今天已经提醒过了」</b>：底层 {@code pushTo} 撞键是<b>静默跳过</b>，
 *       而静默的后果是运营看不出发没发出去，于是再点一次。</li>
 *   <li><b>事由是枚举，不是自由文本</b>。理由与链条画像的卡点一一对应 ——
 *       让运营自己写的话，同一件事会有十种说法，而商家那头收到的是十条不同的通知。
 *       备注可以自由写，但它是<b>附加</b>在模板后面的一句话。</li>
 * </ol>
 *
 * <p>它落在 {@code shop-core} 而不是装配层：只用到 {@code MessageService} 与三个
 * SPI 端口，不需要认识 {@code mch_entity} —— 多加一层跨模块依赖是为了拿一个店名，
 * 而 {@code MerchantQueryPort} 已经能给。
 */
public interface MerchantNudgeService {

    /**
     * @param entityNo 商家
     * @param reason   事由，取值见 {@link Reason}。与链条画像的卡点一一对应
     * @param note     运营补充的一句话，可空
     */
    NudgeResult nudge(String entityNo, String reason, String note);

    /**
     * @param sent             实际发出几条（按收件人计）
     * @param alreadySentToday 今天已就同一事由提醒过。<b>不是失败</b>，
     *                         但界面必须说出来，否则运营会一直点
     * @param noRecipient      这家店一个能收消息的人都没有。与「已经提醒过了」是两回事：
     *                         前者要去给这家店配人，后者什么都不用做
     */
    record NudgeResult(int sent, boolean alreadySentToday, boolean noRecipient) {
    }

    /**
     * 事由。与 {@code MerchantChainService.Stuck} 同名同义 —— 两处分叉就是两套结论。
     *
     * <p><b>少一个：{@code IN_AUDIT} 不在这里，而且不该在。</b>
     * 那一档的意思是「他的品全卡在平台的审核队列里」——
     * <b>欠账的是平台，不是商家</b>。就这件事去提醒商家，等于把自己的积压
     * 说成对方的问题；而商家收到之后能做的只有再等。
     * 链条画像上那一行该给的出路是「去审核队列」，不是「发个提醒」。
     *
     * <p>{@code NO_ACCOUNT} 留着，但它是个边界情况：建了账失败多半是投影链路断了
     * （见「链路健康」），也是平台的事。留它是因为另一半原因确实在商家那头
     * （比如品还没审过、没进过投影范围），而运营点之前看得到卡点。
     */
    final class Reason {
        public static final String NO_GOODS = "NO_GOODS";
        public static final String NOT_ON_SALE = "NOT_ON_SALE";
        public static final String NO_ACCOUNT = "NO_ACCOUNT";
        public static final String NO_INBOUND = "NO_INBOUND";
        public static final String STALE_LEDGER = "STALE_LEDGER";

        /** 允许的事由。**不认的一律拒**，否则自由文本会从这个口子漏进去 */
        public static final java.util.Set<String> ALL = java.util.Set.of(
                NO_GOODS, NOT_ON_SALE, NO_ACCOUNT, NO_INBOUND, STALE_LEDGER);

        private Reason() {
        }
    }
}
