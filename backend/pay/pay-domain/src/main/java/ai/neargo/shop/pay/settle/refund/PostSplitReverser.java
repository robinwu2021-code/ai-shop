package ai.neargo.shop.pay.settle.refund;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.entity.StlSplitLog;
import ai.neargo.shop.pay.mapper.BillRefundMapper;
import ai.neargo.shop.pay.settle.SplitInstructionSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * <b>分账后</b>的退款：按份额向通道发「部分分账回退」，回退单号 {@code REV-售后单号}（一张售后单一笔，R3）。
 *
 * <p>回退成功后再收回补贴（{@code SUBR-售后单号}）：钱先回到二级商户，再从里面拿回平台。
 * 补贴收回失败不阻断退款 —— 买家的钱必须能退，补贴是可追的。
 * 货款全部退完才转 REVERSED，否则保持原状态。
 */
@Component
public class PostSplitReverser {

    private static final Logger log = LoggerFactory.getLogger(PostSplitReverser.class);

    private final BillRefundMapper mapper;
    private final BillRefundJournal journal;
    private final SplitInstructionSender sender;

    public PostSplitReverser(BillRefundMapper mapper, BillRefundJournal journal, SplitInstructionSender sender) {
        this.mapper = mapper;
        this.journal = journal;
        this.sender = sender;
    }

    /** @return false = 通道回退失败（结算单已转人工），调用方<b>必须停止退款</b> */
    public boolean reverse(StlBill bill, BillRefundShare share, String afterSaleNo) {
        long amount = share.merchantReverseMinor();
        if (amount > 0 && !sender.send(StlSplitLog.REVERSE, bill, "REV-" + afterSaleNo, amount)) {
            DataScopeContext.executeWithoutScope(() -> mapper.markManual(
                    bill.getSettleNo(), bill.getStatus(), "分账回退失败，需人工处理（售后 " + afterSaleNo + "）"));
            return false;
        }
        returnSubsidy(bill, share, afterSaleNo);
        journal.append(bill, share, afterSaleNo, false);
        String next = share.finalRefund() ? StlBill.REVERSED : bill.getStatus();
        long before = bill.getRefundedGoodsMinor() == null ? 0L : bill.getRefundedGoodsMinor();
        int rows = DataScopeContext.executeWithoutScope(() -> mapper.recordAfterSplit(
                bill.getSettleNo(), share.goodsMinor(), amount, before, bill.getStatus(), next));
        if (rows != 1) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return true;
    }

    private void returnSubsidy(StlBill bill, BillRefundShare share, String afterSaleNo) {
        if (share.subsidyReturnMinor() <= 0 || bill.getSubsidyAt() == null) {
            return;
        }
        if (!sender.send(StlSplitLog.SUBSIDY_RETURN, bill, "SUBR-" + afterSaleNo, share.subsidyReturnMinor())) {
            log.warn("补差回退失败，需人工追回 settleNo={} afterSale={} subsidy={}",
                    bill.getSettleNo(), afterSaleNo, share.subsidyReturnMinor());
        }
    }
}
