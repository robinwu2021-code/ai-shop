package ai.neargo.shop.pay.settle.refund;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.entity.StlBillRefund;
import ai.neargo.shop.pay.mapper.BillRefundMapper;
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

    public long preSplitGoods(String settleNo) {
        return DataScopeContext.executeWithoutScope(() -> mapper.preSplitGoods(settleNo));
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
