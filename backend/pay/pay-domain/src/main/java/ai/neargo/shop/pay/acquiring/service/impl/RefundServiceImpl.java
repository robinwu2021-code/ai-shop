package ai.neargo.shop.pay.acquiring.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.access.PayAction;
import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.channel.api.CallerType;
import ai.neargo.shop.pay.access.policy.CallerPolicy;
import ai.neargo.shop.pay.acquiring.dto.RefundRequestCmd;
import ai.neargo.shop.pay.acquiring.entity.PayOrder;
import ai.neargo.shop.pay.acquiring.entity.PayRefund;
import ai.neargo.shop.pay.acquiring.entity.PayRefundReviewRule;
import ai.neargo.shop.pay.acquiring.policy.RefundReviewPolicy;
import ai.neargo.shop.pay.acquiring.service.PayExceptionService;
import ai.neargo.shop.pay.acquiring.service.RefundService;
import ai.neargo.shop.pay.acquiring.statemachine.PayRefundStatus;
import ai.neargo.shop.pay.approval.entity.PayApproval;
import ai.neargo.shop.pay.approval.service.ApprovalService;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.funds.BizDates;
import ai.neargo.shop.pay.mapper.SettleMappers.PaymentMapper;
import ai.neargo.shop.pay.mapper.acquiring.AcquiringMappers.PayOrderMapper;
import ai.neargo.shop.pay.mapper.acquiring.AcquiringMappers.PayRefundMapper;
import ai.neargo.shop.pay.mapper.acquiring.AcquiringMappers.RefundReviewRuleMapper;
import ai.neargo.shop.pay.service.RefundExecutionService;
import ai.neargo.shop.pay.service.impl.RefundPaymentStore;
import ai.neargo.shop.spi.settle.RefundRequest;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** {@link RefundService} 的实现 */
@Service
public class RefundServiceImpl implements RefundService {

    private final CallerPolicy policy;
    private final PayOrderMapper orders;
    private final PayRefundMapper refunds;
    private final RefundReviewRuleMapper rules;
    private final PaymentMapper attempts;
    private final ApprovalService approvals;
    private final RefundExecutionService executions;
    private final RefundPaymentStore store;
    private final TransactionTemplate tx;
    private final boolean reviewEnabled;

    public RefundServiceImpl(CallerPolicy policy, PayOrderMapper orders, PayRefundMapper refunds,
                             RefundReviewRuleMapper rules, PaymentMapper attempts, ApprovalService approvals,
                             @Lazy RefundExecutionService executions, @Lazy RefundPaymentStore store,
                             @Qualifier("payTxManager") PlatformTransactionManager payTxManager,
                             @Value("${shop.pay.refund.review:false}") boolean reviewEnabled) {
        this.policy = policy;
        this.orders = orders;
        this.refunds = refunds;
        this.rules = rules;
        this.attempts = attempts;
        this.approvals = approvals;
        this.executions = executions;
        this.store = store;
        this.tx = new TransactionTemplate(payTxManager);
        this.reviewEnabled = reviewEnabled;
    }

    @Override
    public PayRefund request(PayCaller caller, RefundRequestCmd cmd) {
        PayOrder order = DataScopeContext.executeWithoutScope(() -> orders.selectOne(Wrappers.<PayOrder>lambdaQuery()
                .eq(PayOrder::getPayOrderNo, cmd.payOrderNo())));
        if (order == null || !ownedBy(caller, order.getPayAppId())) {
            throw BizException.of(ErrorCode.PAY_ORDER_NOT_FOUND);
        }
        policy.check(caller, PayAction.REQUEST_REFUND, order.getStoreId());
        StlPayment origin = DataScopeContext.executeWithoutScope(() -> attempts.selectOne(Wrappers.<StlPayment>lambdaQuery()
                .eq(StlPayment::getPayOrderId, order.getId()).eq(StlPayment::getDirection, StlPayment.PAY)
                .eq(StlPayment::getStatus, StlPayment.SUCCESS).orderByDesc(StlPayment::getId).last("LIMIT 1")));
        if (origin == null) {
            throw BizException.of(ErrorCode.PAY_ORDER_STATE, order.getStatus());
        }
        String bizRefundNo = cmd.bizRefundNo() == null || cmd.bizRefundNo().isBlank() ? cmd.outRefundNo() : cmd.bizRefundNo();
        PayRefund pr = tx.execute(s -> open(order.getId(), cmd.outRefundNo(), bizRefundNo, cmd.amountMinor(), cmd.reason(),
                caller.operatorNo(), caller.type().name(), caller.callerId()));
        if (PayRefundStatus.valueOf(pr.getStatus()).terminal()) {
            return pr;
        }
        executions.request(new RefundRequest(cmd.outRefundNo(), bizRefundNo, null, origin.getPaymentNo(),
                cmd.amountMinor(), order.getCurrency(), cmd.reason()));
        return byId(pr.getId());
    }

    @Override
    public PayRefund query(PayCaller caller, String payRefundNo) {
        PayRefund r = DataScopeContext.executeWithoutScope(() -> refunds.selectOne(Wrappers.<PayRefund>lambdaQuery()
                .eq(PayRefund::getPayRefundNo, payRefundNo)));
        if (r == null || !ownedBy(caller, r.getPayAppId())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        policy.check(caller, PayAction.QUERY, r.getStoreId());
        return r;
    }

    @Override
    public List<PayRefund> recentOfStore(PayCaller caller, Long storeId, int limit) {
        policy.check(caller, PayAction.QUERY, storeId);
        return DataScopeContext.executeWithoutScope(() -> refunds.selectList(Wrappers.<PayRefund>lambdaQuery()
                .eq(PayRefund::getStoreId, storeId).orderByDesc(PayRefund::getId)
                .last("LIMIT " + Math.max(1, Math.min(limit, 200)))));
    }

    /** 第三方只能动自己建的单（与 {@code PaymentServiceImpl#requireOwner} 同一条规则）：不归它的一律当作不存在 */
    private static boolean ownedBy(PayCaller caller, Long payAppId) {
        return caller.type() != CallerType.PARTNER || Objects.equals(caller.callerId(), payAppId);
    }

    @Override
    public void attachExecution(StlPayment origin, StlPayment refundRow, RefundRequest request) {
        if (origin.getPayOrderId() == null || (request.afterSaleNo() != null
                && request.afterSaleNo().startsWith(PayExceptionService.SOURCE_PREFIX))) {
            // 支付异常的原路退回（W3 E04）：钱不在支付单的余额里，不开退款单
            return;
        }
        PayOrder order = DataScopeContext.executeWithoutScope(() -> orders.selectById(origin.getPayOrderId()));
        // 现有售后路径：操作员没有一路传进支付（偏差 6），记售后单号以便追溯
        PayRefund pr = open(order.getId(), request.executionNo(), request.afterSaleNo(), request.amountMinor(),
                request.reason(), "AFTER_SALE:" + request.afterSaleNo(), "BIZ", order.getPayAppId());
        PayRefund link = new PayRefund();
        link.setId(pr.getId());
        link.setStlPaymentId(refundRow.getId());
        DataScopeContext.executeWithoutScope(() -> refunds.updateById(link));
        StlPayment back = new StlPayment();
        back.setId(refundRow.getId());
        back.setPayRefundId(pr.getId());
        DataScopeContext.executeWithoutScope(() -> attempts.updateById(back));
    }

    /** 建退款单、占用可退余额、判复核。幂等：同一调用方退款单号返回已有那张（金额必须一致） */
    private PayRefund open(Long orderId, String outRefundNo, String bizRefundNo, long amount, String reason,
                           String requestedBy, String sourceType, Long sourceId) {
        PayOrder o = orders.lockById(orderId);
        PayRefund existing = DataScopeContext.executeWithoutScope(() -> refunds.selectOne(Wrappers.<PayRefund>lambdaQuery()
                .eq(PayRefund::getPayAppId, o.getPayAppId()).eq(PayRefund::getOutRefundNo, outRefundNo)));
        if (existing != null) {
            if (existing.getAmountMinor() != amount) {
                throw BizException.of(ErrorCode.PAY_ORDER_CONFLICT);
            }
            return existing;
        }
        long available = nz(o.getPaidMinor()) - nz(o.getRefundedMinor()) - nz(o.getReservedRefundMinor());
        if (amount <= 0 || amount > available) {
            throw BizException.of(ErrorCode.PAY_REFUND_EXCEEDS, Math.max(available, 0));
        }
        RefundReviewPolicy.Decision decision = reviewEnabled ? review(o, sourceType, amount)
                : new RefundReviewPolicy.Decision(false, null, List.of());
        PayRefund pr = new PayRefund();
        pr.setPayRefundNo(BizKey.next(BizKey.PAY_REFUND));
        pr.setPayOrderId(o.getId());
        pr.setPayAppId(o.getPayAppId());
        pr.setOutRefundNo(outRefundNo);
        pr.setBizRefundNo(bizRefundNo);
        pr.setStoreId(o.getStoreId());
        pr.setCurrency(o.getCurrency());
        pr.setAmountMinor(amount);
        pr.setRefundMode("ONLINE");
        pr.setReason(reason == null || reason.isBlank() ? "-" : reason);
        pr.setStatus((decision.review() ? PayRefundStatus.PENDING_REVIEW : PayRefundStatus.APPROVED).name());
        pr.setRequestedBy(requestedBy);
        pr.setPayRefundReviewRuleId(decision.ruleId());
        pr.setHitReasons(decision.hitReasons().isEmpty() ? null : String.join(",", decision.hitReasons()));
        pr.setSourceType(sourceType);
        pr.setSourceId(sourceId);
        DataScopeContext.executeWithoutScope(() -> refunds.insert(pr));
        PayOrder reserve = new PayOrder();
        reserve.setId(o.getId());
        reserve.setReservedRefundMinor(nz(o.getReservedRefundMinor()) + amount);
        DataScopeContext.executeWithoutScope(() -> orders.updateById(reserve));
        if (decision.review()) {
            PayApproval a = approvals.open("REFUND", pr.getId(), pr.getPayRefundNo() + "|" + amount, amount,
                    o.getCurrency(), requestedBy);
            PayRefund link = new PayRefund();
            link.setId(pr.getId());
            link.setPayApprovalId(a.getId());
            DataScopeContext.executeWithoutScope(() -> refunds.updateById(link));
            pr.setPayApprovalId(a.getId());
        }
        return pr;
    }

    private RefundReviewPolicy.Decision review(PayOrder o, String sourceType, long amount) {
        List<PayRefundReviewRule> candidates = DataScopeContext.executeWithoutScope(() -> rules.selectList(
                Wrappers.<PayRefundReviewRule>lambdaQuery().eq(PayRefundReviewRule::getMarket, o.getMarket())
                        .eq(PayRefundReviewRule::getStatus, "ACTIVE")));
        LocalDate today = BizDates.of(o.getMarket(), Instant.now());
        long daily = DataScopeContext.executeWithoutScope(() -> refunds.selectList(Wrappers.<PayRefund>lambdaQuery()
                        .eq(PayRefund::getStoreId, o.getStoreId()).notIn(PayRefund::getStatus, "FAILED", "REJECTED")
                        .ge(PayRefund::getCreatedAt, today.atStartOfDay())))
                .stream().mapToLong(r -> nz(r.getAmountMinor())).sum();
        Instant paidAt = o.getSucceededAt() == null ? null : o.getSucceededAt().toInstant(ZoneOffset.UTC);
        return RefundReviewPolicy.decide(candidates, new RefundReviewPolicy.RefundFacts(o.getMarket(), o.getCurrency(),
                o.getStoreId(), sourceType, o.getPayMethod() == null ? "" : o.getPayMethod(), amount, daily,
                nz(o.getPaidMinor()), paidAt, Instant.now()));
    }

    @Override
    public boolean dispatchAllowed(String executionNo) {
        PayRefund pr = byExecution(executionNo);
        if (pr == null) {
            return true;
        }
        PayRefundStatus s = PayRefundStatus.valueOf(pr.getStatus());
        return s != PayRefundStatus.PENDING_REVIEW && s != PayRefundStatus.PENDING_SECOND_REVIEW
                && s != PayRefundStatus.REJECTED;
    }

    @Override
    public void dispatched(String executionNo) {
        PayRefund pr = byExecution(executionNo);
        if (pr != null && PayRefundStatus.APPROVED.name().equals(pr.getStatus())) {
            DataScopeContext.executeWithoutScope(() -> refunds.update(null, Wrappers.<PayRefund>lambdaUpdate()
                    .eq(PayRefund::getId, pr.getId()).eq(PayRefund::getStatus, PayRefundStatus.APPROVED.name())
                    .set(PayRefund::getStatus, PayRefundStatus.DISPATCHED.name())));
        }
    }

    @Override
    public PayRefund review(PayCaller caller, String payRefundNo, boolean approve, String reason) {
        PayRefund pr = DataScopeContext.executeWithoutScope(() -> refunds.selectOne(Wrappers.<PayRefund>lambdaQuery()
                .eq(PayRefund::getPayRefundNo, payRefundNo)));
        if (pr == null || pr.getPayApprovalId() == null) {
            throw BizException.of(ErrorCode.PAY_ORDER_NOT_FOUND);
        }
        policy.check(caller, PayAction.REVIEW_REFUND, pr.getStoreId());
        PayApproval a = approvals.decide(pr.getPayApprovalId(), caller.operatorNo(), approve, reason);
        switch (a.getStatus()) {
            case "PENDING_SECOND" -> status(pr, PayRefundStatus.PENDING_SECOND_REVIEW, null);
            case "APPROVED" -> status(pr, PayRefundStatus.APPROVED, null);
            case "REJECTED" -> {
                // 执行行判失败（售后侧据此知道没退成）—— 结果入口随之释放占用；之后退款单改记为驳回
                store.reject(pr.getOutRefundNo(), "REFUND_REVIEW_REJECTED");
                status(pr, PayRefundStatus.REJECTED, reason);
            }
            default -> {
            }
        }
        return byId(pr.getId());
    }

    private void status(PayRefund pr, PayRefundStatus to, String failedReason) {
        PayRefund patch = new PayRefund();
        patch.setId(pr.getId());
        patch.setStatus(to.name());
        patch.setFailedReason(failedReason);
        DataScopeContext.executeWithoutScope(() -> refunds.updateById(patch));
    }

    private PayRefund byExecution(String executionNo) {
        return DataScopeContext.executeWithoutScope(() -> refunds.selectOne(Wrappers.<PayRefund>lambdaQuery()
                .eq(PayRefund::getOutRefundNo, executionNo).orderByDesc(PayRefund::getId).last("LIMIT 1")));
    }

    private PayRefund byId(Long id) {
        return DataScopeContext.executeWithoutScope(() -> refunds.selectById(id));
    }

    private static long nz(Long v) {
        return v == null ? 0 : v;
    }
}
