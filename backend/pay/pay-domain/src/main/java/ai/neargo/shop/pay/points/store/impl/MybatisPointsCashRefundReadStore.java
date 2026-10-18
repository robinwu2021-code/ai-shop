package ai.neargo.shop.pay.points.store.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.mapper.points.PointsConfigurationMapper;
import ai.neargo.shop.pay.mapper.SettleMappers.PaymentMapper;
import ai.neargo.shop.pay.points.store.PointsCashRefundReadStore;
import ai.neargo.shop.pay.policy.RefundOutcomePolicy;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Repository
public class MybatisPointsCashRefundReadStore implements PointsCashRefundReadStore {
    private final PointsConfigurationMapper mapper;
    private final PaymentMapper payments;
    public MybatisPointsCashRefundReadStore(PointsConfigurationMapper mapper, PaymentMapper payments) {
        this.mapper=mapper; this.payments=payments;
    }
    @Override public boolean hasRefundForAfterSale(String no) {
        return DataScopeContext.executeWithoutScope(() -> mapper.cashRefundCount(no)>0);
    }
    @Override public CashRefund refund(String no) { return read(no,false); }
    @Override @Transactional(value="payTxManager",propagation=Propagation.MANDATORY)
    public CashRefund lockRefund(String no) { return read(no,true); }
    private CashRefund read(String no,boolean lock) {
        return DataScopeContext.executeWithoutScope(() -> {
            var row=payments.selectOne(Wrappers.<StlPayment>lambdaQuery()
                    .eq(StlPayment::getPaymentNo,no).eq(StlPayment::getDirection,StlPayment.REFUND));
            if(row==null)return null;
            var originQuery=Wrappers.<StlPayment>lambdaQuery().eq(StlPayment::getDirection,StlPayment.PAY)
                    .eq(StlPayment::getOrderNo,row.getOrderNo())
                    .eq(row.getOriginPaymentNo()!=null,StlPayment::getPaymentNo,row.getOriginPaymentNo())
                    .eq(StlPayment::getStatus,StlPayment.SUCCESS).orderByAsc(StlPayment::getId)
                    .last(lock?"LIMIT 1 FOR UPDATE":"LIMIT 1");
            var origin=payments.selectOne(originQuery);
            var current=lock?payments.lockRefundEvidence(no):row;
            var safety=RefundOutcomePolicy.resolve(current,origin!=null && "FROZEN".equals(origin.getRefundCapacityState()));
            return new CashRefund(current.getPaymentNo(),current.getDirection(),current.getAfterSaleNo(),
                    current.getSubOrderNo(),current.getUserNo(),current.getCurrency(),current.getAmountMinor(),
                    current.getStatus(),current.getPayChannel(),current.getRefundVerifiedProviderNo(),
                    current.getSucceededAt(),current.getRefundVerifiedMinor(),current.getRefundVerifiedAt(),safety);
        });
    }
}
