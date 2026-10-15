package ai.neargo.shop.pay.settle.refund;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.entity.StlBillRefund;
import ai.neargo.shop.pay.mapper.BillRefundMapper;
import ai.neargo.shop.spi.settle.SettlePort.SplitReversal;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.Objects;
import java.util.List;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 结算单退款流水：按售后单号判重（R3），并记下每一笔在结算单上的份额。 */
@Component
public class BillRefundJournal {

    private final BillRefundMapper mapper;

    public BillRefundJournal(BillRefundMapper mapper) {
        this.mapper = mapper;
    }

    public boolean recorded(String afterSaleNo) {
        return DataScopeContext.executeWithoutScope(() -> mapper.countByAfterSale(afterSaleNo)) > 0;
    }

    public boolean replayMatches(StlBill bill, SplitReversal cmd) {
        var row = DataScopeContext.executeWithoutScope(() -> mapper.selectOne(
                Wrappers.<StlBillRefund>lambdaQuery().eq(StlBillRefund::getAfterSaleNo, cmd.afterSaleNo())));
        if (row == null) return false;
        if (!Objects.equals(row.getSettleNo(), bill.getSettleNo())
                || !Objects.equals(row.getSubOrderNo(), cmd.subOrderNo())
                || !Objects.equals(row.getGoodsMinor(), cmd.goodsMinor())
                || !Objects.equals(row.getFreightMinor(), cmd.freightMinor())) {
            throw new IllegalArgumentException("Refund replay key belongs to another immutable request");
        }
        return true;
    }

    /** 旧部分切分缺完整原值份额时不继续自动累计，也不把未知历史补零。 */
    public boolean historyReady(StlBill bill) {
        List<StlBillRefund> rows = DataScopeContext.executeWithoutScope(() -> mapper.selectList(
                Wrappers.<StlBillRefund>lambdaQuery().eq(StlBillRefund::getSettleNo, bill.getSettleNo())));
        if (rows.stream().anyMatch(row -> !complete(row))) return false;
        long goods = rows.stream().mapToLong(StlBillRefund::getGoodsMinor).sum();
        long freight = rows.stream().mapToLong(StlBillRefund::getFreightMinor).sum();
        return Objects.equals(bill.getRefundedGoodsMinor(), goods)
                && Objects.equals(bill.getRefundedFreightMinor(), freight);
    }

    static boolean complete(StlBillRefund row) {
        return row.getCashAllocation() != null && row.getGoodsMinor() != null && row.getFreightMinor() != null
                && row.getMerchantGoodsMinor() != null && row.getFreightReverseMinor() != null
                && row.getGrossCutMinor() != null && row.getCommissionCutMinor() != null
                && row.getServiceFeeCutMinor() != null && row.getPointsFeeCutMinor() != null
                && row.getPointsCostCutMinor() != null;
    }

    /** 落一行。并发重放同一售后单时第二行撞 {@code uk_stl_bill_refund}，整笔回滚。 */
    public void append(StlBill bill, BillRefundShare share, String afterSaleNo, boolean preSplit) {
        StlBillRefund row = new StlBillRefund();
        row.setSettleNo(bill.getSettleNo());
        row.setSubOrderNo(bill.getSubOrderNo());
        row.setAfterSaleNo(afterSaleNo);
        row.setGoodsMinor(share.goodsMinor());
        row.setFreightMinor(share.freightMinor());
        row.setMerchantReverseMinor(share.merchantReverseMinor());
        row.setMerchantGoodsMinor(share.merchantGoodsMinor());
        row.setGrossCutMinor(share.grossCutMinor());
        row.setCommissionCutMinor(share.commissionCutMinor());
        row.setServiceFeeCutMinor(share.serviceFeeCutMinor());
        row.setPointsFeeCutMinor(share.pointsFeeCutMinor());
        row.setPointsCostCutMinor(share.pointsCostCutMinor());
        row.setFreightReverseMinor(share.freightReverseMinor());
        row.setCashAllocation(share.cashAllocation().name());
        row.setSubsidyReturnMinor(share.subsidyReturnMinor());
        row.setPlatformCutMinor(share.platformCutMinor());
        row.setPreSplit(preSplit);
        row.setFinalRefund(share.finalRefund());
        row.setAt(System.currentTimeMillis());
        row.setTenantNo("MAIN");
        row.setCreatedAt(LocalDateTime.now());
        DataScopeContext.executeWithoutScope(() -> mapper.insert(row));
    }
}
