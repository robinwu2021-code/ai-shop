package ai.neargo.shop.trade.order.service.lifecycle.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.settle.OrdinaryRefundPort;
import ai.neargo.shop.spi.settle.RefundDispatchResult;
import ai.neargo.shop.spi.settle.RefundRequest;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import ai.neargo.shop.trade.order.entity.OrdPaymentResolution;
import ai.neargo.shop.trade.order.mapper.PaymentResolutionMapper;
import ai.neargo.shop.trade.order.service.lifecycle.PaymentResolutionOpsService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentResolutionOpsServiceImpl implements PaymentResolutionOpsService {
    private final PaymentResolutionMapper resolutions;
    private final OrderMapper orders;
    private final SubOrderMapper children;
    private final ObjectProvider<OrdinaryRefundPort> owners;
    private final TransactionTemplate transaction;

    public PaymentResolutionOpsServiceImpl(PaymentResolutionMapper resolutions, OrderMapper orders,
            SubOrderMapper children, ObjectProvider<OrdinaryRefundPort> owners,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.resolutions = resolutions;
        this.orders = orders;
        this.children = children;
        this.owners = owners;
        this.transaction = new TransactionTemplate(manager);
    }

    @Override
    public List<Case> list(String status) {
        var rows = resolutions.selectList(Wrappers.<OrdPaymentResolution>lambdaQuery()
                .eq(OrdPaymentResolution::getTenantNo, "MAIN")
                .eq(status != null, OrdPaymentResolution::getStatus, status)
                .orderByAsc(OrdPaymentResolution::getId));
        if (rows.isEmpty()) return List.of();
        Set<String> payNos = rows.stream().map(OrdPaymentResolution::getPayNo).collect(Collectors.toSet());
        Set<String> allowed = visibleParents(payNos);
        if (allowed.isEmpty()) return List.of();
        var parents = DataScopeContext.executeWithoutScope(() -> orders.selectList(
                Wrappers.<OrdOrder>lambdaQuery().eq(OrdOrder::getTenantNo, "MAIN")
                        .in(OrdOrder::getOrderNo, allowed))).stream()
                .collect(Collectors.toMap(OrdOrder::getOrderNo, p -> p));
        return rows.stream().filter(r -> allowed.contains(r.getPayNo()))
                .map(r -> view(r, eligible(r, parents.get(r.getPayNo())))).toList();
    }

    @Override
    public Case refund(String payNo, String paymentNo) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("PAYMENT_RESOLUTION_REQUIRES_NO_TRANSACTION");
        }
        var owner = owners.getIfUnique();
        if (owner == null) throw BizException.of(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE);
        OrdPaymentResolution frozen = transaction.execute(tx -> {
            if (!visibleParents(Set.of(payNo)).contains(payNo)) throw BizException.of(ErrorCode.NOT_FOUND);
            var row = find(payNo, paymentNo, true);
            if (OrdPaymentResolution.RESOLVED.equals(row.getStatus())) return row;
            var parent = DataScopeContext.executeWithoutScope(() -> orders.selectOne(
                    Wrappers.<OrdOrder>lambdaQuery().eq(OrdOrder::getTenantNo, row.getTenantNo())
                            .eq(OrdOrder::getOrderNo, payNo).last("FOR UPDATE")));
            if (!eligible(row, parent) || children.selectCount(Wrappers.<OrdSubOrder>lambdaQuery()
                    .eq(OrdSubOrder::getTenantNo, row.getTenantNo()).eq(OrdSubOrder::getOrderNo, payNo)
                    .notIn(OrdSubOrder::getStatus, OrdOrder.CANCELLED, OrdOrder.CLOSED)) > 0) {
                throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
            }
            if (row.getRefundNo() == null) {
                row.setRefundNo(executionNo(row));
                row.setStatus(OrdPaymentResolution.REFUNDING);
                resolutions.updateById(row);
            }
            return row;
        });
        if (OrdPaymentResolution.RESOLVED.equals(frozen.getStatus())) return view(frozen, false);
        var request = new RefundRequest(frozen.getRefundNo(), "PAYRES:" + frozen.getRefundNo(), null,
                frozen.getPaymentNo(), frozen.getAmountMinor(), frozen.getCurrency(), "LATE_PAYMENT");
        var command = new OrdinaryRefundPort.Execution(frozen.getSourcePayNo(), frozen.getSourceUserNo(),
                frozen.getOriginOutTradeNo(), request);
        // Always query first: a crash after preparing locally must reuse the same cash instruction.
        var receipt = owner.query(command);
        verify(command, receipt);
        if (receipt.outcome().retryable() && ("REFUND_NOT_PREPARED".equals(receipt.outcome().errorCode())
                || "REFUND_NOT_DISPATCHED".equals(receipt.outcome().errorCode()))) {
            receipt = owner.request(command);
            verify(command, receipt);
        }
        var outcome = receipt.outcome();
        return transaction.execute(tx -> {
            var current = find(payNo, paymentNo, true);
            if (!OrdPaymentResolution.RESOLVED.equals(current.getStatus())) {
                current.setStatus(outcome.status() == RefundDispatchResult.DispatchStatus.SUCCEEDED
                        ? OrdPaymentResolution.RESOLVED : OrdPaymentResolution.REFUNDING);
                current.setRefundErrorCode(outcome.errorCode());
                resolutions.updateById(current);
            }
            return view(current, !OrdPaymentResolution.RESOLVED.equals(current.getStatus()));
        });
    }

    private OrdPaymentResolution find(String payNo, String paymentNo, boolean lock) {
        var row = resolutions.selectOne(Wrappers.<OrdPaymentResolution>lambdaQuery()
                .eq(OrdPaymentResolution::getTenantNo, "MAIN").eq(OrdPaymentResolution::getPayNo, payNo)
                .eq(OrdPaymentResolution::getPaymentNo, paymentNo).last(lock ? "FOR UPDATE" : ""));
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }

    /** Parent money cannot be exposed to an operator who can see only one of its stores. */
    private Set<String> visibleParents(Set<String> payNos) {
        var query = Wrappers.<OrdSubOrder>lambdaQuery().eq(OrdSubOrder::getTenantNo, "MAIN")
                .in(OrdSubOrder::getOrderNo, payNos);
        var visible = children.selectList(query).stream().map(OrdSubOrder::getSubOrderNo).collect(Collectors.toSet());
        var all = DataScopeContext.executeWithoutScope(() -> children.selectList(query));
        return all.stream().collect(Collectors.groupingBy(OrdSubOrder::getOrderNo)).entrySet().stream()
                .filter(e -> e.getValue().stream().allMatch(c -> visible.contains(c.getSubOrderNo())))
                .map(Map.Entry::getKey).collect(Collectors.toSet());
    }

    private boolean eligible(OrdPaymentResolution row, OrdOrder parent) {
        return parent != null && OrdPaymentResolution.LATE_PAYMENT.equals(row.getReason())
                && (OrdPaymentResolution.MANUAL_REQUIRED.equals(row.getStatus())
                        || OrdPaymentResolution.REFUNDING.equals(row.getStatus()))
                && (OrdOrder.CANCELLED.equals(parent.getStatus()) || OrdOrder.CLOSED.equals(parent.getStatus()))
                && parent.getPaidAt() == null && parent.getPayTradeNo() == null
                && Objects.equals(parent.getUserNo(), row.getUserNo())
                && Objects.equals(row.getUserNo(), row.getSourceUserNo())
                && Objects.equals(row.getPayNo(), row.getSourcePayNo())
                && Objects.equals(parent.getPayAmount(), row.getAmountMinor())
                && Objects.equals(parent.getCurrency(), row.getCurrency())
                && row.getOriginOutTradeNo() != null && !row.getOriginOutTradeNo().isBlank();
    }

    private static void verify(OrdinaryRefundPort.Execution command, OrdinaryRefundPort.Receipt receipt) {
        if (receipt == null || !command.equals(receipt.execution()) || receipt.outcome() == null
                || receipt.outcome().status() == null
                || !command.refund().executionNo().equals(receipt.outcome().executionNo())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private static Case view(OrdPaymentResolution r, boolean canRefund) {
        return new Case(r.getPayNo(), r.getPaymentNo(), r.getReason(), r.getStatus(), r.getAmountMinor(),
                r.getCurrency(), r.getRefundNo(), r.getRefundErrorCode(), canRefund);
    }

    private static String executionNo(OrdPaymentResolution row) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest((row.getTenantNo() + "|"
                    + row.getPayNo() + "|" + row.getPaymentNo()).getBytes(StandardCharsets.UTF_8));
            return "PR" + HexFormat.of().formatHex(digest).substring(0, 48);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
