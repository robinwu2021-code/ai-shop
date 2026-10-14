package ai.neargo.shop.trade.order.refund.service;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.order.refund.model.RefundLine;
import ai.neargo.shop.trade.order.refund.model.RefundPlan;
import ai.neargo.shop.trade.order.refund.model.RefundableLine;
import ai.neargo.shop.trade.order.refund.model.RefundableView;
import ai.neargo.shop.trade.order.refund.policy.FreightRefundPolicy;
import ai.neargo.shop.trade.order.refund.policy.LineAmountPolicy;

import java.util.ArrayList;
import java.util.List;

/**
 * 由买家的申请（哪几行、各几件）生成退款方案（纯函数）。不传行 = 整单剩余全退。
 * 金额由 {@link LineAmountPolicy} 算，运费由 {@link FreightRefundPolicy} 判；本类只校验与组装。
 */
public class RefundPlanner {

    private final LineAmountPolicy lineAmount;
    private final FreightRefundPolicy freight;

    public RefundPlanner(LineAmountPolicy lineAmount, FreightRefundPolicy freight) {
        this.lineAmount = lineAmount;
        this.freight = freight;
    }

    /**
     * @param requested 买家选的行（金额字段忽略，由策略算）；null 或空 = 整单剩余
     * @param shipped   货是否已发出（决定运费）
     */
    public RefundPlan plan(RefundableView view, List<RefundLine> requested, boolean shipped, String reasonCode) {
        List<RefundLine> wanted = requested == null || requested.isEmpty() ? wholeRemaining(view) : requested;
        List<RefundLine> lines = new ArrayList<>();
        for (RefundLine r : wanted) {
            lines.add(priced(view, r));
        }
        RefundPlan goodsOnly = new RefundPlan(view.subOrderNo(), lines, 0L);
        long f = freight.freightFor(view.freightRemainingMinor(), shipped, goodsOnly.coversAllRemaining(view), reasonCode);
        RefundPlan plan = new RefundPlan(view.subOrderNo(), lines, f);
        if (plan.totalMinor() <= 0) {
            throw BizException.of(ErrorCode.AFTERSALE_AMOUNT_INVALID);
        }
        return plan;
    }

    private RefundLine priced(RefundableView view, RefundLine r) {
        RefundableLine line = view.line(r.itemId());
        if (line == null || r.qty() <= 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (r.qty() > line.remainingQty()) {
            throw BizException.of(ErrorCode.AFTERSALE_AMOUNT_INVALID);
        }
        return new RefundLine(r.itemId(), r.qty(), lineAmount.amountFor(line, r.qty()));
    }

    private static List<RefundLine> wholeRemaining(RefundableView view) {
        return view.lines().stream().filter(l -> l.remainingQty() > 0)
                .map(l -> new RefundLine(l.itemId(), l.remainingQty(), 0L)).toList();
    }
}
