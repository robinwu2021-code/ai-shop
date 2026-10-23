package ai.neargo.shop.trade.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.media.ReceiptEvidencePort;
import ai.neargo.shop.spi.media.ReceiptEvidencePort.ReceiptEvidence;
import ai.neargo.shop.spi.media.ReceiptEvidencePort.ReceiptEvidenceQuery;
import ai.neargo.shop.spi.settle.PaymentOperationPort;
import ai.neargo.shop.spi.settle.PaymentOperationPort.OfflinePaymentReceiptReq;
import ai.neargo.shop.spi.settle.PaymentOperationPort.PaymentOperationReq;
import ai.neargo.shop.spi.settle.PaymentOperationPort.PaymentOperationView;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutScope;
import ai.neargo.shop.spi.trade.CheckoutPort.OperationRef;
import ai.neargo.shop.spi.trade.CheckoutPort.PaymentAttemptView;
import ai.neargo.shop.spi.trade.CheckoutPort.PaymentLookup;
import ai.neargo.shop.spi.trade.OfflineReceiptPort.OfflineReceiptReq;
import ai.neargo.shop.trade.entity.OrdCheckoutConfirmation;
import ai.neargo.shop.trade.entity.OrdCheckoutOperation;
import ai.neargo.shop.trade.entity.OrdCheckoutPaymentRequest;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.mapper.CheckoutMappers.ConfirmationMapper;
import ai.neargo.shop.trade.mapper.CheckoutMappers.OperationMapper;
import ai.neargo.shop.trade.mapper.CheckoutMappers.PaymentRequestMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderMapper;
import ai.neargo.shop.trade.order.service.create.CheckoutClock;
import ai.neargo.shop.trade.order.service.create.CheckoutDigests;
import ai.neargo.shop.trade.policy.CheckoutAccessPolicy;
import ai.neargo.shop.trade.policy.CheckoutAccessPolicy.CashierActor;
import ai.neargo.shop.trade.service.CheckoutPaymentService;
import ai.neargo.shop.trade.service.OfflineReceiptService;
import ai.neargo.shop.trade.service.OrderService;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Original consent and durable key precede financial receipt; media and pay calls never hold order
 * locks.
 */
@Service
public class OfflineReceiptServiceImpl implements OfflineReceiptService {
    private static final String ACTION = "OFFLINE_RECEIPT";
    private final CheckoutAccessPolicy access;
    private final OperationMapper operations;
    private final PaymentRequestMapper requests;
    private final ConfirmationMapper confirmations;
    private final OrderMapper orderRows;
    private final OrderService orders;
    private final CheckoutPaymentService reads;
    private final ReceiptEvidencePort evidence;
    private final PaymentOperationPort payments;
    private final ObjectMapper json;
    private final CheckoutClock clock;
    private final TransactionTemplate writes;

    /**
     * Bind actual owners and independent platform/pay boundaries without optional fake-success
     * providers.
     */
    public OfflineReceiptServiceImpl(
            CheckoutAccessPolicy access,
            OperationMapper operations,
            PaymentRequestMapper requests,
            ConfirmationMapper confirmations,
            OrderMapper orderRows,
            OrderService orders,
            CheckoutPaymentService reads,
            ReceiptEvidencePort evidence,
            PaymentOperationPort payments,
            ObjectMapper json,
            CheckoutClock clock,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.access = access;
        this.operations = operations;
        this.requests = requests;
        this.confirmations = confirmations;
        this.orderRows = orderRows;
        this.orders = orders;
        this.reads = reads;
        this.evidence = evidence;
        this.payments = payments;
        this.json = json;
        this.clock = clock;
        writes = new TransactionTemplate(manager);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public PaymentAttemptView confirm(CheckoutScope scope, OfflineReceiptReq request) {
        var actor = access.requireCurrentCashier(scope);
        String key = CheckoutDigests.hash(request.requestKey());
        var row =
                unscoped(
                        () ->
                                requests.findKey(
                                        actor.realm(),
                                        actor.subjectKind(),
                                        actor.actorNo(),
                                        ACTION,
                                        key));
        if (row == null) {
            var op = operation(scope, request.operation());
            // Original offline consent and economics, not today's goods availability or store sale
            // switch.
            var instruction =
                    orders.offlineReceiptInstructionForCheckout(op.getOrderNo(), op.getBuyerNo());
            requireAmount(request, instruction);
            var proof = verify(scope, actor, request.evidenceRef());
            try {
                row =
                        unscoped(
                                () ->
                                        writes.execute(
                                                tx ->
                                                        accept(
                                                                scope,
                                                                request,
                                                                actor,
                                                                key,
                                                                instruction,
                                                                proof)));
            } catch (DuplicateKeyException race) {
                row =
                        unscoped(
                                () ->
                                        requests.findKey(
                                                actor.realm(),
                                                actor.subjectKind(),
                                                actor.actorNo(),
                                                ACTION,
                                                key));
                if (row == null) {
                    throw race;
                }
            }
        }
        var accepted = replay(scope, request, actor, row);
        try {
            var prior = payments.query(row.getPaymentOperationNo());
            if (prior.receipt() != null && "SUCCESS".equals(prior.receipt().status())) {
                return view(row, prior);
            }
        } catch (BizException failure) {
            if (failure.errorCode() != ErrorCode.NOT_FOUND) {
                throw failure;
            }
        }
        // An unfinished original instruction must still have actual private bytes and identical
        // owner facts.
        if (!Objects.equals(accepted.evidence(), verify(scope, actor, accepted.evidenceRef()))) {
            throw BizException.of(ErrorCode.CHECKOUT_RECEIPT_EVIDENCE_INVALID);
        }
        if (!actor.equals(access.requireCurrentCashier(scope))) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        var payment = json.readValue(row.getPaymentRequestJson(), SettlePort.PaymentOpen.class);
        var operation =
                new PaymentOperationReq(
                        "MAIN",
                        row.getPaymentOperationNo(),
                        row.getRequestHash(),
                        row.getOrderNo(),
                        row.getBuyerNo(),
                        row.getEntityNo(),
                        row.getStoreNo(),
                        payment);
        return view(
                row,
                payments.confirmOffline(
                        new OfflinePaymentReceiptReq(
                                operation,
                                accepted.method(),
                                accepted.evidenceRef(),
                                row.getActorNo(),
                                row.getActorRealm(),
                                row.getActorKind(),
                                row.getActorAccountNo(),
                                accepted.receivedAt(),
                                accepted.note(),
                                accepted.evidence())));
    }

    @Override
    public PaymentAttemptView find(CheckoutScope scope, PaymentLookup request) {
        return reads.findPayment(scope, request);
    }

    private OrdCheckoutPaymentRequest accept(
            CheckoutScope scope,
            OfflineReceiptReq request,
            CashierActor actor,
            String key,
            SettlePort.PaymentOpen instruction,
            ReceiptEvidence proof) {
        var op = operations.lock(request.operation().caller(), request.operation().operationNo());
        requireScope(scope, op);
        var existing =
                requests.findKey(actor.realm(), actor.subjectKind(), actor.actorNo(), ACTION, key);
        if (existing != null) {
            return existing;
        }
        var order =
                orderRows.selectOne(
                        Wrappers.<OrdOrder>lambdaQuery()
                                .eq(OrdOrder::getTenantNo, "MAIN")
                                .eq(OrdOrder::getOrderNo, op.getOrderNo())
                                .last("FOR UPDATE"));
        var confirmation =
                confirmations.selectOne(
                        Wrappers.<OrdCheckoutConfirmation>lambdaQuery()
                                .eq(OrdCheckoutConfirmation::getTenantNo, "MAIN")
                                .eq(
                                        OrdCheckoutConfirmation::getConfirmationNo,
                                        op.getConfirmationNo()));
        if (order == null
                || !op.getBuyerNo().equals(order.getUserNo())
                || !op.getOrderNo().equals(instruction.orderNo())
                || !op.getBuyerNo().equals(instruction.userNo())
                || !op.getEntityNo().equals(instruction.entityNo())
                || order.getPayAmount() == null
                || order.getPayAmount() != instruction.amountMinor()
                || !Objects.equals(order.getCurrency(), instruction.currency())
                || !Set.of(
                                OrdOrder.WAIT_OFFLINE_PAY,
                                OrdOrder.PAID,
                                OrdOrder.CLOSED,
                                OrdOrder.CANCELLED)
                        .contains(order.getStatus())
                || confirmation == null
                || !"CONSUMED".equals(confirmation.getStatus())
                || !"ORDINARY_SERVICE_PURCHASE".equals(confirmation.getPurpose())
                || !op.getBuyerNo().equals(confirmation.getBuyerNo())
                || !op.getCaller().equals(confirmation.getCaller())
                || !op.getOperationNo().equals(confirmation.getOperationNo())
                || !op.getOrderNo().equals(confirmation.getOrderNo())
                || !op.getCurrentQuoteNo().equals(confirmation.getQuoteNo())
                || confirmation.getAmountMinor() == null
                || confirmation.getAmountMinor() != request.amountMinor()
                || !request.currency().equals(confirmation.getCurrency())
                || confirmation.getConsumedAt() == null) {
            throw conflict();
        }
        // The immutable UTC consumption seal is slightly later than local-time order creation in
        // the same TX.
        Instant consumed = confirmation.getConsumedAt().toInstant(ZoneOffset.UTC);
        if (request.receivedAt().isBefore(consumed)
                || request.receivedAt().isAfter(clock.now().plusSeconds(120))) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var accepted =
                new AcceptedOffline(
                        request.method(),
                        request.amountMinor(),
                        request.currency(),
                        request.evidenceRef(),
                        request.receivedAt(),
                        request.note(),
                        actor.accountNo(),
                        op.getConfirmationNo(),
                        op.getCurrentQuoteNo(),
                        proof);
        var row = new OrdCheckoutPaymentRequest();
        row.setTenantNo("MAIN");
        row.setCaller(op.getCaller());
        row.setOperationNo(op.getOperationNo());
        row.setPaymentOperationNo("PO" + UUID.randomUUID().toString().replace("-", ""));
        row.setOrderNo(op.getOrderNo());
        row.setSubOrderNo(op.getSubOrderNo());
        row.setBuyerNo(op.getBuyerNo());
        row.setEntityNo(op.getEntityNo());
        row.setStoreNo(op.getStoreNo());
        row.setActorNo(actor.actorNo());
        row.setActorRealm(actor.realm());
        row.setActorKind(actor.subjectKind());
        row.setActorAccountNo(actor.accountNo());
        row.setAction(ACTION);
        row.setRequestKeyHash(key);
        row.setRequestJson(json.writeValueAsString(accepted));
        row.setPaymentRequestJson(json.writeValueAsString(instruction));
        row.setRequestHash(
                CheckoutDigests.hash(
                        json,
                        Arrays.asList(
                                1,
                                "MAIN",
                                op.getCaller(),
                                op.getOperationNo(),
                                op.getOrderNo(),
                                op.getSubOrderNo(),
                                op.getBuyerNo(),
                                op.getEntityNo(),
                                op.getStoreNo(),
                                actor.realm(),
                                actor.subjectKind(),
                                actor.actorNo(),
                                actor.accountNo(),
                                ACTION,
                                accepted,
                                instruction)));
        requests.insert(row);
        return row;
    }

    private AcceptedOffline replay(
            CheckoutScope scope,
            OfflineReceiptReq request,
            CashierActor actor,
            OrdCheckoutPaymentRequest row) {
        var op = operation(scope, request.operation());
        var accepted = json.readValue(row.getRequestJson(), AcceptedOffline.class);
        if (!op.getCaller().equals(row.getCaller())
                || !op.getOperationNo().equals(row.getOperationNo())
                || !op.getOrderNo().equals(row.getOrderNo())
                || !op.getSubOrderNo().equals(row.getSubOrderNo())
                || !op.getBuyerNo().equals(row.getBuyerNo())
                || !scope.entityNo().equals(row.getEntityNo())
                || !scope.storeNo().equals(row.getStoreNo())
                || !actor.actorNo().equals(row.getActorNo())
                || !actor.realm().equals(row.getActorRealm())
                || !actor.subjectKind().equals(row.getActorKind())
                || !actor.accountNo().equals(row.getActorAccountNo())
                || !ACTION.equals(row.getAction())
                || !request.method().equals(accepted.method())
                || request.amountMinor() != accepted.amountMinor()
                || !request.currency().equals(accepted.currency())
                || !Objects.equals(request.evidenceRef(), accepted.evidenceRef())
                || !request.receivedAt().equals(accepted.receivedAt())
                || !Objects.equals(request.note(), accepted.note())
                || !actor.accountNo().equals(accepted.accountNo())
                || !op.getConfirmationNo().equals(accepted.confirmationNo())
                || !op.getCurrentQuoteNo().equals(accepted.quoteNo())) {
            throw conflict();
        }
        return accepted;
    }

    private ReceiptEvidence verify(CheckoutScope scope, CashierActor actor, String reference) {
        return reference == null
                ? null
                : evidence.verify(
                        new ReceiptEvidenceQuery(
                                "MAIN",
                                scope.entityNo(),
                                scope.storeNo(),
                                actor.accountNo(),
                                reference));
    }

    private OrdCheckoutOperation operation(CheckoutScope scope, OperationRef ref) {
        var op = unscoped(() -> operations.find(ref.caller(), ref.operationNo()));
        requireScope(scope, op);
        return op;
    }

    private static void requireScope(CheckoutScope scope, OrdCheckoutOperation op) {
        if (op == null
                || !scope.entityNo().equals(op.getEntityNo())
                || !scope.storeNo().equals(op.getStoreNo())
                || !"CREATED".equals(op.getPhase())
                || op.getOrderNo() == null
                || op.getSubOrderNo() == null
                || op.getBuyerNo() == null
                || op.getConfirmationNo() == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
    }

    private static void requireAmount(OfflineReceiptReq request, SettlePort.PaymentOpen payment) {
        if (request.amountMinor() != payment.amountMinor()
                || !request.currency().equals(payment.currency())
                || !"OFFLINE".equals(payment.payChannel())
                || !payment.stores().isEmpty()) {
            throw conflict();
        }
    }

    private PaymentAttemptView view(OrdCheckoutPaymentRequest row, PaymentOperationView observed) {
        if (!row.getPaymentOperationNo().equals(observed.operationNo())
                || !row.getRequestHash().equals(observed.requestHash())) {
            throw conflict();
        }
        var original = json.readValue(row.getPaymentRequestJson(), SettlePort.PaymentOpen.class);
        return new PaymentAttemptView(
                new OperationRef(row.getCaller(), row.getOperationNo()),
                row.getPaymentOperationNo(),
                observed.phase(),
                observed.paymentNo(),
                observed.outTradeNo(),
                original.payChannel(),
                original.currency(),
                original.amountMinor(),
                Map.of(),
                observed.receiptNo(),
                observed.reasonCode(),
                observed.observedAt());
    }

    private static <T> T unscoped(Supplier<T> action) {
        return DataScopeContext.executeWithoutScope(action);
    }

    private static BizException conflict() {
        return BizException.of(ErrorCode.CHECKOUT_PAYMENT_CONFLICT);
    }

    /**
     * Immutable full original intent; diagnostics never expose the attachment or reusable client
     * key.
     */
    public record AcceptedOffline(
            String method,
            long amountMinor,
            String currency,
            String evidenceRef,
            Instant receivedAt,
            String note,
            String accountNo,
            String confirmationNo,
            String quoteNo,
            ReceiptEvidence evidence) {
        @Override
        public String toString() {
            return "AcceptedOffline[redacted]";
        }
    }
}
