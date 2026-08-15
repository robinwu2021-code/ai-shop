package ai.neargo.shop.spi.trade;

import java.util.List;

/**
 * settle → trade：<b>退款回退分账</b>队列（矩阵 P-12.1.5，待完成功能清单 E4）。
 *
 * <p><b>这个队列为什么存在</b>：{@code AfterSaleServiceImpl.arbitrate(refund=true)}
 * 只把售后单推到 {@code REFUNDING}，<b>不执行退款</b> —— 单子停在那里等一个不会来的动作。
 * 而它对应的子单可能已经分过账（钱在商家账户里）。这两件事叠起来就是 E4：
 * 已分账订单的退款，必须先把分账收回来，再退给买家。
 *
 * <p><b>为什么 {@link #resumeRefund} 是一个 Port 而不是让 settle 自己改
 * {@code ord_after_sale.status}</b>：与 {@link OrderRepairPort} 同一条理由 ——
 * 退款收尾要做的不止改一个字段（子单转态、发 {@code AfterSaleRefunded} 事件、
 * 下游据此回补库存与评分）。在别处抄一遍，漏掉的那几件不会报错，
 * 要等有人问「我退款了怎么库存没回来」才知道。
 *
 * <p>所以这里只暴露<b>一个只读查询 + 一个既有链路的入口</b>，不是新写的收尾逻辑。
 */
public interface RefundSplitBackPort {

    /**
     * 等待回退分账的售后单：<b>已裁决支持退款、钱还没退</b>的那些。
     *
     * <p>口径是 {@code status = REFUNDING} 且 <b>{@code liability} 非空</b>
     * 且 {@code split_reversed} 不为真。
     *
     * <p><b>{@code liability} 非空是关键的判别器</b>：REFUNDING 有两个来源 ——
     * 平台裁决支持退款（{@code arbitrate} 强制写责任方），以及退货退款商家已同意
     * （等买家寄回，{@code approve} 不写责任方）。后者的钱<b>本来就不该现在退</b>，
     * 货还没回来。只按状态取，财务会在货没收到时就把钱退出去。
     *
     * <p>调用方（settle）还要再与结算单求交集，排掉已经 {@code REVERSED} 的。
     */
    List<PendingBack> pendingSplitBacks();

    /**
     * 把一笔已回退分账的售后单走完退款（{@code REFUNDING → REFUNDED}）。
     *
     * <p><b>必须幂等</b>：运营看到列表没刷新就会再点一次，而这条路径动的是真钱。
     * 幂等由 {@code doRefund} 首行的 {@code REFUNDED} 早退保证。
     *
     * <p><b>内部会再做一次分账回退</b>（{@code SettlePort.reverseSplit}）。
     * 这不是多余的：顺序保证只在 {@code doRefund} 里有一份，
     * 调用方先回退过并不意味着可以跳过它 —— 跳过就等于把同一条约束拆成了两处。
     */
    void resumeRefund(String afterSaleNo, String operatorNo);

    /**
     * @param merchantName 商家名。运营认的是店名，不是 {@code entityNo}
     * @param liability    裁定的责任方 PLATFORM / MERCHANT / PICKUP；未裁决为空
     * @param verdict      裁决说明（取自售后时间线上平台那条）。用户与商家都会看到
     * @param images       用户提交的举证材料。<b>可能为空列表，但不会是 null</b> ——
     *                     运营端契约把它声明成必填数组，缺了会在渲染时抛异常
     */
    record PendingBack(String afterSaleNo, String subOrderNo, String orderNo,
                       String merchantNo, String merchantName, String buyerNickname,
                       String type, String status, long refundMinor, String reason,
                       List<String> images, String liability, String verdict,
                       long createdAt) {
    }
}
