package ai.neargo.shop.pay.acquiring.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.acquiring.entity.PayOrder;
import ai.neargo.shop.pay.acquiring.entity.PayRefund;
import ai.neargo.shop.pay.acquiring.service.RefundOutcomeService;
import ai.neargo.shop.pay.acquiring.statemachine.PayOrderEvent;
import ai.neargo.shop.pay.acquiring.statemachine.PayOrderStateMachine;
import ai.neargo.shop.pay.acquiring.statemachine.PayOrderStatus;
import ai.neargo.shop.pay.acquiring.statemachine.PayRefundStatus;
import ai.neargo.shop.pay.funds.FundsPaths;
import ai.neargo.shop.pay.ledger.service.LedgerService;
import ai.neargo.shop.pay.mapper.acquiring.AcquiringMappers.PayOrderMapper;
import ai.neargo.shop.pay.mapper.acquiring.AcquiringMappers.PayRefundMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** {@link RefundOutcomeService} 的实现（TDD-支付服务-I3支付单退款流水 S6）。在执行器的退款事务里执行 */
@Service
public class RefundOutcomeServiceImpl implements RefundOutcomeService {

    private final PayRefundMapper refunds;
    private final PayOrderMapper orders;
    private final FundsPaths funds;
    private final LedgerService ledger;
    private final boolean ledgerEnabled;

    public RefundOutcomeServiceImpl(PayRefundMapper refunds, PayOrderMapper orders, FundsPaths funds,
                                    LedgerService ledger, @Value("${shop.pay.ledger.enabled:true}") boolean ledgerEnabled) {
        this.refunds = refunds;
        this.orders = orders;
        this.funds = funds;
        this.ledger = ledger;
        this.ledgerEnabled = ledgerEnabled;
    }

    @Override
    public void succeeded(String executionNo) {
        PayRefund pr = open(executionNo);
        if (pr == null) {
            return;
        }
        PayOrder o = orders.lockById(pr.getPayOrderId());
        long refunded = nz(o.getRefundedMinor()) + pr.getAmountMinor();
        PayOrder patch = new PayOrder();
        patch.setId(o.getId());
        patch.setRefundedMinor(refunded);
        patch.setReservedRefundMinor(Math.max(0, nz(o.getReservedRefundMinor()) - pr.getAmountMinor()));
        PayOrderEvent event = refunded >= nz(o.getPaidMinor()) ? PayOrderEvent.REFUNDED : PayOrderEvent.PARTIALLY_REFUNDED;
        PayOrderStatus from = PayOrderStatus.valueOf(o.getStatus());
        if (PayOrderStateMachine.allows(from, event)) {
            patch.setStatus(PayOrderStateMachine.next(from, event).name());
        }
        DataScopeContext.executeWithoutScope(() -> orders.updateById(patch));
        PayRefund done = new PayRefund();
        done.setId(pr.getId());
        done.setStatus(PayRefundStatus.SUCCEEDED.name());
        done.setSucceededAt(LocalDateTime.now(ZoneOffset.UTC));
        DataScopeContext.executeWithoutScope(() -> refunds.updateById(done));
        if (ledgerEnabled) {
            funds.of(o.getFundsPath()).ifPresent(strategy -> ledger.post(
                    strategy.onRefundSucceeded(o, pr.getId(), pr.getAmountMinor(), o.getPayChannel())));
        }
    }

    @Override
    public void failed(String executionNo, String reason) {
        PayRefund pr = open(executionNo);
        if (pr == null) {
            return;
        }
        PayOrder o = orders.lockById(pr.getPayOrderId());
        PayOrder release = new PayOrder();
        release.setId(o.getId());
        release.setReservedRefundMinor(Math.max(0, nz(o.getReservedRefundMinor()) - pr.getAmountMinor()));
        DataScopeContext.executeWithoutScope(() -> orders.updateById(release));
        PayRefund done = new PayRefund();
        done.setId(pr.getId());
        done.setStatus(PayRefundStatus.FAILED.name());
        done.setFailedReason(reason);
        DataScopeContext.executeWithoutScope(() -> refunds.updateById(done));
    }

    /** 挂着、且还没到终态的退款单；存量行与已结的返回空 */
    private PayRefund open(String executionNo) {
        PayRefund pr = DataScopeContext.executeWithoutScope(() -> refunds.selectOne(Wrappers.<PayRefund>lambdaQuery()
                .eq(PayRefund::getOutRefundNo, executionNo).orderByDesc(PayRefund::getId).last("LIMIT 1")));
        if (pr == null) {
            return null;
        }
        PayRefund locked = refunds.lockById(pr.getId());
        return locked == null || PayRefundStatus.valueOf(locked.getStatus()).terminal() ? null : locked;
    }

    private static long nz(Long v) {
        return v == null ? 0 : v;
    }
}
