package ai.neargo.shop.trade.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.settle.PaymentOperationPort;
import ai.neargo.shop.spi.settle.PaymentOperationPort.PaymentOperationReq;
import ai.neargo.shop.spi.settle.PaymentOperationPort.PaymentOperationView;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutScope;
import ai.neargo.shop.spi.trade.CheckoutPort.InitiatePaymentReq;
import ai.neargo.shop.spi.trade.CheckoutPort.OperationRef;
import ai.neargo.shop.spi.trade.CheckoutPort.PaymentAttemptView;
import ai.neargo.shop.spi.trade.CheckoutPort.PaymentLookup;
import ai.neargo.shop.trade.entity.OrdCheckoutOperation;
import ai.neargo.shop.trade.entity.OrdCheckoutPaymentRequest;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.mapper.CheckoutMappers.OperationMapper;
import ai.neargo.shop.trade.mapper.CheckoutMappers.PaymentRequestMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderMapper;
import ai.neargo.shop.trade.order.service.create.CheckoutClock;
import ai.neargo.shop.trade.order.service.create.CheckoutDigests;
import ai.neargo.shop.trade.policy.CheckoutAccessPolicy;
import ai.neargo.shop.trade.service.CheckoutPaymentService;
import ai.neargo.shop.trade.service.OrderService;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Commit the first key and owner instruction before any financial call; reads never dispatch. */
@Service
public class CheckoutPaymentServiceImpl implements CheckoutPaymentService {
    private static final String INITIATE = "INITIATE_PAYMENT";
    private final CheckoutAccessPolicy access;
    private final OperationMapper operations;
    private final PaymentRequestMapper requests;
    private final OrderMapper orderRows;
    private final OrderService orders;
    private final PaymentOperationPort payments;
    private final ObjectMapper json;
    private final CheckoutClock clock;
    private final TransactionTemplate writes;

    /**
     * Keep order/request writes on the platform manager and call the financial owner only after
     * commit.
     */
    public CheckoutPaymentServiceImpl(
            CheckoutAccessPolicy access,
            OperationMapper operations,
            PaymentRequestMapper requests,
            OrderMapper orderRows,
            OrderService orders,
            PaymentOperationPort payments,
            ObjectMapper json,
            CheckoutClock clock,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.access = access;
        this.operations = operations;
        this.requests = requests;
        this.orderRows = orderRows;
        this.orders = orders;
        this.payments = payments;
        this.json = json;
        this.clock = clock;
        writes = new TransactionTemplate(manager);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public PaymentAttemptView initiatePayment(InitiatePaymentReq request) {
        String buyer = access.requireCurrentBuyer();
        buyerOperation(request.operation(), buyer);
        String key = CheckoutDigests.hash(request.requestKey());
        var row = unscoped(() -> requests.findKey("CONSUMER", "USR", buyer, INITIATE, key));
        if (row == null) {
            var operation = buyerOperation(request.operation(), buyer);
            // Route resolution can call another owner; it must finish before checkout/order locks.
            var instruction =
                    orders.paymentInstructionForCheckout(
                            operation.getOrderNo(), buyer, request.channel());
            try {
                row =
                        unscoped(
                                () ->
                                        writes.execute(
                                                tx -> accept(request, buyer, key, instruction)));
            } catch (DuplicateKeyException collision) {
                row = unscoped(() -> requests.findKey("CONSUMER", "USR", buyer, INITIATE, key));
                if (row == null) {
                    throw collision;
                }
            }
        }
        requireReplay(row, request, buyer);
        // Authorization is current on every explicit replay, never reconstructed from the persisted
        // buyer.
        requireStillBuyer(buyer);
        var prepared = payments.prepare(instruction(row));
        if (Set.of("PREPARED", "OPENED").contains(prepared.phase())) {
            try {
                // This lock-free current owner read authorizes the first send. Later cancellation
                // is a late race.
                orders.requireCheckoutPaymentSendAllowed(row.getOrderNo(), buyer);
            } catch (BizException noLongerPayable) {
                var original = payments.query(row.getPaymentOperationNo());
                requireStillBuyer(buyer);
                if (Set.of("SENDING", "UNKNOWN", "PAYABLE", "SUCCEEDED", "MANUAL_REQUIRED")
                        .contains(original.phase())) {
                    // Another accepted sender may have won; only recover that original, never
                    // dispatch here.
                    return view(row, original, true);
                }
                throw noLongerPayable;
            }
        }
        var observed = payments.initiate(row.getPaymentOperationNo());
        requireStillBuyer(buyer);
        return view(row, observed, true);
    }

    private OrdCheckoutPaymentRequest accept(
            InitiatePaymentReq request,
            String buyer,
            String key,
            SettlePort.PaymentOpen instruction) {
        var op = operations.lock(request.operation().caller(), request.operation().operationNo());
        requirePlaced(op, buyer);
        var existing = requests.findKey("CONSUMER", "USR", buyer, INITIATE, key);
        if (existing != null) {
            return existing;
        }
        var order =
                orderRows.selectOne(
                        Wrappers.<OrdOrder>lambdaQuery()
                                .eq(OrdOrder::getTenantNo, "MAIN")
                                .eq(OrdOrder::getOrderNo, op.getOrderNo())
                                .last("FOR UPDATE"));
        if (order == null
                || !buyer.equals(order.getUserNo())
                || !op.getOrderNo().equals(instruction.orderNo())
                || !op.getEntityNo().equals(instruction.entityNo())
                || order.getPayAmount() == null
                || order.getPayAmount() != instruction.amountMinor()
                || !Objects.equals(order.getCurrency(), instruction.currency())
                || !OrdOrder.WAIT_PAY.equals(order.getStatus())
                || order.getPayDeadlineAt() != null
                        && order.getPayDeadlineAt() < clock.now().toEpochMilli()) {
            throw BizException.of(ErrorCode.CHECKOUT_PAYMENT_CONFLICT);
        }
        var accepted =
                new AcceptedOnline(
                        request.channel(), op.getConfirmationNo(), op.getCurrentQuoteNo());
        var row = new OrdCheckoutPaymentRequest();
        row.setTenantNo("MAIN");
        row.setCaller(op.getCaller());
        row.setOperationNo(op.getOperationNo());
        row.setPaymentOperationNo("PO" + UUID.randomUUID().toString().replace("-", ""));
        row.setOrderNo(op.getOrderNo());
        row.setSubOrderNo(op.getSubOrderNo());
        row.setBuyerNo(buyer);
        row.setEntityNo(op.getEntityNo());
        row.setStoreNo(op.getStoreNo());
        row.setActorNo(buyer);
        row.setActorRealm("CONSUMER");
        row.setActorKind("USR");
        row.setAction(INITIATE);
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
                                buyer,
                                op.getEntityNo(),
                                op.getStoreNo(),
                                "CONSUMER",
                                "USR",
                                buyer,
                                INITIATE,
                                accepted,
                                instruction)));
        requests.insert(row);
        return row;
    }

    private void requireReplay(
            OrdCheckoutPaymentRequest row, InitiatePaymentReq request, String buyer) {
        var op = buyerOperation(request.operation(), buyer);
        var accepted = json.readValue(row.getRequestJson(), AcceptedOnline.class);
        if (!request.operation().caller().equals(row.getCaller())
                || !request.operation().operationNo().equals(row.getOperationNo())
                || !buyer.equals(row.getBuyerNo())
                || !buyer.equals(row.getActorNo())
                || !"CONSUMER".equals(row.getActorRealm())
                || !"USR".equals(row.getActorKind())
                || !INITIATE.equals(row.getAction())
                || !op.getOrderNo().equals(row.getOrderNo())
                || !op.getSubOrderNo().equals(row.getSubOrderNo())
                || !op.getEntityNo().equals(row.getEntityNo())
                || !op.getStoreNo().equals(row.getStoreNo())
                || !Objects.equals(accepted.requestedChannel(), request.channel())
                || !Objects.equals(op.getConfirmationNo(), accepted.confirmationNo())
                || !Objects.equals(op.getCurrentQuoteNo(), accepted.quoteNo())) {
            throw BizException.of(ErrorCode.CHECKOUT_PAYMENT_CONFLICT);
        }
    }

    @Override
    public PaymentAttemptView findPaymentForCurrentBuyer(PaymentLookup request) {
        String buyer = access.requireCurrentBuyer();
        var op = buyerOperation(request.operation(), buyer);
        var row =
                unscoped(
                        () ->
                                request.paymentOperationNo() == null
                                        ? requests.findKey(
                                                "CONSUMER",
                                                "USR",
                                                buyer,
                                                INITIATE,
                                                CheckoutDigests.hash(request.requestKey()))
                                        : requests.find(request.paymentOperationNo()));
        requireMapping(row, op);
        if (!"CONSUMER".equals(row.getActorRealm())
                || !"USR".equals(row.getActorKind())
                || !buyer.equals(row.getActorNo())
                || !INITIATE.equals(row.getAction())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        var observed = observe(row);
        requireStillBuyer(buyer);
        return view(row, observed, true);
    }

    @Override
    public PaymentAttemptView findPayment(CheckoutScope scope, PaymentLookup request) {
        var actor = access.requireMerchant(scope, BizPerms.ORDER_VIEW);
        var op =
                unscoped(
                        () ->
                                operations.find(
                                        request.operation().caller(),
                                        request.operation().operationNo()));
        if (op == null
                || !scope.entityNo().equals(op.getEntityNo())
                || !scope.storeNo().equals(op.getStoreNo())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        var row =
                unscoped(
                        () ->
                                request.paymentOperationNo() == null
                                        ? requests.findKey(
                                                actor.realm(),
                                                actor.subjectKind(),
                                                actor.actorNo(),
                                                "OFFLINE_RECEIPT",
                                                CheckoutDigests.hash(request.requestKey()))
                                        : requests.find(request.paymentOperationNo()));
        requireMapping(row, op);
        return view(row, observe(row), false);
    }

    private PaymentOperationView observe(OrdCheckoutPaymentRequest row) {
        try {
            return payments.query(row.getPaymentOperationNo());
        } catch (BizException failure) {
            if (failure.errorCode() != ErrorCode.NOT_FOUND) {
                throw failure;
            }
            // P0 was committed but P1 was never accepted. A read must not call prepare or send.
            return new PaymentOperationView(
                    row.getPaymentOperationNo(),
                    null,
                    "PREPARED",
                    null,
                    null,
                    row.getRequestHash(),
                    null,
                    null,
                    null,
                    Map.of(),
                    null,
                    clock.now());
        }
    }

    private PaymentAttemptView view(
            OrdCheckoutPaymentRequest row, PaymentOperationView payment, boolean parameters) {
        if (!row.getPaymentOperationNo().equals(payment.operationNo())
                || !row.getRequestHash().equals(payment.requestHash())) {
            throw BizException.of(ErrorCode.CHECKOUT_PAYMENT_CONFLICT);
        }
        var frozen = json.readValue(row.getPaymentRequestJson(), SettlePort.PaymentOpen.class);
        return new PaymentAttemptView(
                new OperationRef(row.getCaller(), row.getOperationNo()),
                row.getPaymentOperationNo(),
                payment.phase(),
                payment.paymentNo(),
                payment.outTradeNo(),
                frozen.payChannel(),
                frozen.currency(),
                frozen.amountMinor(),
                parameters ? payment.payParams() : Map.of(),
                payment.receiptNo(),
                payment.reasonCode(),
                payment.observedAt());
    }

    private PaymentOperationReq instruction(OrdCheckoutPaymentRequest row) {
        return new PaymentOperationReq(
                row.getTenantNo(),
                row.getPaymentOperationNo(),
                row.getRequestHash(),
                row.getOrderNo(),
                row.getBuyerNo(),
                row.getEntityNo(),
                row.getStoreNo(),
                json.readValue(row.getPaymentRequestJson(), SettlePort.PaymentOpen.class));
    }

    private OrdCheckoutOperation buyerOperation(OperationRef ref, String buyer) {
        var op = unscoped(() -> operations.findForBuyer(ref.caller(), ref.operationNo(), buyer));
        requirePlaced(op, buyer);
        return op;
    }

    private static void requirePlaced(OrdCheckoutOperation op, String buyer) {
        if (op != null && "MANUAL_OFFLINE".equals(op.getAdmissionKind())) {
            throw BizException.of(ErrorCode.CHECKOUT_PAYMENT_CONFLICT);
        }
        if (op == null
                || !buyer.equals(op.getBuyerNo())
                || !"CREATED".equals(op.getPhase())
                || op.getOrderNo() == null
                || op.getSubOrderNo() == null
                || op.getConfirmationNo() == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
    }

    private static void requireMapping(OrdCheckoutPaymentRequest row, OrdCheckoutOperation op) {
        if (row == null
                || !op.getCaller().equals(row.getCaller())
                || !op.getOperationNo().equals(row.getOperationNo())
                || !op.getOrderNo().equals(row.getOrderNo())
                || !op.getSubOrderNo().equals(row.getSubOrderNo())
                || !op.getBuyerNo().equals(row.getBuyerNo())
                || !op.getEntityNo().equals(row.getEntityNo())
                || !op.getStoreNo().equals(row.getStoreNo())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
    }

    private void requireStillBuyer(String buyer) {
        if (!buyer.equals(access.requireCurrentBuyer())) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
    }

    private static <T> T unscoped(Supplier<T> work) {
        return DataScopeContext.executeWithoutScope(work);
    }

    /** No reusable key or payment parameter is stored in this request payload. */
    public record AcceptedOnline(String requestedChannel, String confirmationNo, String quoteNo) {}
}
