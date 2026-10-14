package ai.neargo.shop.merchant.dto;

/**
 * 收款进件状态（每通道一条）。
 *
 * <p><b>商家真正想知道的是「我能收钱了吗、卡在哪」</b>，不是一个「审核中」。
 * 所以这里除了状态还带上 {@code missing}：缺什么就说缺什么 ——
 * 「还差结算账户」比「审核中」有用得多，后者只会换来一通电话。
 *
 * @param payChannel     通道码，如 WECHAT
 * @param channelName    通道展示名
 * @param applyStatus    NONE / APPLYING / ACTIVE / REJECTED / FROZEN
 * @param canReceiveMoney 这个通道现在能不能收钱 = {@code applyStatus == ACTIVE}。
 *                       单独给一个布尔而不是让端去比状态串：端上比错的表现是
 *                       「显示能收钱但收不了」，而这种错要到第一笔订单才暴露
 * @param payMerchantNo  收款商户号业务键，通过后才有
 * @param subMchidMasked 二级商户号<b>掩码</b>。完整号不回显给端
 * @param settleAccountType PERSONAL_BANK_CARD / MERCHANT_ID
 * @param settleAccountMasked 结算账号掩码。<b>明文永不回显</b>，包括给商家自己
 * @param rejectReason   驳回原因。驳回时必有 —— 没有原因商家只能反复重提
 * @param missing        还缺哪些资料（如 settleAccount / licenses）。空表示资料齐了在等通道
 * @param submitted      <b>有没有真的发给通道过</b>（{@code channel_apply_no} 非空）。
 *
 *                       <p>没有这一个布尔，{@code APPLYING} 就同时表示两件相反的事：
 *                       「入驻通过时建的占位，商家还没填过任何东西」与「已经发给通道、在等回执」。
 *                       端上把它一律显示成「审核中」，于是新商家看到的是
 *                       <b>「审核中」+「还差结算账户」</b> —— 他读成球在平台，
 *                       而球其实在他自己脚下。而这正是「不能收钱」最常卡死的一步。
 *
 *                       <p>判据本来就在库里（提交过才有通道单号），只是没下发。
 * @param appliedAt      提交时间
 * @param activatedAt    开户完成时间
 * @param storeNo        这条进件是<b>为哪家门店</b>做的；<b>空 = 主体级默认号</b>。
 *                       必须给出去：多门店商家看到两条「微信 · 已开通」而分不清哪条是哪家店，
 *                       等于让他猜自己的钱打进了哪张卡
 */
public record PaymentApplymentVO(String payChannel, String channelName, String applyStatus,
                                 boolean canReceiveMoney, String payMerchantNo,
                                 String subMchidMasked, String settleAccountType,
                                 String settleAccountMasked, String rejectReason,
                                 java.util.List<String> missing, boolean submitted,
                                 Long appliedAt, Long activatedAt, String storeNo) {
}
