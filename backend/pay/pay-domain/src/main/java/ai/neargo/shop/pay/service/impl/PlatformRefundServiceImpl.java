package ai.neargo.shop.pay.service.impl;

import ai.neargo.shop.pay.channel.*;
import ai.neargo.shop.pay.service.*;
import ai.neargo.shop.spi.pay.RefundQueryResult;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

import javax.sql.DataSource;

@Service
@ConditionalOnProperty(
        prefix = "commerce",
        name = {"enabled", "mall.refund.enabled"},
        havingValue = "true")
public class PlatformRefundServiceImpl implements PlatformRefundService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PlatformCollectionService collection;
    private final PayGatewayRouter gateways;
    private final JsonMapper json = JsonMapper.builder().build();

    public PlatformRefundServiceImpl(
            @Qualifier("payDataSource") DataSource ds,
            @Qualifier("payTxManager") PlatformTransactionManager manager,
            PlatformCollectionService collection,
            PayGatewayRouter gateways) {
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(manager);
        this.collection = collection;
        this.gateways = gateways;
    }

    private record Frozen(
            String business,
            Request request,
            String originalExternal,
            String originalProvider,
            String channel) {}

    private record Row(
            String paymentNo,
            Frozen frozen,
            String external,
            String state,
            String provider,
            Long verifiedAt,
            Long dispatched,
            String error) {}

    private static void require(boolean ok, String reason) {
        if (!ok) throw new IllegalArgumentException(reason);
    }

    private static void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Refund IO requires no caller transaction");
    }

    private static void text(String s, int max) {
        require(s != null && !s.isBlank() && s.length() <= max, "REFUND_IDENTITY_REQUIRED");
    }

    private static void key(Key k) {
        require(k != null, "REFUND_KEY_REQUIRED");
        text(k.tenantNo(), 32);
        text(k.orderNo(), 64);
        text(k.refundNo(), 64);
    }

    private static String digest(Key k) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            ("platform-refund:v1\0"
                                                            + k.tenantNo()
                                                            + "\0"
                                                            + k.orderNo()
                                                            + "\0"
                                                            + k.refundNo())
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String paymentNo(Key k) {
        return "MR" + digest(k).substring(0, 60);
    }

    private Row row(Key k) {
        var rows =
                jdbc.query(
                        "SELECT"
                            + " payment_no,refund_request_json,out_trade_no,status,refund_verified_provider_no,refund_verified_at,refund_dispatch_at,err_code"
                            + " FROM stl_payment WHERE tenant_no=? AND payment_no=? AND"
                            + " direction='REFUND' AND pay_scene='PLATFORM_MALL' FOR UPDATE",
                        (rs, n) ->
                                new Row(
                                        rs.getString(1),
                                        json.readValue(rs.getString(2), Frozen.class),
                                        rs.getString(3),
                                        rs.getString(4),
                                        rs.getString(5),
                                        rs.getObject(6, Long.class),
                                        rs.getObject(7, Long.class),
                                        rs.getString(8)),
                        k.tenantNo(),
                        paymentNo(k));
        if (rows.isEmpty()) return null;
        Row row = rows.getFirst();
        require(
                "PLATFORM_MALL".equals(row.frozen.business) && k.equals(row.frozen.request.key()),
                "REFUND_SCOPE_MISMATCH");
        return row;
    }

    private void lockOriginal(Request r) {
        var originals =
                jdbc.queryForList(
                        "SELECT"
                            + " amount_minor,currency,status,pay_scene,entity_no,order_no,refund_capacity_state"
                            + " FROM stl_payment WHERE tenant_no=? AND payment_no=? AND"
                            + " direction='PAY' FOR UPDATE",
                        r.key().tenantNo(),
                        r.originalPaymentNo());
        require(originals.size() == 1, "REFUND_ORIGINAL_REQUIRED");
        var original = originals.getFirst();
        require(
                "SUCCESS".equals(original.get("status"))
                        && ("PLATFORM_MALL".equals(original.get("pay_scene"))
                                || PlatformCollectionService.UNMATCHED_BUSINESS.equals(
                                        original.get("pay_scene")))
                        && !"FROZEN".equals(original.get("refund_capacity_state"))
                        && r.key().orderNo().equals(original.get("order_no"))
                        && r.merchantNo().equals(original.get("entity_no"))
                        && r.currency().equals(original.get("currency"))
                        && r.originalPaidMinor()
                                == ((Number) original.get("amount_minor")).longValue(),
                "REFUND_ORIGINAL_MISMATCH");
    }

    private Row prepare(Request r, PlatformCollectionService.Receipt original) {
        lockOriginal(r);
        Row existing = row(r.key());
        if (existing != null) {
            require(existing.frozen.request.equals(r), "REFUND_INTENT_CONFLICT");
            return existing;
        }
        var amounts =
                jdbc.queryForList(
                        "SELECT amount_minor FROM stl_payment WHERE tenant_no=? AND order_no=? AND"
                            + " direction='REFUND' AND (origin_payment_no=? OR origin_payment_no IS"
                            + " NULL) AND (refund_capacity_state IS NULL OR"
                            + " refund_capacity_state<>'RELEASED') AND deleted=0 FOR UPDATE",
                        Long.class,
                        r.key().tenantNo(),
                        r.key().orderNo(),
                        r.originalPaymentNo());
        long held = 0;
        for (Long amount : amounts) held = Math.addExact(held, amount);
        require(
                Math.addExact(held, r.refundMinor()) <= r.originalPaidMinor(),
                "REFUND_CASH_CAPACITY_EXCEEDED");
        String originalExternal =
                jdbc.queryForObject(
                        "SELECT out_trade_no FROM stl_payment WHERE payment_no=?",
                        String.class,
                        r.originalPaymentNo());
        String external = "R" + digest(r.key()).substring(0, 31);
        var frozen =
                new Frozen(
                        "PLATFORM_MALL",
                        r,
                        originalExternal,
                        original.providerNo(),
                        original.request().channel());
        jdbc.update(
                "INSERT INTO"
                    + " stl_payment(payment_no,direction,order_no,after_sale_no,refund_execution_no,origin_payment_no,refund_capacity_state,user_no,entity_no,pay_channel,pay_scene,pay_method,amount_minor,currency,status,out_trade_no,refund_request_json,tenant_no,created_at,updated_at,created_by,updated_by)"
                    + " VALUES"
                    + " (?,'REFUND',?,?,?,?,'HELD',?,?,?,'PLATFORM_MALL',?,?,?,'PENDING',?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),?,?)",
                paymentNo(r.key()),
                r.key().orderNo(),
                r.key().refundNo(),
                paymentNo(r.key()),
                r.originalPaymentNo(),
                original.request().buyerNo(),
                r.merchantNo(),
                original.request().channel(),
                original.request().method(),
                r.refundMinor(),
                r.currency(),
                external,
                json.writeValueAsString(frozen),
                r.key().tenantNo(),
                original.request().buyerNo(),
                original.request().buyerNo());
        return row(r.key());
    }

    private PayGateway gateway(Row row) {
        var gateway = gateways.of(row.frozen.channel);
        var capability =
                gateway.platformCollectionCapability()
                        .orElseThrow(
                                () -> new IllegalArgumentException("REFUND_ROUTE_UNAVAILABLE"));
        require(
                capability.merchantNo().equals(row.frozen.request.merchantNo())
                        && capability.currency().equals(row.frozen.request.currency())
                        && gateway.supportsRefundCurrency(row.frozen.request.currency()),
                "REFUND_ROUTE_MISMATCH");
        return gateway;
    }

    public Receipt refund(Request request) {
        outside();
        require(request != null, "REFUND_REQUEST_REQUIRED");
        key(request.key());
        text(request.storeNo(), 64);
        text(request.originalAttemptNo(), 64);
        text(request.originalPaymentNo(), 64);
        text(request.merchantNo(), 64);
        text(request.reason(), 1024);
        require(
                request.refundMinor() > 0 && request.originalPaidMinor() >= request.refundMinor(),
                "REFUND_AMOUNT_INVALID");
        var original =
                collection.query(
                        new PlatformCollectionService.Key(
                                request.key().tenantNo(),
                                request.key().orderNo(),
                                request.originalAttemptNo()));
        require(
                "SUCCESS".equals(original.state())
                        && request.originalPaymentNo().equals(original.paymentNo())
                        && request.merchantNo().equals(original.request().merchantNo())
                        && request.originalPaidMinor() == original.request().amountMinor()
                        && request.currency().equals(original.request().currency()),
                "REFUND_ORIGINAL_UNPROVEN");
        // Unmatched manual cash may only be returned through the offline payout flow.
        require(
                !PlatformCollectionService.UNMATCHED_BUSINESS.equals(scene(request))
                        || "OFFLINE".equals(original.request().channel()),
                "REFUND_ORIGINAL_MISMATCH");
        Row prepared = tx.execute(s -> prepare(request, original));
        if ("SUCCESS".equals(prepared.state)) return receipt(prepared);
        // Bank transfer originals: hold capacity and wait for a recorded real payout. No gateway.
        if (OFFLINE.equals(prepared.frozen.channel)) return receipt(prepared);
        var gateway = gateway(prepared);
        boolean dispatch =
                Boolean.TRUE.equals(
                        tx.execute(
                                s -> {
                                    lockOriginal(request);
                                    Row current = row(request.key());
                                    if ("SUCCESS".equals(current.state)
                                            || current.dispatched != null) return false;
                                    return jdbc.update(
                                                    "UPDATE stl_payment SET"
                                                        + " refund_dispatch_at=?,version=version+1"
                                                        + " WHERE payment_no=? AND"
                                                        + " refund_dispatch_at IS NULL",
                                                    System.currentTimeMillis(),
                                                    current.paymentNo)
                                            == 1;
                                }));
        if (dispatch) {
            try {
                gateway.refund(
                        new PayGateway.TxContext(
                                null,
                                prepared.frozen.originalProvider,
                                prepared.frozen.originalExternal,
                                request.originalPaidMinor(),
                                request.currency()),
                        request.refundMinor(),
                        prepared.external,
                        request.reason());
            } catch (RuntimeException uncertain) {
                return unknown(request.key(), "REFUND_DISPATCH_UNKNOWN");
            }
        }
        return query(request.key());
    }

    public Receipt query(Key key) {
        outside();
        key(key);
        Row prior = tx.execute(s -> row(key));
        if (prior == null)
            return new Receipt(null, null, "UNKNOWN", null, null, "REFUND_NOT_FOUND");
        if ("SUCCESS".equals(prior.state)) return receipt(prior);
        if (OFFLINE.equals(prior.frozen.channel))
            return new Receipt(prior.paymentNo, prior.frozen.request, "PENDING", null, null,
                    "OFFLINE_PAYOUT_PENDING");
        try {
            var proof = gateway(prior).queryRefundOutcome(prior.external);
            var r = prior.frozen.request;
            if (proof == null || proof.status() != RefundQueryResult.Status.SUCCEEDED)
                return unknown(key, "REFUND_FINALITY_UNPROVEN");
            if (!prior.external.equals(proof.outRefundNo())
                    || !prior.frozen.originalExternal.equals(proof.originalOutTradeNo())
                    || !prior.frozen.originalProvider.equals(proof.originalTradeNo())
                    || !r.merchantNo().equals(proof.receiverNo())
                    || proof.originalTotalMinor() == null
                    || r.originalPaidMinor() != proof.originalTotalMinor()
                    || r.refundMinor() != proof.amountMinor()
                    || !r.currency().equals(proof.currency())
                    || proof.providerNo() == null
                    || proof.providerNo().isBlank()
                    || proof.actualRefundAt() == null
                    || proof.actualRefundAt() <= 0
                    || proof.observedAt() < proof.actualRefundAt())
                return unknown(key, "REFUND_PROOF_MISMATCH");
            return tx.execute(
                    s -> {
                        lockOriginal(r);
                        Row current = row(key);
                        if ("SUCCESS".equals(current.state)) return receipt(current);
                        jdbc.update(
                                "UPDATE stl_payment SET"
                                    + " status='SUCCESS',platform_handoff_state='PENDING',trade_no=?,succeeded_at=?,refund_verified_provider_no=?,refund_verified_minor=?,refund_verified_at=?,refund_capacity_state='CONSUMED',refund_query_state='SUCCEEDED',platform_receipt_json=?,err_code=NULL,version=version+1"
                                    + " WHERE payment_no=?",
                                proof.providerNo(),
                                proof.actualRefundAt(),
                                proof.providerNo(),
                                proof.amountMinor(),
                                proof.observedAt(),
                                json.writeValueAsString(proof),
                                current.paymentNo);
                        return receipt(row(key));
                    });
        } catch (RuntimeException uncertain) {
            return unknown(key, "REFUND_QUERY_UNKNOWN");
        }
    }

    private static final String OFFLINE = "OFFLINE";

    private String scene(Request r) {
        var scenes =
                jdbc.queryForList(
                        "SELECT pay_scene FROM stl_payment WHERE tenant_no=? AND payment_no=? AND"
                                + " direction='PAY'",
                        String.class,
                        r.key().tenantNo(),
                        r.originalPaymentNo());
        return scenes.size() == 1 ? scenes.getFirst() : null;
    }

    private static String bankKey(String tenant, String reference) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            ("platform-bank-payout:v1\0" + tenant + "\0"
                                                            + reference.trim().toUpperCase(java.util.Locale.ROOT))
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Receipt confirmOfflinePayout(OfflinePayout p) {
        outside();
        require(p != null, "OFFLINE_PAYOUT_REQUIRED");
        key(p.key());
        text(p.operatorNo(), 64);
        text(p.payeeReference(), 128);
        text(p.bankReference(), 128);
        text(p.evidenceReference(), 1024);
        require(
                p.actualMinor() > 0
                        && p.currency() != null
                        && p.paidOutAt() > 0
                        && p.paidOutAt() % 1000 == 0
                        && p.confirmedAt() >= p.paidOutAt(),
                "OFFLINE_PAYOUT_INVALID");
        try {
            return tx.execute(
                    s -> {
                        Row current = row(p.key());
                        require(current != null, "REFUND_NOT_FOUND");
                        require(OFFLINE.equals(current.frozen.channel), "OFFLINE_PAYOUT_ROUTE_MISMATCH");
                        var r = current.frozen.request;
                        lockOriginal(r);
                        String stored =
                                jdbc.queryForObject(
                                        "SELECT platform_manual_proof FROM stl_payment WHERE payment_no=?",
                                        String.class,
                                        current.paymentNo);
                        if ("SUCCESS".equals(current.state)) {
                            require(
                                    stored != null
                                            && p.equals(json.readValue(stored, OfflinePayout.class)),
                                    "OFFLINE_PAYOUT_CONFLICT");
                            return receipt(current);
                        }
                        require(
                                r.refundMinor() == p.actualMinor() && r.currency().equals(p.currency()),
                                "OFFLINE_PAYOUT_AMOUNT_MISMATCH");
                        // Unmatched-cash returns are not commerce refunds: no commerce handoff.
                        String handoff =
                                PlatformCollectionService.UNMATCHED_BUSINESS.equals(scene(r))
                                        ? null
                                        : "PENDING";
                        jdbc.update(
                                "UPDATE stl_payment SET"
                                    + " status='SUCCESS',platform_handoff_state=?,trade_no=?,succeeded_at=?,refund_verified_provider_no=?,refund_verified_minor=?,refund_verified_at=?,refund_capacity_state='CONSUMED',refund_query_state='SUCCEEDED',platform_bank_ref_key=?,platform_manual_proof=?,err_code=NULL,version=version+1"
                                    + " WHERE payment_no=?",
                                handoff,
                                p.bankReference(),
                                p.paidOutAt(),
                                p.bankReference(),
                                p.actualMinor(),
                                p.paidOutAt(),
                                bankKey(p.key().tenantNo(), p.bankReference()),
                                json.writeValueAsString(p),
                                current.paymentNo);
                        return receipt(row(p.key()));
                    });
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            throw new IllegalArgumentException("OFFLINE_BANK_REFERENCE_CONFLICT", duplicate);
        }
    }

    private static Receipt receipt(Row row) {
        return new Receipt(
                row.paymentNo,
                row.frozen.request,
                row.state,
                row.provider,
                row.verifiedAt,
                row.error);
    }

    private Receipt unknown(Key key, String reason) {
        return tx.execute(
                s -> {
                    Row current = row(key);
                    if (current == null)
                        return new Receipt(null, null, "UNKNOWN", null, null, reason);
                    if ("SUCCESS".equals(current.state)) return receipt(current);
                    jdbc.update(
                            "UPDATE stl_payment SET err_code=?,version=version+1 WHERE"
                                + " payment_no=?",
                            reason,
                            current.paymentNo);
                    return new Receipt(
                            current.paymentNo,
                            current.frozen.request,
                            "UNKNOWN",
                            current.provider,
                            current.verifiedAt,
                            reason);
                });
    }

    public Optional<Key> refundKey(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        var rows =
                jdbc.queryForList(
                        "SELECT refund_request_json FROM stl_payment WHERE"
                            + " pay_scene='PLATFORM_MALL' AND direction='REFUND' AND (payment_no=?"
                            + " OR out_trade_no=?)",
                        String.class,
                        id,
                        id);
        if (rows.size() != 1) return Optional.empty();
        var frozen = json.readValue(rows.getFirst(), Frozen.class);
        return "PLATFORM_MALL".equals(frozen.business)
                ? Optional.of(frozen.request.key())
                : Optional.empty();
    }
}
