package ai.neargo.shop.pay.points.store.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.mapper.points.PointsConfigurationMapper;
import ai.neargo.shop.pay.points.store.PointsCashRefundReadStore;
import org.springframework.stereotype.Repository;

/** Pay SUCCESS is written by verified callback/reconciliation; acceptance alone remains PENDING. */
@Repository
public class MybatisPointsCashRefundReadStore implements PointsCashRefundReadStore {
    private final PointsConfigurationMapper mapper;
    public MybatisPointsCashRefundReadStore(PointsConfigurationMapper mapper) { this.mapper=mapper; }
    @Override public boolean hasRefundForAfterSale(String no) {
        return DataScopeContext.executeWithoutScope(() -> mapper.cashRefundCount(no)>0);
    }
    @Override public CashRefund refund(String no) {
        return DataScopeContext.executeWithoutScope(() -> {
            var row=mapper.cashRefund(no);
            if (row==null) { return null; }
            return new CashRefund((String)row.get("payment_no"),(String)row.get("direction"),
                    (String)row.get("after_sale_no"),(String)row.get("sub_order_no"),(String)row.get("user_no"),
                    (String)row.get("currency"),((Number)row.get("amount_minor")).longValue(),(String)row.get("status"),
                    (String)row.get("pay_channel"),(String)row.get("refund_verified_provider_no"),
                    row.get("succeeded_at")==null?null:
                    ((Number)row.get("succeeded_at")).longValue(),row.get("refund_verified_minor")==null?null:
                    ((Number)row.get("refund_verified_minor")).longValue(),row.get("refund_verified_at")==null?null:
                    ((Number)row.get("refund_verified_at")).longValue());
        });
    }
}
