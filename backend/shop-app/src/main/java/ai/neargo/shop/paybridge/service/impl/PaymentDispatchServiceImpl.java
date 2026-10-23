package ai.neargo.shop.paybridge.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.channel.PayGateway;
import ai.neargo.shop.pay.channel.PayGatewayRouter;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.pay.service.PaymentOperationService;
import ai.neargo.shop.pay.service.PaymentOperationService.NotSentReason;
import ai.neargo.shop.paybridge.service.PaymentDispatchService;
import ai.neargo.shop.spi.media.ReceiptEvidencePort;
import ai.neargo.shop.spi.settle.PaymentOperationPort.OfflinePaymentReceiptReq;
import ai.neargo.shop.spi.settle.PaymentOperationPort.PaymentOperationReq;
import ai.neargo.shop.spi.settle.PaymentOperationPort.PaymentOperationView;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.trade.OrderRepairPort;
import ai.neargo.shop.spi.user.UserIdentityPort;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Network calls never share a database transaction with dispatch claims or order projection locks.
 */
@Service
public class PaymentDispatchServiceImpl implements PaymentDispatchService {
    private final PaymentOperationService operations;
    private final PaymentLedgerService ledger;
    private final PayGatewayRouter gateways;
    private final UserIdentityPort identities;
    private final ReceiptEvidencePort evidence;
    private final ObjectProvider<OrderRepairPort> orders;
    private final String wechatAppId;

    /** Resolve real owner providers while keeping their I/O outside financial locks. */
    public PaymentDispatchServiceImpl(
            PaymentOperationService operations,
            PaymentLedgerService ledger,
            PayGatewayRouter gateways,
            UserIdentityPort identities,
            ReceiptEvidencePort evidence,
            ObjectProvider<OrderRepairPort> orders,
            @Value("${shop.pay.wechat.appid:}") String wechatAppId) {
        this.operations = operations;
        this.ledger = ledger;
        this.gateways = gateways;
        this.identities = identities;
        this.evidence = evidence;
        this.orders = orders;
        this.wechatAppId = wechatAppId;
    }

    @Override
    public PaymentOperationView prepare(PaymentOperationReq request) {
        outside();
        return operations.prepare(request);
    }

    @Override
    public PaymentOperationView initiate(String operationNo) {
        outside();
        var result = dispatchOriginal(operationNo);
        // The ticket belongs to the canonical root, while the caller keeps its own durable
        // identity.
        return operationNo.equals(result.operationNo()) ? result : operations.find(operationNo);
    }

    private PaymentOperationView dispatchOriginal(String operationNo) {
        var opened = operations.open(operationNo);
        if (!"OPENED".equals(opened.phase())) {
            return query(operationNo);
        }
        var request = ledger.paymentRequest(opened.outTradeNo()).orElseThrow();
        PayGateway gateway;
        try {
            gateway = gateways.of(request.payChannel());
        } catch (RuntimeException missing) {
            gateway = null;
        }
        String payer =
                gateway == null ? null : identities.wxPayerOpenIdMp(request.userNo()).orElse(null);
        var ticket = operations.claimSend(operationNo);
        if (ticket.isEmpty()) {
            return query(operationNo);
        }
        if (gateway == null) {
            return operations.rejectNotSent(ticket.get(), NotSentReason.NO_GATEWAY);
        }
        request = ticket.get().payment();
        String account =
                !request.stores().isEmpty()
                                && "DIRECT".equals(request.stores().getFirst().route().fundsPath())
                        ? request.stores().getFirst().route().subMerchantId()
                        : null;
        PayGateway.PrepayResult result;
        try {
            outside();
            result =
                    gateway.prepay(
                            new PayGateway.PrepayCommand(
                                    ticket.get().outTradeNo(),
                                    request.amountMinor(),
                                    request.currency(),
                                    request.subject(),
                                    "JSAPI",
                                    account,
                                    payer));
        } catch (RuntimeException uncertain) {
            return operations.recordUnknown(ticket.get(), "PAYMENT_RESPONSE_UNKNOWN");
        }
        if (result == null || result.disposition() == PayGateway.PrepayDisposition.UNKNOWN) {
            return operations.recordUnknown(ticket.get(), "PAYMENT_RESPONSE_UNKNOWN");
        }
        if (result.disposition() == PayGateway.PrepayDisposition.NOT_SENT) {
            return operations.rejectNotSent(ticket.get(), NotSentReason.LOCAL_PRECONDITION);
        }
        ledger.recordPayer(
                ticket.get().outTradeNo(),
                payer,
                "WECHAT".equals(request.payChannel()) ? wechatAppId : null);
        var payable =
                operations.recordPayable(
                        ticket.get(), result.params(), Instant.now().plusSeconds(300));
        if ("FREE".equals(request.payChannel())) {
            ledger.settle(
                    new SettlePort.PaymentSettled(
                            ticket.get().outTradeNo(),
                            "FREE",
                            result.tradeNo(),
                            0L,
                            System.currentTimeMillis(),
                            request.currency()));
            return project(operations.observeReceipt(operationNo));
        }
        return payable;
    }

    @Override
    public PaymentOperationView query(String operationNo) {
        outside();
        var view = operations.observeReceipt(operationNo);
        if (view.outTradeNo() == null
                || "REJECTED".equals(view.phase())
                || "OPENED".equals(view.phase())) {
            return view;
        }
        if (!"SUCCEEDED".equals(view.phase()) && !"MANUAL_REQUIRED".equals(view.phase())) {
            var original = ledger.paymentByOutTradeNo(view.outTradeNo());
            try {
                var result = gateways.of(original.payChannel()).query(view.outTradeNo());
                if (result != null
                        && result.ok()
                        && result.found()
                        && result.paid()
                        && result.amountMinor() >= 0
                        && result.currency() != null
                        && !result.currency().isBlank()
                        && result.tradeNo() != null
                        && !result.tradeNo().isBlank()) {
                    ledger.settle(
                            new SettlePort.PaymentSettled(
                                    view.outTradeNo(),
                                    original.payChannel(),
                                    result.tradeNo(),
                                    result.amountMinor(),
                                    System.currentTimeMillis(),
                                    result.currency()));
                    view = operations.observeReceipt(operationNo);
                }
            } catch (RuntimeException uncertain) {
                // Not found, pending and transport failures never close or resend the original
                // number.
            }
        }
        return project(view);
    }

    @Override
    public PaymentOperationView confirmOffline(OfflinePaymentReceiptReq request) {
        outside();
        PaymentOperationView prior = null;
        try {
            prior = operations.find(request.operation().operationNo());
        } catch (BizException missing) {
            if (missing.errorCode() != ErrorCode.NOT_FOUND) {
                throw missing;
            }
        }
        if (prior == null
                || !("SUCCEEDED".equals(prior.phase())
                        || "MANUAL_REQUIRED".equals(prior.phase()))) {
            if (request.evidenceRef() != null) {
                var proof =
                        evidence.verify(
                                new ReceiptEvidencePort.ReceiptEvidenceQuery(
                                        request.operation().tenantNo(),
                                        request.operation().entityNo(),
                                        request.operation().storeNo(),
                                        request.actorAccountNo(),
                                        request.evidenceRef()));
                if (!Objects.equals(proof, request.evidence())) {
                    throw new IllegalArgumentException("RECEIPT_EVIDENCE_CHANGED");
                }
            }
        }
        return project(operations.confirmOffline(request));
    }

    @Override
    public SettlePort.PayInitResult initiateLegacy(SettlePort.PaymentOpen payment) {
        outside();
        var prepared = operations.prepareLegacy(payment);
        var result = initiate(prepared.operationNo());
        boolean ready =
                "SUCCEEDED".equals(result.phase())
                        || "PAYABLE".equals(result.phase()) && !result.payParams().isEmpty();
        return new SettlePort.PayInitResult(
                ready,
                result.outTradeNo(),
                payment.payChannel(),
                result.payParams(),
                ready ? null : "PAYMENT_ORIGINAL_RESULT_PENDING");
    }

    private PaymentOperationView project(PaymentOperationView view) {
        if (view.receipt() == null || !"SUCCESS".equals(view.receipt().status())) {
            return view;
        }
        try {
            outside();
            DataScopeContext.executeWithoutScope(
                    () -> {
                        orders.getObject()
                                .markPaid(
                                        view.receipt().orderNo(),
                                        view.receipt().payChannel(),
                                        view.receipt().tradeNo());
                        return null;
                    });
            return view;
        } catch (RuntimeException projection) {
            return new PaymentOperationView(
                    view.operationNo(),
                    view.canonicalOperationNo(),
                    "MANUAL_REQUIRED",
                    view.paymentNo(),
                    view.outTradeNo(),
                    view.requestHash(),
                    view.receiptNo(),
                    "PAYMENT_ORDER_PROJECTION_PENDING",
                    view.receipt(),
                    Map.of(),
                    view.payParamsExpiresAt(),
                    Instant.now());
        }
    }

    private static void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Payment orchestration requires no caller transaction");
        }
    }
}
