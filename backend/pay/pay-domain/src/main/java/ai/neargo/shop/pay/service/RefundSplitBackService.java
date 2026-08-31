package ai.neargo.shop.pay.service;

import ai.neargo.shop.pay.dto.FinanceVOs.RefundSplitBackVO;

import java.util.List;

/**
 * 退款回退分账（矩阵 P-12.1.5 / 待完成功能清单 E4，标「高风险」）。
 *
 * <p><b>这个队列为什么存在</b>：{@code AfterSaleServiceImpl.arbitrate(refund=true)}
 * 只把售后单推到 {@code REFUNDING}，<b>不执行退款</b> —— 单子停在那里等一个不会来的动作。
 * 而它对应的子单可能已经分过账（钱在商家账户里）。财务要做的正是把这两件事按顺序做完。
 *
 * <p><b>顺序不可交换</b>（ADR-002）：先回退分账，再退款。
 * 反过来做的话，钱退给买家了而分账收不回，商家已提现的部分只能人工追。
 */
public interface RefundSplitBackService {

    /**
     * 待办队列：<b>已裁决支持退款、钱还没退、且结算单尚未回退</b>的售后单。
     *
     * <p>两个条件缺一不可。只按售后状态取，会把「退货退款还在等买家寄回」那批也算进来
     * ——那批钱本来就不该现在退；只按结算单取，则拿不到售后单号，
     * 而这条链路上运营认的是售后单。
     */
    List<RefundSplitBackVO> pending();

    /**
     * 执行回退并走完退款。
     *
     * <ol>
     *   <li>回退分账（失败即中止，<b>不退款</b>，结算单转人工）</li>
     *   <li>走既有 {@code doRefund}：退款 → 子单转态 → 发事件</li>
     * </ol>
     *
     * <p><b>幂等</b>：运营看到列表没刷新会再点一次，而这条路径动的是真钱。
     */
    RefundSplitBackVO execute(String afterSaleNo, String operatorNo);
}
