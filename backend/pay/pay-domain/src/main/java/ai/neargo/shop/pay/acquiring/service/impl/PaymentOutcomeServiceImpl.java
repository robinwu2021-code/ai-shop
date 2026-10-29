package ai.neargo.shop.pay.acquiring.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.acquiring.dto.ChannelOutcome;
import ai.neargo.shop.pay.acquiring.entity.PayException;
import ai.neargo.shop.pay.acquiring.entity.PayOrder;
import ai.neargo.shop.pay.acquiring.service.PaymentOutcomeService;
import ai.neargo.shop.pay.acquiring.statemachine.PayOrderEvent;
import ai.neargo.shop.pay.acquiring.statemachine.PayOrderStateMachine;
import ai.neargo.shop.pay.acquiring.statemachine.PayOrderStatus;
import ai.neargo.shop.pay.mapper.acquiring.AcquiringMappers.PayExceptionMapper;
import ai.neargo.shop.pay.mapper.acquiring.AcquiringMappers.PayOrderMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.neargo.shop.pay.funds.FundsPaths;
import ai.neargo.shop.pay.ledger.service.LedgerService;
import ai.neargo.shop.pay.notify.service.BizNotifyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** {@link PaymentOutcomeService} 的实现（TDD-支付服务-I3支付单退款流水 S4）。在调用方（收款账本）的支付事务里执行 */
@Service
public class PaymentOutcomeServiceImpl implements PaymentOutcomeService {

    private static final Logger log = LoggerFactory.getLogger(PaymentOutcomeServiceImpl.class);
    static final String LATE_PAYMENT = "LATE_PAYMENT";
    static final String AMOUNT_MISMATCH = "AMOUNT_MISMATCH";
    static final String DUPLICATE_PAYMENT = "DUPLICATE_PAYMENT";

    private final PayOrderMapper orders;
    private final PayExceptionMapper exceptions;
    private final FundsPaths funds;
    private final LedgerService ledger;
    private final boolean ledgerEnabled;
    private final BizNotifyService notifier;

    public PaymentOutcomeServiceImpl(PayOrderMapper orders, PayExceptionMapper exceptions, FundsPaths funds,
                                     LedgerService ledger,
                                     @Value("${shop.pay.ledger.enabled:true}") boolean ledgerEnabled,
                                     BizNotifyService notifier) {
        this.notifier = notifier;
        this.orders = orders;
        this.exceptions = exceptions;
        this.funds = funds;
        this.ledger = ledger;
        this.ledgerEnabled = ledgerEnabled;
    }

    @Override
    public boolean apply(ChannelOutcome outcome) {
        if (outcome.payOrderId() == null) {
            return false;
        }
        PayOrder o = orders.lockById(outcome.payOrderId());
        if (o == null) {
            return false;
        }
        PayOrderStatus status = PayOrderStatus.valueOf(o.getStatus());
        if (status == PayOrderStatus.CLOSED || o.getCloseRequestedAt() != null) {
            // 业务已终止或已接受关闭意图：钱是真的到了，也必须进异常退款，不能悄悄记正常成功。
            return exception(LATE_PAYMENT, o, outcome);
        }
        if (status == PayOrderStatus.SUCCEEDED || status == PayOrderStatus.PARTIALLY_REFUNDED
                || status == PayOrderStatus.REFUNDED) {
            // 同一张单另一笔尝试也成功了：重复支付，钱要退回给顾客
            return exception(DUPLICATE_PAYMENT, o, outcome);
        }
        if (outcome.channelAmountMinor() != o.getAmountMinor()
                || !Objects.equals(outcome.channelCurrency(), o.getCurrency())) {
            return exception(AMOUNT_MISMATCH, o, outcome);
        }
        PayOrder patch = new PayOrder();
        patch.setId(o.getId());
        patch.setStatus(PayOrderStateMachine.next(status, PayOrderEvent.SUCCEEDED).name());
        patch.setPaidMinor(outcome.channelAmountMinor());
        patch.setSucceededAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(outcome.succeededAt()), ZoneOffset.UTC));
        DataScopeContext.executeWithoutScope(() -> orders.updateById(patch));
        if (ledgerEnabled && outcome.channelAmountMinor() > 0) {
            // 同事务记一张凭证（S5）：支付单成功与流水要么一起在、要么一起不在。免单没有钱动，不记；
            // 未冻结资金路径的存量单留给回填 B7
            funds.of(o.getFundsPath()).ifPresent(strategy -> ledger.post(
                    strategy.onPaymentSucceeded(o, outcome.channelAmountMinor(), outcome.payChannel())));
        }
        // 答复与成功同事务落行（S6）：成功在、答复就在；投递在事务外
        notifier.paymentSucceeded(DataScopeContext.executeWithoutScope(() -> orders.selectById(o.getId())));
        return true;
    }

    private boolean exception(String kind, PayOrder o, ChannelOutcome outcome) {
        log.error("[pay-outcome] 支付单 {} 状态 {} 收到渠道成功（{} {} {}）：记 {}，不进成功", o.getPayOrderNo(), o.getStatus(),
                outcome.channelTradeNo(), outcome.channelAmountMinor(), outcome.channelCurrency(), kind);
        PayException e = new PayException();
        e.setKind(kind);
        e.setPayOrderId(o.getId());
        e.setStlPaymentId(outcome.stlPaymentId());
        e.setAmountMinor(outcome.channelAmountMinor());
        e.setCurrency(outcome.channelCurrency());
        e.setStatus("OPEN");
        try {
            DataScopeContext.executeWithoutScope(() -> exceptions.insert(e));
        } catch (DuplicateKeyException alreadyRecorded) {
            // uk (kind, stl_payment_id)：渠道重推同一笔，异常只记一次
        }
        return false;
    }
}
