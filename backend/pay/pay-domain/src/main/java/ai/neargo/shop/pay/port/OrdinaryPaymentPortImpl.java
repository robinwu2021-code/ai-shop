package ai.neargo.shop.pay.port;

import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.pay.PayQueryPort;
import ai.neargo.shop.spi.settle.OrdinaryPaymentPort;
import ai.neargo.shop.spi.settle.SettlePort;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Embedded PAY owner; ordinary ledger RPC never impersonates channel initiation or cancellation. */
public class OrdinaryPaymentPortImpl implements OrdinaryPaymentPort {
    private static final Logger log = LoggerFactory.getLogger(OrdinaryPaymentPortImpl.class);
    private final PaymentLedgerService ledger;
    private final PayQueryPort channel;

    public OrdinaryPaymentPortImpl(PaymentLedgerService ledger, PayQueryPort channel) {
        this.ledger = ledger;
        this.channel = channel;
    }

    @Override
    public Attempt open(SettlePort.PaymentOpen request) {
        if (request == null) {
            throw new IllegalArgumentException("PAY_REQUEST_INVALID");
        }
        require(request.orderNo());
        require(request.userNo());
        String actual = ledger.open(request);
        Attempt payment = ledger.ordinaryPayment(actual);
        if (payment == null) {
            throw new IllegalStateException("PAY_OPEN_RECEIPT_MISSING");
        }
        verifyOwner(payment, request.orderNo(), request.userNo());
        return payment;
    }

    @Override
    public Lookup query(Reference reference) {
        if (reference == null) {
            throw new IllegalArgumentException("PAY_REFERENCE_INVALID");
        }
        require(reference.orderNo());
        require(reference.userNo());
        require(reference.outTradeNo());
        Attempt payment = ledger.ordinaryPayment(reference.outTradeNo());
        if (payment != null) {
            verifyOwner(payment, reference.orderNo(), reference.userNo());
            if (!Objects.equals(payment.outTradeNo(), reference.outTradeNo())) {
                throw new IllegalArgumentException("PAY_REFERENCE_CONFLICT");
            }
        }
        return new Lookup(payment != null, payment);
    }

    @Override
    public Lookup refresh(Reference reference) {
        Lookup original = query(reference);
        if (!original.found() || "SUCCESS".equals(original.payment().status())) return original;
        Attempt payment = original.payment();
        ChannelEvidence evidence = queryChannel(reference);
        if (evidence.status() != ChannelStatus.PAID || payment.amountMinor() == null
                || !Objects.equals(payment.amountMinor(), evidence.amountMinor())
                || payment.currency() == null || !Objects.equals(payment.currency(), evidence.currency())
                || evidence.tradeNo() == null || evidence.tradeNo().isBlank()) return original;
        String order = ledger.settle(new SettlePort.PaymentSettled(payment.outTradeNo(), payment.payChannel(),
                evidence.tradeNo(), evidence.amountMinor(), System.currentTimeMillis(), evidence.currency()));
        if (order != null && !Objects.equals(order, payment.orderNo()))
            throw new IllegalStateException("PAY_REFRESH_OWNER_CONFLICT");
        return query(reference);
    }

    @Override
    public Lookup success(OrderReference reference) {
        if (reference == null) {
            throw new IllegalArgumentException("PAY_REFERENCE_INVALID");
        }
        require(reference.orderNo());
        require(reference.userNo());
        Attempt payment = ledger.successfulOrdinaryPayment(reference.orderNo());
        if (payment != null) {
            verifyOwner(payment, reference.orderNo(), reference.userNo());
            if (!"SUCCESS".equals(payment.status())) {
                throw new IllegalStateException("PAY_SUCCESS_RECEIPT_INVALID");
            }
        }
        return new Lookup(payment != null, payment);
    }

    @Override
    public ChannelEvidence queryChannel(Reference reference) {
        Lookup lookup = query(reference);
        if (!lookup.found()) {
            return unknown(reference.outTradeNo());
        }
        Attempt payment = lookup.payment();
        try {
            PayQueryPort.Result result = channel.query(payment.payChannel(), payment.outTradeNo());
            if (result == null || !result.ok()) {
                return unknown(payment.outTradeNo());
            }
            if (!result.found()) {
                return new ChannelEvidence(payment.outTradeNo(), ChannelStatus.NOT_FOUND, null, null, null);
            }
            if (!result.paid()) {
                return new ChannelEvidence(payment.outTradeNo(), ChannelStatus.UNPAID, null, null, null);
            }
            return new ChannelEvidence(payment.outTradeNo(), ChannelStatus.PAID,
                    result.amountMinor(), result.currency(), result.tradeNo());
        } catch (RuntimeException failure) {
            log.warn("Ordinary payment channel query outcome unknown for {}", payment.outTradeNo(), failure);
            return unknown(payment.outTradeNo());
        }
    }

    private static ChannelEvidence unknown(String outTradeNo) {
        return new ChannelEvidence(outTradeNo, ChannelStatus.UNKNOWN, null, null, null);
    }

    private static void verifyOwner(Attempt payment, String orderNo, String userNo) {
        if (!Objects.equals(payment.orderNo(), orderNo) || !Objects.equals(payment.userNo(), userNo)) {
            throw new IllegalArgumentException("PAY_OWNER_CONFLICT");
        }
    }

    private static void require(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("PAY_REFERENCE_INVALID");
        }
    }
}
