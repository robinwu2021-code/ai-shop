package ai.neargo.shop.pay.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.entity.StlPaymentOperation;
import ai.neargo.shop.pay.mapper.PaymentOperationMapper;
import ai.neargo.shop.pay.mapper.PaymentOrderGuardMapper;
import ai.neargo.shop.pay.mapper.SettleMappers.PaymentMapper;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.pay.service.PaymentOperationService;
import ai.neargo.shop.spi.settle.PaymentOperationPort.OfflinePaymentReceiptReq;
import ai.neargo.shop.spi.settle.PaymentOperationPort.PaymentOperationReq;
import ai.neargo.shop.spi.settle.PaymentOperationPort.PaymentOperationView;
import ai.neargo.shop.spi.settle.SettlePort;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Durable instruction and dispatch arbitration on the same connection as the original payment
 * ledger.
 */
@Service
public class PaymentOperationServiceImpl implements PaymentOperationService {
    private final PaymentOperationMapper operations;
    private final PaymentOrderGuardMapper guards;
    private final PaymentMapper payments;
    private final PaymentLedgerService ledger;
    private final TransactionTemplate transactions;
    private final ObjectMapper json = new ObjectMapper();

    /** Keep operation and ledger writes on the same financial transaction manager. */
    public PaymentOperationServiceImpl(
            PaymentOperationMapper operations,
            PaymentOrderGuardMapper guards,
            PaymentMapper payments,
            PaymentLedgerService ledger,
            @Qualifier("payTxManager") PlatformTransactionManager manager) {
        this.operations = operations;
        this.guards = guards;
        this.payments = payments;
        this.ledger = ledger;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public PaymentOperationView prepare(PaymentOperationReq request) {
        if ("OFFLINE".equals(request.payment().payChannel())) {
            conflict();
        }
        return prepareRow(row(request, null));
    }

    @Override
    public PaymentOperationView prepareLegacy(SettlePort.PaymentOpen payment) {
        // Preserve the actual old attempt's route/description while retaining its basic identity
        // checks.
        var existing = currentPayment(payment.orderNo());
        if (existing != null) {
            if (StlPayment.SUCCESS.equals(existing.getStatus())) {
                conflict();
            }
            var original =
                    json.readValue(existing.getPaymentRequestJson(), SettlePort.PaymentOpen.class);
            if (!sameMoneyAndBuyer(original, payment)) {
                conflict();
            }
            payment = original;
        }
        var row = new StlPaymentOperation();
        row.setTenantNo(existing == null ? "MAIN" : existing.getTenantNo());
        row.setOperationNo("PO" + UUID.randomUUID().toString().replace("-", ""));
        row.setOwnerKind("LEGACY_TRADE");
        row.setOrderNo(payment.orderNo());
        row.setBuyerNo(payment.userNo());
        row.setEntityNo(payment.entityNo());
        row.setRequestHash(hash(json.writeValueAsString(payment)));
        row.setPaymentRequestJson(json.writeValueAsString(payment));
        row.setPurpose("ONLINE");
        row.setPhase("PREPARED");
        return prepareRow(row);
    }

    private PaymentOperationView prepareRow(StlPaymentOperation desired) {
        try {
            return transaction(
                    () -> {
                        var existing = operations.find(desired.getOperationNo());
                        if (existing != null) {
                            sameInstruction(existing, desired);
                            return view(existing);
                        }
                        operations.insert(desired);
                        return view(desired);
                    });
        } catch (DuplicateKeyException race) {
            var existing = required(desired.getOperationNo());
            sameInstruction(existing, desired);
            return view(existing);
        }
    }

    @Override
    public PaymentOperationView open(String operationNo) {
        transaction(
                () -> {
                    var known = required(operationNo);
                    guard(known.getOrderNo());
                    return openLocked(lockRequestAndRoot(known), null);
                });
        return find(operationNo);
    }

    private StlPaymentOperation openLocked(
            StlPaymentOperation row, OfflinePaymentReceiptReq offline) {
        if (!"PREPARED".equals(row.getPhase())) {
            return row;
        }
        var request = instruction(row);
        StlPayment payment = currentPayment(row.getOrderNo());
        boolean created = payment == null;
        if (created) {
            String outTradeNo = ledger.open(request);
            payment = payment(outTradeNo);
        } else if (!Objects.equals(instructionOf(payment), request)) {
            conflict();
        }
        String canonical = payment.getInitiatingOperationNo();
        if (canonical != null) {
            var root = required(canonical);
            if (!Objects.equals(row.getOrderNo(), root.getOrderNo())
                    || !Objects.equals(row.getPurpose(), root.getPurpose())) {
                conflict();
            }
            if (offline != null
                    && !Objects.equals(
                            offlineFacts(offline),
                            offlineFacts(
                                    json.readValue(
                                            root.getOfflineRequestJson(),
                                            OfflinePaymentReceiptReq.class)))) {
                conflict();
            }
            row.setCanonicalOperationNo(canonical);
            row.setPhase("ALIASED");
        } else {
            if (offline != null && !created) {
                conflict();
            }
            row.setCanonicalOperationNo(row.getOperationNo());
            row.setPhase(created ? "OPENED" : "UNKNOWN");
            var patch = new StlPayment();
            patch.setId(payment.getId());
            patch.setInitiatingOperationNo(row.getOperationNo());
            payments.updateById(patch);
            if (offline != null) {
                row.setMethod(offline.method());
                row.setEvidenceRef(offline.evidenceRef());
                row.setEvidenceJson(
                        offline.evidence() == null
                                ? null
                                : json.writeValueAsString(offline.evidence()));
                row.setActorNo(offline.actorNo());
                row.setActorRealm(offline.actorRealm());
                row.setActorKind(offline.actorKind());
                row.setActorAccountNo(offline.actorAccountNo());
                row.setReceivedAt(local(offline.receivedAt()));
                row.setNote(offline.note());
            }
        }
        row.setPaymentNo(payment.getPaymentNo());
        row.setOutTradeNo(payment.getOutTradeNo());
        save(row);
        return row;
    }

    @Override
    public Optional<DispatchTicket> claimSend(String operationNo) {
        return canonicalTransaction(
                operationNo,
                row -> {
                    var pay = observeLocked(row);
                    if (!"OPENED".equals(row.getPhase())
                            || row.getSendStartedAt() != null
                            || !"ONLINE".equals(row.getPurpose())) {
                        return Optional.empty();
                    }
                    if (pay == null || !StlPayment.PENDING.equals(pay.getStatus())) {
                        return Optional.empty();
                    }
                    // A closed original may receive real late money. The same order guard makes
                    // this current read atomic with the first claim for any other pending attempt.
                    var received =
                            payments.selectOne(
                                    Wrappers.<StlPayment>lambdaQuery()
                                            .eq(StlPayment::getOrderNo, row.getOrderNo())
                                            .eq(StlPayment::getDirection, StlPayment.PAY)
                                            .eq(StlPayment::getStatus, StlPayment.SUCCESS)
                                            .orderByAsc(StlPayment::getId)
                                            .last("LIMIT 1 FOR UPDATE"));
                    if (received != null) {
                        conflict();
                    }
                    row.setPhase("SENDING");
                    row.setSendStartedAt(local(Instant.now()));
                    save(row);
                    return Optional.of(
                            new DispatchTicket(
                                    row.getOperationNo(),
                                    row.getPaymentNo(),
                                    row.getOutTradeNo(),
                                    row.getVersion(),
                                    instruction(row)));
                });
    }

    @Override
    public PaymentOperationView recordPayable(
            DispatchTicket ticket, Map<String, String> params, Instant expiresAt) {
        return finish(
                ticket,
                row -> {
                    row.setPhase("PAYABLE");
                    row.setPayParamsJson(json.writeValueAsString(Map.copyOf(params)));
                    row.setPayParamsExpiresAt(local(expiresAt));
                });
    }

    @Override
    public PaymentOperationView recordUnknown(DispatchTicket ticket, String reasonCode) {
        return finish(
                ticket,
                row -> {
                    row.setPhase("UNKNOWN");
                    row.setLastErrorCode(reasonCode);
                });
    }

    @Override
    public PaymentOperationView rejectNotSent(DispatchTicket ticket, NotSentReason reason) {
        Objects.requireNonNull(reason);
        return finish(
                ticket,
                row -> {
                    ledger.closeUnsent(row.getOutTradeNo(), row.getOperationNo());
                    row.setPhase("REJECTED");
                    row.setLastErrorCode("PAYMENT_NOT_SENT_" + reason.name());
                });
    }

    private PaymentOperationView finish(
            DispatchTicket ticket, Consumer<StlPaymentOperation> update) {
        return transaction(
                () -> {
                    var known = required(ticket.canonicalOperationNo());
                    guard(known.getOrderNo());
                    var row = operations.lock(ticket.canonicalOperationNo());
                    observeLocked(row);
                    if ("SENDING".equals(row.getPhase())
                            && Objects.equals(row.getVersion(), ticket.revision())
                            && row.getOutTradeNo().equals(ticket.outTradeNo())
                            && row.getPaymentNo().equals(ticket.paymentNo())) {
                        update.accept(row);
                        save(row);
                    }
                    return view(row);
                });
    }

    @Override
    public PaymentOperationView confirmOffline(OfflinePaymentReceiptReq request) {
        prepareRow(row(request.operation(), request));
        try {
            return transaction(
                    () -> {
                        guard(request.operation().orderNo());
                        var row = lockRequestAndRoot(required(request.operation().operationNo()));
                        row = openLocked(row, request);
                        var root = canonicalLocked(row);
                        if (!Objects.equals(
                                offlineFacts(request),
                                offlineFacts(
                                        json.readValue(
                                                root.getOfflineRequestJson(),
                                                OfflinePaymentReceiptReq.class)))) {
                            conflict();
                        }
                        if (!StlPayment.SUCCESS.equals(payment(root.getOutTradeNo()).getStatus())) {
                            String order =
                                    ledger.settle(
                                            new SettlePort.PaymentSettled(
                                                    root.getOutTradeNo(),
                                                    "OFFLINE",
                                                    "OFFLINE:" + root.getOperationNo(),
                                                    request.operation().payment().amountMinor(),
                                                    request.receivedAt().toEpochMilli(),
                                                    "CNY"));
                            if (!root.getOrderNo().equals(order)) {
                                conflict();
                            }
                        }
                        observeLocked(root);
                        return view(row);
                    });
        } catch (DuplicateKeyException alreadyBound) {
            // SQL exception text contains the private evidence key; return only a bounded business
            // conflict.
            throw BizException.of(ErrorCode.CHECKOUT_PAYMENT_CONFLICT);
        }
    }

    @Override
    public PaymentOperationView observeReceipt(String operationNo) {
        canonicalTransaction(
                operationNo,
                root -> {
                    observeLocked(root);
                    return null;
                });
        // A new autocommit observation cannot reuse a pre-guard RR snapshot of the alias or root.
        return find(operationNo);
    }

    private <T> T canonicalTransaction(
            String operationNo, Function<StlPaymentOperation, T> action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Canonical payment operations require no caller transaction");
        }
        StlPaymentOperation refreshed = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            var hint = refreshed;
            try {
                return transaction(
                        () -> {
                            var known = hint == null ? required(operationNo) : hint;
                            guard(known.getOrderNo());
                            var locked =
                                    Stream.of(operationNo, known.getCanonicalOperationNo())
                                            .filter(Objects::nonNull)
                                            .distinct()
                                            .sorted()
                                            .map(operations::lock)
                                            .toList();
                            var current =
                                    locked.stream()
                                            .filter(row -> operationNo.equals(row.getOperationNo()))
                                            .findFirst()
                                            .orElseThrow();
                            if (!Objects.equals(
                                    known.getCanonicalOperationNo(),
                                    current.getCanonicalOperationNo())) {
                                // No ledger read/write has occurred. Roll back all locks before
                                // following the new root.
                                throw new CanonicalChanged(current);
                            }
                            String rootNo =
                                    current.getCanonicalOperationNo() == null
                                            ? operationNo
                                            : current.getCanonicalOperationNo();
                            var root =
                                    locked.stream()
                                            .filter(row -> rootNo.equals(row.getOperationNo()))
                                            .findFirst()
                                            .orElseThrow();
                            return action.apply(root);
                        });
            } catch (CanonicalChanged changed) {
                // A request binds once to one immutable root; only that bounded race is retried.
                refreshed = changed.current;
            }
        }
        throw BizException.of(ErrorCode.CONFLICT);
    }

    private static final class CanonicalChanged extends RuntimeException {
        private final StlPaymentOperation current;

        private CanonicalChanged(StlPaymentOperation current) {
            super(null, null, false, false);
            this.current = current;
        }
    }

    private StlPayment observeLocked(StlPaymentOperation row) {
        if (row.getOutTradeNo() == null) {
            return null;
        }
        var pay =
                payments.selectOne(
                        Wrappers.<StlPayment>lambdaQuery()
                                .eq(StlPayment::getDirection, StlPayment.PAY)
                                .eq(StlPayment::getOutTradeNo, row.getOutTradeNo())
                                .last("FOR UPDATE"));
        if (pay != null && StlPayment.SUCCESS.equals(pay.getStatus())) {
            var frozen = instruction(row);
            String phase =
                    Objects.equals(frozen.currency(), pay.getCurrency())
                                    && Objects.equals(frozen.userNo(), pay.getUserNo())
                                    && Objects.equals(frozen.orderNo(), pay.getOrderNo())
                                    && Objects.equals(frozen.payChannel(), pay.getPayChannel())
                                    && Objects.equals(frozen.amountMinor(), pay.getAmountMinor())
                                    && pay.getReceiptNo() != null
                            ? "SUCCEEDED"
                            : "MANUAL_REQUIRED";
            if (!phase.equals(row.getPhase())) {
                row.setPhase(phase);
                if ("MANUAL_REQUIRED".equals(phase)) {
                    row.setLastErrorCode("PAYMENT_RECEIPT_MISMATCH");
                }
                save(row);
            }
        }
        return pay;
    }

    @Override
    public PaymentOperationView find(String operationNo) {
        return unscoped(() -> view(required(operationNo)));
    }

    @Override
    public boolean protectsUncertainAttempt(String outTradeNo) {
        var row = unscoped(() -> operations.canonicalForPayment(outTradeNo));
        return row != null && List.of("SENDING", "PAYABLE", "UNKNOWN").contains(row.getPhase());
    }

    private PaymentOperationView view(StlPaymentOperation request) {
        var root =
                request.getCanonicalOperationNo() == null
                        ? request
                        : required(request.getCanonicalOperationNo());
        var pay = root.getOutTradeNo() == null ? null : payment(root.getOutTradeNo());
        var receipt = pay == null ? null : ledger.paymentByOutTradeNo(pay.getOutTradeNo());
        Instant expiry =
                root.getPayParamsExpiresAt() == null
                        ? null
                        : root.getPayParamsExpiresAt().toInstant(ZoneOffset.UTC);
        Map<String, String> params = Map.of();
        String reason = root.getLastErrorCode();
        if (root.getPayParamsJson() != null
                && expiry != null
                && expiry.isAfter(Instant.now())
                && "PAYABLE".equals(root.getPhase())) {
            params =
                    json.readValue(
                            root.getPayParamsJson(), new TypeReference<Map<String, String>>() {});
        } else if ("PAYABLE".equals(root.getPhase())) {
            reason = "PAYMENT_PARAMS_EXPIRED";
        }
        return new PaymentOperationView(
                request.getOperationNo(),
                root.getCanonicalOperationNo(),
                root.getPhase(),
                root.getPaymentNo(),
                root.getOutTradeNo(),
                request.getRequestHash(),
                pay == null ? null : pay.getReceiptNo(),
                reason,
                receipt,
                params,
                expiry,
                Instant.now());
    }

    private StlPaymentOperation row(PaymentOperationReq request, OfflinePaymentReceiptReq offline) {
        var row = new StlPaymentOperation();
        row.setTenantNo(request.tenantNo());
        row.setOperationNo(request.operationNo());
        row.setOwnerKind("CHECKOUT");
        row.setOrderNo(request.orderNo());
        row.setBuyerNo(request.buyerNo());
        row.setEntityNo(request.entityNo());
        row.setStoreNo(request.storeNo());
        row.setRequestHash(request.requestHash());
        row.setPaymentRequestJson(json.writeValueAsString(request.payment()));
        row.setOfflineRequestJson(offline == null ? null : json.writeValueAsString(offline));
        row.setPurpose(offline == null ? "ONLINE" : "OFFLINE");
        row.setPhase("PREPARED");
        return row;
    }

    private void sameInstruction(StlPaymentOperation a, StlPaymentOperation b) {
        if (!Objects.equals(a.getTenantNo(), b.getTenantNo())
                || !Objects.equals(a.getOwnerKind(), b.getOwnerKind())
                || !Objects.equals(a.getRequestHash(), b.getRequestHash())
                || !Objects.equals(a.getOrderNo(), b.getOrderNo())
                || !Objects.equals(a.getBuyerNo(), b.getBuyerNo())
                || !Objects.equals(a.getEntityNo(), b.getEntityNo())
                || !Objects.equals(a.getStoreNo(), b.getStoreNo())
                || !Objects.equals(a.getPurpose(), b.getPurpose())
                || !Objects.equals(a.getPaymentRequestJson(), b.getPaymentRequestJson())
                || !Objects.equals(a.getOfflineRequestJson(), b.getOfflineRequestJson())) {
            conflict();
        }
    }

    private Object offlineFacts(OfflinePaymentReceiptReq request) {
        return Arrays.asList(
                request.method(),
                request.actorNo(),
                request.actorRealm(),
                request.actorKind(),
                request.actorAccountNo(),
                request.receivedAt(),
                request.note(),
                request.evidenceRef(),
                request.evidence());
    }

    private boolean sameMoneyAndBuyer(SettlePort.PaymentOpen a, SettlePort.PaymentOpen b) {
        return Objects.equals(a.orderNo(), b.orderNo())
                && Objects.equals(a.userNo(), b.userNo())
                && Objects.equals(a.entityNo(), b.entityNo())
                && Objects.equals(a.payChannel(), b.payChannel())
                && Objects.equals(a.currency(), b.currency())
                && a.amountMinor() == b.amountMinor();
    }

    private StlPayment currentPayment(String orderNo) {
        return unscoped(
                () ->
                        payments.selectOne(
                                Wrappers.<StlPayment>lambdaQuery()
                                        .eq(StlPayment::getDirection, StlPayment.PAY)
                                        .eq(StlPayment::getOrderNo, orderNo)
                                        .in(
                                                StlPayment::getStatus,
                                                StlPayment.SUCCESS,
                                                StlPayment.INIT,
                                                StlPayment.PENDING)
                                        .orderByDesc(StlPayment::getId)
                                        .last("LIMIT 1")));
    }

    private StlPayment payment(String no) {
        return payments.selectOne(
                Wrappers.<StlPayment>lambdaQuery()
                        .eq(StlPayment::getDirection, StlPayment.PAY)
                        .eq(StlPayment::getOutTradeNo, no));
    }

    private StlPaymentOperation lockRequestAndRoot(StlPaymentOperation known) {
        var payment = currentPayment(known.getOrderNo());
        String canonical = payment == null ? null : payment.getInitiatingOperationNo();
        var locked =
                Stream.of(known.getOperationNo(), canonical)
                        .filter(Objects::nonNull)
                        .distinct()
                        .sorted()
                        .map(operations::lock)
                        .toList();
        return locked.stream()
                .filter(row -> known.getOperationNo().equals(row.getOperationNo()))
                .findFirst()
                .orElseThrow();
    }

    private StlPaymentOperation canonicalLocked(StlPaymentOperation known) {
        return operations.lock(
                known.getCanonicalOperationNo() == null
                        ? known.getOperationNo()
                        : known.getCanonicalOperationNo());
    }

    private StlPaymentOperation required(String no) {
        var row = operations.find(no);
        if (row == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return row;
    }

    private SettlePort.PaymentOpen instruction(StlPaymentOperation row) {
        return json.readValue(row.getPaymentRequestJson(), SettlePort.PaymentOpen.class);
    }

    private SettlePort.PaymentOpen instructionOf(StlPayment row) {
        return json.readValue(row.getPaymentRequestJson(), SettlePort.PaymentOpen.class);
    }

    private void guard(String orderNo) {
        guards.ensure(orderNo);
        guards.lock(orderNo);
    }

    private void save(StlPaymentOperation row) {
        if (operations.updateById(row) != 1) {
            conflict();
        }
    }

    private <T> T transaction(Supplier<T> action) {
        return transactions.execute(tx -> unscoped(action));
    }

    private <T> T unscoped(Supplier<T> action) {
        return DataScopeContext.executeWithoutScope(action);
    }

    private static LocalDateTime local(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static void conflict() {
        throw BizException.of(ErrorCode.CONFLICT);
    }

    private static String hash(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
