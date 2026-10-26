package ai.neargo.shop.pay.acquiring.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.acquiring.entity.PayException;
import ai.neargo.shop.pay.acquiring.service.PayExceptionService;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.mapper.SettleMappers.PaymentMapper;
import ai.neargo.shop.pay.mapper.acquiring.AcquiringMappers.PayExceptionMapper;
import ai.neargo.shop.pay.service.RefundExecutionService;
import ai.neargo.shop.spi.settle.RefundDispatchResult;
import ai.neargo.shop.spi.settle.RefundRequest;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/** {@link PayExceptionService} 的实现（TDD-支付服务-W3收单补完 E04） */
@Service
public class PayExceptionServiceImpl implements PayExceptionService {

    private static final Logger log = LoggerFactory.getLogger(PayExceptionServiceImpl.class);
    private static final List<String> REFUNDABLE = List.of(PaymentOutcomeServiceImpl.LATE_PAYMENT,
            PaymentOutcomeServiceImpl.DUPLICATE_PAYMENT);

    private final PayExceptionMapper exceptions;
    private final PaymentMapper attempts;
    private final RefundExecutionService executions;
    private final boolean autoRefund;

    public PayExceptionServiceImpl(PayExceptionMapper exceptions, PaymentMapper attempts,
                                   @Lazy RefundExecutionService executions,
                                   @Value("${shop.pay.late-payment.auto-refund:false}") boolean autoRefund) {
        this.exceptions = exceptions;
        this.attempts = attempts;
        this.executions = executions;
        this.autoRefund = autoRefund;
    }

    @Override
    public void refund(Long exceptionId, String operatorNo) {
        PayException e = DataScopeContext.executeWithoutScope(() -> exceptions.selectById(exceptionId));
        if (e == null || !REFUNDABLE.contains(e.getKind())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if ("RESOLVED".equals(e.getStatus()) || "IGNORED".equals(e.getStatus())) {
            return;
        }
        // 先抢状态：两个运营同时点、或作业与运营撞车，只有一方发起
        if ("OPEN".equals(e.getStatus()) && DataScopeContext.executeWithoutScope(() -> exceptions.update(null,
                Wrappers.<PayException>lambdaUpdate().eq(PayException::getId, e.getId()).eq(PayException::getStatus, "OPEN")
                        .set(PayException::getStatus, "AUTO_REFUNDING").set(PayException::getHandledBy, operatorNo)
                        .set(PayException::getHandledAt, LocalDateTime.now(ZoneOffset.UTC)))) != 1) {
            return;
        }
        StlPayment origin = DataScopeContext.executeWithoutScope(() -> attempts.selectById(e.getStlPaymentId()));
        if (origin == null) {
            throw new IllegalStateException("异常 " + e.getId() + " 指向的尝试不存在");
        }
        // 执行号与来源标记都由异常 id 决定：重复调用落到同一行（执行器按执行号幂等），不会退两次
        RefundDispatchResult r = executions.request(new RefundRequest(EXECUTION_PREFIX + e.getId(),
                SOURCE_PREFIX + e.getId(), null, origin.getPaymentNo(), e.getAmountMinor(), e.getCurrency(),
                "支付异常 " + e.getKind() + " 原路退回"));
        if (r != null && r.status() == RefundDispatchResult.DispatchStatus.SUCCEEDED) {
            refundSettled(EXECUTION_PREFIX + e.getId(), true, null);
        } else if (r != null && r.status() == RefundDispatchResult.DispatchStatus.FAILED) {
            refundSettled(EXECUTION_PREFIX + e.getId(), false, r.errorCode());
        }
    }

    @Override
    public void ignore(Long exceptionId, String operatorNo, String note) {
        int n = DataScopeContext.executeWithoutScope(() -> exceptions.update(null, Wrappers.<PayException>lambdaUpdate()
                .eq(PayException::getId, exceptionId).eq(PayException::getStatus, "OPEN")
                .set(PayException::getStatus, "IGNORED").set(PayException::getResolution, "IGNORED")
                .set(PayException::getNote, note == null ? null : note.length() > 512 ? note.substring(0, 512) : note)
                .set(PayException::getHandledBy, operatorNo).set(PayException::getHandledAt,
                        LocalDateTime.now(ZoneOffset.UTC))));
        if (n != 1) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    @Override
    public int autoRefundOpen(int limit) {
        if (!autoRefund) {
            return 0;
        }
        List<PayException> open = DataScopeContext.executeWithoutScope(() -> exceptions.selectList(
                Wrappers.<PayException>lambdaQuery().eq(PayException::getStatus, "OPEN").in(PayException::getKind, REFUNDABLE)
                        .orderByAsc(PayException::getId).last("LIMIT " + Math.max(1, Math.min(limit, 200)))));
        int started = 0;
        for (PayException e : open) {
            try {
                refund(e.getId(), "SYSTEM");
                started++;
            } catch (RuntimeException failure) {
                log.warn("[pay-exception] 自动原路退回失败 id={} kind={}：{}", e.getId(), e.getKind(), failure.toString());
            }
        }
        return started;
    }

    @Override
    public void refundSettled(String executionNo, boolean succeeded, String reason) {
        if (executionNo == null || !executionNo.startsWith(EXECUTION_PREFIX)) {
            return;
        }
        Long id;
        try {
            id = Long.valueOf(executionNo.substring(EXECUTION_PREFIX.length()));
        } catch (NumberFormatException notOurs) {
            return;
        }
        DataScopeContext.executeWithoutScope(() -> exceptions.update(null, Wrappers.<PayException>lambdaUpdate()
                .eq(PayException::getId, id).eq(PayException::getStatus, "AUTO_REFUNDING")
                .set(PayException::getStatus, succeeded ? "RESOLVED" : "OPEN")
                .set(PayException::getResolution, succeeded ? "REFUNDED" : null)
                .set(!succeeded, PayException::getNote, "原路退回失败：" + reason)));
    }
}
