package ai.neargo.shop.pay.dto;

/**
 * 结算单（对齐契约 SettleBill）。金额三列都给出去 —— 商家要能自己核对。
 *
 * <p><b>storeNo 与 payMerchantNo 都要给出去</b>，因为它们回答的是两个不同的问题：
 * 「这笔是哪家店挣的」和「这笔打给哪个账户」。多门店商家两个都要看得见 ——
 * 只给其中一个，他就无法回答「河坊街店这个月的钱到底进了哪张卡」。
 */
public record SettleBillVO(String settleNo,
                           String subOrderNo,
                           String orderNo,
                           String merchantNo,
                           long grossMinor,
                           long commissionMinor,
                           long serviceFeeMinor,
                           long netMinor,
                           String trafficSource,
                           int commissionRate,
                           String status,
                           long createdAt,
                           Long splitAt,
                           /** 哪家店挣的（统计维度）。空 = 存量主体级流水 */
                           String storeNo,
                           /** 打给哪个收款号（结算维度）。空 = 生成时进件还没走完 */
                           String payMerchantNo,
                           /** SELF_OPERATED / THIRD_PARTY。决定这张单该看哪些字段 */
                           String businessMode,
                           /** 自营：进项票状态。第三方恒为 NO_INVOICE */
                           String invoiceStatus,
                           /** 自营：付款凭证号（网银流水）。空 = 尚未付款 */
                           String paymentRef,
                           /**
                            * 本单发分的费用金（分）。**单独一列，不并进 serviceFeeMinor** ——
                            * 商家最常问「这个月为什么少了」，而佣金是按率的（他改不了）、
                            * 费用金是按他自己开的积分活动走的（他关掉就没了），
                            * 两者的解释路径完全不同。并成一列，客服每次都得翻明细才答得出。
                            *
                            * <p>此前它不在 VO 里，于是 B 端「本期积分支出」<b>永远是 0</b> ——
                            * 不是数字不对，是这个数根本没下发。
                            */
                           long pointsFeeMinor,
                           /**
                            * T2 可结算时刻。空 = 还不可结算（未履约，或售后未闭环）。
                            *
                            * <p>与下面两个一起，回答商家问的第一个问题：<b>「这笔什么时候到」</b>。
                            * 此前这一页只给金额，商家拿它去对银行流水，对不上就来找客服 ——
                            * 而客服看到的也只有一个金额。
                            */
                           Long settleableAt,
                           /** T3 应结日（本批的）。空 = 还没入批 */
                           Long dueAt,
                           /** 归属批次。空 = 还没入批；<b>「卡在哪」全靠它</b> */
                           String batchNo,
                           /**
                            * 本批当前的状态（{@code StlSettleBatch} 的取值）。空 = 还没入批。
                            *
                            * <p>单据状态说「钱在哪」，批次状态说「流程走到哪」——
                            * 商家两个都要看：单子还是 PENDING 但批次已 RECONCILED，
                            * 说明就快放了；批次 BLOCKED 则要看 {@code batchBlockedReason}。
                            */
                           String batchStatus,
                           /** 批次被挂起的原因，<b>直接展示给商家的原话</b>。空 = 没挂起 */
                           String batchBlockedReason) {
}
