package ai.neargo.shop.pay.settle.refund;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.mapper.SettleMappers.BillMapper;
import ai.neargo.shop.pay.mapper.PointsSettlementRefundHoldMapper;
import ai.neargo.shop.spi.settle.SettlePort.SplitReversal;
import ai.neargo.shop.spi.points.PointsRefundPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 退款时的结算侧编排（5.3）：按售后单判重 → 口径算份额 → 分账前扣减 / 分账后按额回退。
 *
 * <p>自身不写规则：份额归 {@link SettlementRefundPolicy}，两种落法归
 * {@link PreSplitAdjuster} / {@link PostSplitReverser}。这里只判「这张结算单处在哪一边」。
 */
@Component
public class SettlementRefundCoordinator {

    private static final Logger log = LoggerFactory.getLogger(SettlementRefundCoordinator.class);

    /** 分账指令发出过（含确认到账）—— 钱已经动过，要经通道回退 */
    private static final Set<String> SPLIT_DONE = Set.of(StlBill.SPLIT, StlBill.SPLIT_CONFIRMED);

    private final BillMapper billMapper;
    private final ai.neargo.shop.pay.service.impl.RefundCapacityLedger capacity;
    private final BillRefundJournal journal;
    private final SettlementRefundPolicy policy;
    private final PreSplitAdjuster preSplit;
    private final PostSplitReverser postSplit;
    private final PointsSettlementRefundHoldMapper holds;
    private final PointsRefundPort pointsRefunds;
    private final ai.neargo.shop.pay.mapper.CashRefundSplitMapper cashReversals;

    public SettlementRefundCoordinator(BillMapper billMapper, BillRefundJournal journal, SettlementRefundPolicy policy,
                                   PreSplitAdjuster preSplit, PostSplitReverser postSplit,
                                   PointsSettlementRefundHoldMapper holds, PointsRefundPort pointsRefunds,
                                   ai.neargo.shop.pay.mapper.CashRefundSplitMapper cashReversals,
                                   ai.neargo.shop.pay.service.impl.RefundCapacityLedger capacity) {
        this.capacity = capacity;
        this.billMapper = billMapper;
        this.journal = journal;
        this.policy = policy;
        this.preSplit = preSplit;
        this.postSplit = postSplit;
        this.holds = holds;
        this.pointsRefunds = pointsRefunds;
        this.cashReversals = cashReversals;
    }

    /** @return false = 回退没做成，调用方<b>必须停止退款</b>（钱还在商家那边） */
    @Transactional("payTxManager")
    public boolean reverse(SplitReversal cmd) {
        return reverse(cmd, false);
    }

    /** Never call the legacy transactional remote reversal from the new source workflow. */
    @Transactional("payTxManager")
    public boolean reversePrepared(SplitReversal cmd) {
        if (cmd == null || cmd.afterSaleNo() == null || cmd.subOrderNo() == null
                || cmd.goodsMinor() < 0 || cmd.freightMinor() < 0 || cmd.goodsPaidMinor() < 0)
            throw new IllegalArgumentException("SETTLEMENT_REFUND_HOLD_INVALID");
        StlBill bill = DataScopeContext.executeWithoutScope(() -> billMapper.selectOne(
                Wrappers.<StlBill>lambdaQuery().eq(StlBill::getSubOrderNo, cmd.subOrderNo())
                        .last("LIMIT 1 FOR UPDATE")));
        if (bill == null) return true;
        var prior = holds.find(cmd.afterSaleNo());
        if (prior != null) {
            if (!bill.getSettleNo().equals(prior.get("settle_no"))
                    || !cmd.subOrderNo().equals(prior.get("sub_order_no"))
                    || cmd.goodsMinor() != number(prior, "goods_minor")
                    || cmd.freightMinor() != number(prior, "freight_minor")
                    || cmd.goodsPaidMinor() != number(prior, "goods_paid_minor"))
                throw new IllegalArgumentException("SETTLEMENT_REFUND_HOLD_CONFLICT");
            return !"CANCELLED".equals(prior.get("status"));
        }
        if (journal.recorded(cmd.afterSaleNo()))
            throw new IllegalArgumentException("SETTLEMENT_REFUND_HOLD_CONFLICT");
        if (holds.pendingForBill(bill.getSettleNo()) > 0 || StlBill.SPLITTING.equals(bill.getStatus())
                || splitDone(bill) || !StlBill.PENDING.equals(bill.getStatus())) return false;
        holds.create(cmd.afterSaleNo(), bill.getSettleNo(), cmd.subOrderNo(),
                cmd.goodsMinor(), cmd.freightMinor(), cmd.goodsPaidMinor());
        return true;
    }

    private long number(java.util.Map<String, Object> row, String field) {
        return ((Number) row.get(field)).longValue();
    }

    @Transactional("payTxManager")
    public boolean confirmPrepared(SplitReversal cmd, String preparationNo, String inputHash) {
        var proof = pointsRefunds.recoveryStatus(preparationNo, inputHash);
        requireProof(cmd, proof, "CONFIRMED");
        if (proof.netCashMinor() > 0 && (proof.cashRefundPaymentNo() == null
                || proof.cashRefundPaymentNo().isBlank()))
            throw new IllegalArgumentException("SETTLEMENT_REFUND_PROOF_INVALID");
        StlBill bill = lockBill(cmd.subOrderNo());
        if (bill == null) return true;
        var hold = hold(cmd, bill);
        if (hold == null) throw new IllegalArgumentException("SETTLEMENT_REFUND_HOLD_MISSING");
        if ("CONFIRMED".equals(hold.get("status"))) {
            requireProofNo(hold, preparationNo);
            return true;
        }
        if (!"PENDING".equals(hold.get("status")))
            throw new IllegalArgumentException("SETTLEMENT_REFUND_HOLD_CONFLICT");
        if (splitDone(bill) || StlBill.SPLITTING.equals(bill.getStatus()))
            throw new IllegalStateException("SETTLEMENT_REFUND_HOLD_VIOLATED");
        if (!reverse(cmd, true)) return false;
        if (holds.finish(cmd.afterSaleNo(), "CONFIRMED", preparationNo) != 1)
            throw new IllegalStateException("SETTLEMENT_REFUND_HOLD_CONFLICT");
        return true;
    }

    @Transactional("payTxManager")
    public boolean cancelPrepared(SplitReversal cmd, String preparationNo, String inputHash) {
        var proof = pointsRefunds.recoveryStatus(preparationNo, inputHash);
        requireProof(cmd, proof, "ABORTED");
        if ((proof.collectionRequired() && !proof.compensationSucceeded())
                || proof.cashRefundPaymentNo() == null)
            throw new IllegalArgumentException("SETTLEMENT_REFUND_COMPENSATION_REQUIRED");
        StlBill bill = lockBill(cmd.subOrderNo());
        if (bill == null) { capacity.releaseAfterRecovery(proof); return true; }
        var hold = hold(cmd, bill);
        if (hold == null) throw new IllegalArgumentException("SETTLEMENT_REFUND_HOLD_MISSING");
        if ("CANCELLED".equals(hold.get("status"))) {
            requireProofNo(hold, preparationNo);
            capacity.releaseAfterRecovery(proof);
            return true;
        }
        if (!"PENDING".equals(hold.get("status")) || journal.recorded(cmd.afterSaleNo()))
            throw new IllegalArgumentException("SETTLEMENT_REFUND_HOLD_CONFLICT");
        if (splitDone(bill) || StlBill.SPLITTING.equals(bill.getStatus()))
            throw new IllegalStateException("SETTLEMENT_REFUND_HOLD_VIOLATED");
        if (holds.finish(cmd.afterSaleNo(), "CANCELLED", preparationNo) != 1)
            throw new IllegalStateException("SETTLEMENT_REFUND_HOLD_CONFLICT");
        capacity.releaseAfterRecovery(proof);
        return true;
    }

    private void requireProof(SplitReversal cmd, PointsRefundPort.RefundRecoveryStatus proof,
                              String state) {
        if (cmd == null || proof == null || !cmd.afterSaleNo().equals(proof.afterSaleNo())
                || !cmd.subOrderNo().equals(proof.subOrderNo()) || !state.equals(proof.state()))
            throw new IllegalArgumentException("SETTLEMENT_REFUND_PROOF_INVALID");
    }

    private void requireProofNo(java.util.Map<String, Object> hold, String preparationNo) {
        if (!preparationNo.equals(hold.get("proof_no")))
            throw new IllegalArgumentException("SETTLEMENT_REFUND_HOLD_CONFLICT");
    }

    private java.util.Map<String, Object> hold(SplitReversal cmd, StlBill bill) {
        var hold = holds.find(cmd.afterSaleNo());
        if (hold != null && (!bill.getSettleNo().equals(hold.get("settle_no"))
                || !cmd.subOrderNo().equals(hold.get("sub_order_no"))
                || cmd.goodsMinor() != number(hold, "goods_minor")
                || cmd.freightMinor() != number(hold, "freight_minor")
                || cmd.goodsPaidMinor() != number(hold, "goods_paid_minor")))
            throw new IllegalArgumentException("SETTLEMENT_REFUND_HOLD_CONFLICT");
        return hold;
    }

    private StlBill lockBill(String subOrderNo) {
        return DataScopeContext.executeWithoutScope(() -> billMapper.selectOne(
                Wrappers.<StlBill>lambdaQuery().eq(StlBill::getSubOrderNo, subOrderNo)
                        .last("LIMIT 1 FOR UPDATE")));
    }

    private boolean reverse(SplitReversal cmd, boolean allowHeld) {
        StlBill bill = DataScopeContext.executeWithoutScope(() -> billMapper.selectOne(
                Wrappers.<StlBill>lambdaQuery().eq(StlBill::getSubOrderNo, cmd.subOrderNo())
                        .last("LIMIT 1 FOR UPDATE")));
        if (bill == null || journal.recorded(cmd.afterSaleNo())) {
            // 没有结算单（未支付即取消）/ 已全部回退 / 本售后单已处理过（重放）：都视为成功
            return true;
        }
        if (cashReversals.pending(bill.getSettleNo())>0) return false;
        if (!allowHeld && holds.pendingForBill(bill.getSettleNo()) > 0) return false;
        if (StlBill.REVERSED.equals(bill.getStatus())) return !allowHeld;
        if (StlBill.SPLITTING.equals(bill.getStatus())) {
            log.warn("[settle-refund] 结算单 {} 分账进行中，售后 {} 稍后重试", bill.getSettleNo(), cmd.afterSaleNo());
            return false;
        }
        BillRefundShare share = policy.share(bill, cmd.goodsMinor(), cmd.freightMinor(), cmd.goodsPaidMinor());
        if (splitDone(bill)) {
            return postSplit.reverse(bill, share, cmd.afterSaleNo(), splitReturn(bill,cmd));
        }
        preSplit.adjust(bill, share, cmd.afterSaleNo());
        return true;
    }

    /** Reverse the original split command, not the merchant net payable (different money directions). */
    public long splitReturn(StlBill bill,SplitReversal cmd) {
        Long amount=bill.getSplitAmountMinor();
        if(amount==null || amount<0) throw new IllegalStateException("SETTLEMENT_SPLIT_AMOUNT_REQUIRED");
        long alreadyBeforeSplit=journal.preSplitGoods(bill.getSettleNo());
        long before=Math.subtractExact(bill.getRefundedGoodsMinor()==null?0:bill.getRefundedGoodsMinor(),alreadyBeforeSplit);
        long remainingBase=Math.subtractExact(cmd.goodsPaidMinor(),alreadyBeforeSplit);
        if(before<0 || remainingBase<0) throw new IllegalStateException("SETTLEMENT_SPLIT_REFUND_BASIS_INVALID");
        if(remainingBase==0) return amount;
        var total=java.math.BigInteger.valueOf(amount);
        var base=java.math.BigInteger.valueOf(remainingBase);
        return total.multiply(java.math.BigInteger.valueOf(Math.addExact(before,cmd.goodsMinor()))).divide(base)
                .subtract(total.multiply(java.math.BigInteger.valueOf(before)).divide(base)).longValueExact();
    }

    /** 转人工的单要看分账发过没有：发过（splitAt 有值）就是钱已动过，同样经通道回退。 */
    private static boolean splitDone(StlBill bill) {
        return SPLIT_DONE.contains(bill.getStatus())
                || (StlBill.MANUAL.equals(bill.getStatus()) && bill.getSplitAt() != null);
    }
}
