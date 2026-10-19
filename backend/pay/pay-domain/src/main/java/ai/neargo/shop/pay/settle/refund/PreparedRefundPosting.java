package ai.neargo.shop.pay.settle.refund;

import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.mapper.BillRefundMapper;
import ai.neargo.shop.pay.mapper.CashRefundSplitMapper;
import ai.neargo.shop.pay.mapper.PointsSettlementRefundHoldMapper;
import ai.neargo.shop.pay.mapper.SettleMappers.BillMapper;
import ai.neargo.shop.pay.settle.refund.impl.CashRefundSplitServiceImpl.Frozen;
import ai.neargo.shop.spi.settle.SettlePort.SplitReversal;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Local posting only. Bank reversals are durable facts, not evidence of a completed goods refund. */
@Component
public class PreparedRefundPosting {
    private final BillRefundMapper refunds;
    private final CashRefundSplitMapper reversals;
    private final PointsSettlementRefundHoldMapper holds;
    private final BillMapper bills;
    private final BillRefundJournal journal;
    private final PreparedRefundShares shares;
    private final PreSplitAdjuster unpaid;
    private final ObjectMapper json = new ObjectMapper();

    public PreparedRefundPosting(BillRefundMapper refunds, CashRefundSplitMapper reversals,
            PointsSettlementRefundHoldMapper holds, BillMapper bills, BillRefundJournal journal,
            PreparedRefundShares shares, PreSplitAdjuster unpaid) {
        this.refunds=refunds; this.reversals=reversals; this.holds=holds; this.bills=bills;
        this.journal=journal; this.shares=shares; this.unpaid=unpaid;
    }

    public boolean confirm(StlBill bill, SplitReversal command, boolean split) {
        if (journal.recorded(command.afterSaleNo())) return true;
        if (split) {
            var row=reversals.find(command.afterSaleNo());
            if (row==null || !"CONFIRMED".equals(row.get("state"))) return false;
            Frozen saved=json.readValue((String)row.get("command_json"),Frozen.class);
            require(saved.deferred() && saved.source().equals(command)
                    && saved.before()==before(bill),"SETTLEMENT_REFUND_REVERSAL_MISMATCH");
            journal.append(bill,saved.share(),command.afterSaleNo(),false);
            String next=saved.share().finalRefund()?StlBill.REVERSED:bill.getStatus();
            // The bank movement was already recorded; only goods progress becomes final here.
            require(refunds.recordAfterSplit(bill.getSettleNo(),command.goodsMinor(),0,before(bill),
                    bill.getStatus(),next)==1,"SETTLEMENT_REFUND_POSTING_CONFLICT");
        } else if (bill.getPaidAt()!=null || StlBill.PAID.equals(bill.getStatus())) {
            var share=shares.share(bill,command);
            journal.append(bill,share,command.afterSaleNo(),false);
            require(refunds.recordAfterSplit(bill.getSettleNo(),command.goodsMinor(),0,before(bill),
                    bill.getStatus(),bill.getStatus())==1,"SETTLEMENT_REFUND_POSTING_CONFLICT");
            long recovery=share.merchantReverseMinor();
            if (recovery>0) {
                require(holds.requireRecovery(command.afterSaleNo(),recovery)==1,"SETTLEMENT_REFUND_RECOVERY_CONFLICT");
                bills.update(null,Wrappers.<StlBill>lambdaUpdate().eq(StlBill::getSettleNo,bill.getSettleNo())
                        .set(StlBill::getLastError,"SUPPLIER_REFUND_RECOVERY_PENDING: "+command.afterSaleNo()+" / "+recovery));
            }
        } else {
            var share=shares.share(bill,command);
            unpaid.adjust(bill,share,command.afterSaleNo());
            if (StlBill.CONFIRMED.equals(bill.getStatus()) && !share.finalRefund()) {
                bills.update(null,Wrappers.<StlBill>lambdaUpdate().eq(StlBill::getSettleNo,bill.getSettleNo())
                        .set(StlBill::getStatus,StlBill.PENDING_RECON));
            }
        }
        return true;
    }

    public void cancel(StlBill bill, SplitReversal command) {
        var row=reversals.find(command.afterSaleNo());
        if (row==null) return;
        // Unknown bank outcomes must retain the hold even if the cash refund is definitely closed.
        require("CONFIRMED".equals(row.get("state")),"SETTLEMENT_REFUND_REVERSAL_UNKNOWN");
        Frozen saved=json.readValue((String)row.get("command_json"),Frozen.class);
        require(saved.deferred() && saved.source().equals(command),"SETTLEMENT_REFUND_REVERSAL_MISMATCH");
        if (saved.cash().amountMinor()>0) {
            bills.update(null,Wrappers.<StlBill>lambdaUpdate().eq(StlBill::getSettleNo,bill.getSettleNo())
                    .set(StlBill::getStatus,StlBill.MANUAL)
                    .set(StlBill::getLastError,"SPLIT_REVERSED_WITHOUT_CASH_REFUND: "+command.afterSaleNo()));
        }
    }

    private long before(StlBill bill) { return bill.getRefundedGoodsMinor()==null?0:bill.getRefundedGoodsMinor(); }
    private void require(boolean valid,String message) { if (!valid) throw new IllegalStateException(message); }
}
