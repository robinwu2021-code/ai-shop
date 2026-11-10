package ai.neargo.shop.pay.service.impl;

import ai.neargo.shop.pay.channel.*;
import ai.neargo.shop.pay.service.PlatformCollectionService;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

import javax.sql.DataSource;

/** Short local transactions commit before every gateway call. All cash remains in stl_payment. */
@Service
@ConditionalOnProperty(
        prefix = "commerce",
        name = {"enabled", "mall.payment.enabled"},
        havingValue = "true")
public class PlatformCollectionServiceImpl implements PlatformCollectionService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    /**
     * 统一项目线已退役 PayGatewayRouter（W2 S5）。平台代收只认声明了 platformCollectionCapability 的网关；
     * 统一线的渠道 handler 都还没声明它，所以线上路由一律 ROUTE_UNAVAILABLE，只有线下转账可用（2026-11-04 合入决定，后续再移植）。
     */
    private final List<PayGateway> gateways;
    private final JsonMapper json = JsonMapper.builder().build();
    private final PlatformOfflineLedger offline;

    public PlatformCollectionServiceImpl(
            @Qualifier("payDataSource") DataSource ds,
            @Qualifier("payTxManager") PlatformTransactionManager manager,
            List<PayGateway> gateways) {
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(manager);
        this.gateways = gateways;
        this.offline = new PlatformOfflineLedger(jdbc, tx, json, this);
    }

    record Frozen(String business, Request request) {}

    private record Row(Receipt receipt, String external, Long dispatched) {}

    private static void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Platform payment IO requires no caller transaction");
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }

    private static void text(String value, int max) {
        require(
                value != null && !value.isBlank() && value.length() <= max,
                "PAYMENT_IDENTITY_REQUIRED");
    }

    private static void key(Key k) {
        require(k != null, "PAYMENT_KEY_REQUIRED");
        text(k.tenantNo(), 32);
        text(k.orderNo(), 64);
        text(k.attemptNo(), 64);
    }

    private static void validate(Request r) {
        require(r != null, "PAYMENT_INTENT_REQUIRED");
        key(r.key());
        text(r.buyerNo(), 64);
        text(r.merchantNo(), 64);
        text(r.channel(), 16);
        text(r.method(), 16);
        text(r.currency(), 8);
        require(
                r.amountMinor() > 0 && r.preparedAt() > 0 && r.expiresAt() > r.preparedAt(),
                "PAYMENT_INTENT_INVALID");
        if ("OFFLINE".equals(r.channel())) {
            require("BANK_TRANSFER".equals(r.method()) && r.offline()!=null, "OFFLINE_INTENT_INVALID");
            return;
        }
        require(r.offline()==null, "PAYMENT_ROUTE_MISMATCH");
        require(r.payer() != null, "PAYER_BINDING_UNPROVEN");
        text(r.payer().realm(), 32);
        text(r.payer().kind(), 16);
        text(r.payer().rawNo(), 64);
        text(r.payer().appId(), 64);
        text(r.payer().providerIdentity(), 128);
    }

    private static String digest(String domain, Key k) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            (domain
                                                            + "\0"
                                                            + k.tenantNo()
                                                            + "\0"
                                                            + k.orderNo()
                                                            + "\0"
                                                            + k.attemptNo())
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String paymentNo(Key k) {
        return "MP" + digest("platform-payment:v1", k).substring(0, 60);
    }

    private static String external(Key k) {
        return "M" + digest("platform-payment-provider:v1", k).substring(0, 31);
    }

    private Receipt unknown(Key k, Request r, String error) {
        return new Receipt(k, null, r, "UNKNOWN", null, null, Map.of(), null, error);
    }

    private Row lock(Key k) {
        var rows =
                jdbc.query(
                        "SELECT"
                            + " payment_no,payment_request_json,status,trade_no,succeeded_at,platform_dispatch_at,platform_close_fence,platform_checkout_json,err_code,out_trade_no,platform_receipt_json,platform_manual_proof"
                            + " FROM stl_payment WHERE tenant_no=? AND payment_no=? AND pay_scene IN (?, 'MALL_UNMATCHED')"
                            + " AND direction='PAY' FOR UPDATE",
                        (rs, n) -> {
                            Frozen frozen =
                                    json.readValue(
                                            rs.getString("payment_request_json"), Frozen.class);
                            require(
                                    (BUSINESS.equals(frozen.business) || PlatformOfflineLedger.UNMATCHED.equals(frozen.business))
                                            && k.equals(frozen.request.key()),
                                    "PAYMENT_SCOPE_MISMATCH");
                            String checkout = rs.getString("platform_checkout_json");
                            Map<String, String> params =
                                    checkout == null
                                            ? Map.of()
                                            : json.readValue(
                                                    checkout,
                                                    new TypeReference<Map<String, String>>() {});
                            return new Row(
                                    new Receipt(
                                            k,
                                            rs.getString("payment_no"),
                                            frozen.request,
                                            rs.getString("status"),
                                            rs.getString("trade_no"),
                                            rs.getObject("succeeded_at", Long.class),
                                            params,
                                            rs.getString("platform_close_fence"),
                                            rs.getString("err_code"),
                                            rs.getString("platform_manual_proof") != null ? 1000L : rs.getString("platform_receipt_json") == null
                                                    ? 1L
                                                    : json.readValue(
                                                                    rs.getString(
                                                                            "platform_receipt_json"),
                                                                    PayGateway.QueryResult.class)
                                                            .paidAtPrecisionMs()),
                                    rs.getString("out_trade_no"),
                                    rs.getObject("platform_dispatch_at", Long.class));
                        },
                        k.tenantNo(),
                        paymentNo(k),
                        BUSINESS);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Row prepare(Request r) {
        validate(r);
        jdbc.update(
                "INSERT INTO"
                    + " stl_payment(payment_no,direction,order_no,user_no,entity_no,pay_channel,pay_scene,pay_method,amount_minor,currency,status,out_trade_no,payment_request_json,tenant_no,created_at,updated_at,created_by,updated_by)"
                    + " VALUES"
                    + " (?,'PAY',?,?,?,?,?,?,?,?,'PENDING',?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),?,?)"
                    + " ON DUPLICATE KEY UPDATE id=id",
                paymentNo(r.key()),
                r.key().orderNo(),
                r.buyerNo(),
                r.merchantNo(),
                r.channel(),
                BUSINESS,
                r.method(),
                r.amountMinor(),
                r.currency(),
                external(r.key()),
                json.writeValueAsString(new Frozen(BUSINESS, r)),
                r.key().tenantNo(),
                r.buyerNo(),
                r.buyerNo());
        Row row = lock(r.key());
        require(row != null && r.equals(row.receipt.request()), "PAYMENT_INTENT_CONFLICT");
        return row;
    }

    private PayGateway gateway(Request r) {
        var g = gatewayOf(r.channel());
        var capability =
                g.platformCollectionCapability()
                        .orElseThrow(
                                () -> new IllegalArgumentException("PAYMENT_ROUTE_UNAVAILABLE"));
        require(
                capability.merchantNo().equals(r.merchantNo())
                        && capability.method().equals(r.method())
                        && capability.currency().equals(r.currency())
                        && r.amountMinor() <= capability.maximumMinor()
                        && capability.appId().equals(r.payer().appId()),
                "PAYMENT_ROUTE_MISMATCH");
        return g;
    }

    public Receipt create(Request request) {
        outside();
        Row row = tx.execute(s -> {
            Row original = lock(request.key());
            if ("OFFLINE".equals(request.channel()) && (original == null || original.dispatched == null))
                offline.verifyIssuance(request);
            Row prepared = prepare(request);
            if ("OFFLINE".equals(request.channel()) && !terminal(prepared) && prepared.dispatched == null)
                jdbc.update("UPDATE stl_payment SET platform_dispatch_at=?,version=version+1 WHERE payment_no=?", System.currentTimeMillis(), prepared.receipt.paymentNo());
            return lock(request.key());
        });
        if ("OFFLINE".equals(request.channel())) return row.receipt;
        if (terminal(row)) return row.receipt;
        PayGateway gateway = gateway(request);
        boolean send =
                Boolean.TRUE.equals(
                        tx.execute(
                                s -> {
                                    Row current = lock(request.key());
                                    if (terminal(current) || current.dispatched != null)
                                        return false;
                                    return jdbc.update(
                                                    "UPDATE stl_payment SET"
                                                        + " platform_dispatch_at=?,version=version+1"
                                                        + " WHERE payment_no=? AND"
                                                        + " platform_dispatch_at IS NULL AND"
                                                        + " platform_close_fence IS NULL AND"
                                                        + " status='PENDING'",
                                                    System.currentTimeMillis(),
                                                    current.receipt.paymentNo())
                                            == 1;
                                }));
        if (!send) return query(request.key());
        try {
            var result =
                    gateway.prepay(
                            new PayGateway.PrepayCommand(
                                    row.external,
                                    request.amountMinor(),
                                    request.currency(),
                                    "Platform mall order " + request.key().orderNo(),
                                    request.method(),
                                    null,
                                    request.payer().providerIdentity()));
            if (result == null || !result.success())
                return uncertainty(request.key(), "PAYMENT_DISPATCH_UNKNOWN");
            Set<String> safe =
                    Set.of("appId", "timeStamp", "nonceStr", "package", "signType", "paySign");
            require(
                    result.params() != null && safe.containsAll(result.params().keySet()),
                    "UNSAFE_CHECKOUT_PARAMETERS");
            tx.executeWithoutResult(
                    s -> {
                        Row current = lock(request.key());
                        if (!terminal(current))
                            jdbc.update(
                                    "UPDATE stl_payment SET"
                                        + " platform_checkout_json=?,version=version+1 WHERE"
                                        + " payment_no=?",
                                    json.writeValueAsString(result.params()),
                                    current.receipt.paymentNo());
                    });
            return tx.execute(s -> lock(request.key()).receipt);
        } catch (RuntimeException uncertain) {
            return uncertainty(request.key(), "PAYMENT_DISPATCH_UNKNOWN");
        }
    }

    private static boolean terminal(Row row) {
        return row != null
                && (row.receipt.state().equals("SUCCESS") || row.receipt.state().equals("CLOSED"));
    }

    public Receipt query(Key key) {
        outside();
        key(key);
        Row row = tx.execute(s -> lock(key));
        if (row == null) return unknown(key, null, "PAYMENT_NOT_FOUND");
        if (row.receipt.request().offline()!=null) return row.receipt;
        if ("SUCCESS".equals(row.receipt.state())
                || "CLOSED".equals(row.receipt.state()) && row.dispatched == null)
            return row.receipt;
        try {
            return observe(row, gateway(row.receipt.request()).query(row.external));
        } catch (RuntimeException uncertain) {
            return uncertainty(key, "PAYMENT_QUERY_UNKNOWN");
        }
    }

    public Receipt close(Key key, Request original) {
        outside();
        key(key);
        if (original != null) require(key.equals(original.key()), "PAYMENT_INTENT_CONFLICT");
        Row before =
                tx.execute(
                        s -> {
                            Row row = original == null ? lock(key) : prepare(original);
                            if (row == null || terminal(row)) return row;
                            if (row.dispatched == null) {
                                jdbc.update(
                                        "UPDATE stl_payment SET"
                                            + " status='CLOSED',platform_close_fence=?,closed_at=?,version=version+1"
                                            + " WHERE payment_no=?",
                                        "LOCAL_BEFORE_DISPATCH:" + row.receipt.paymentNo(),
                                        System.currentTimeMillis(),
                                        row.receipt.paymentNo());
                                return lock(key);
                            }
                            return row;
                        });
        if (before == null) return unknown(key, original, "PAYMENT_NOT_FOUND");
        if (terminal(before)) return before.receipt;
        if (before.receipt.request().offline()!=null) return unknown(key,before.receipt.request(),"OFFLINE_RECONCILIATION_REQUIRED");
        try {
            return observe(before, gateway(before.receipt.request()).close(before.external));
        } catch (RuntimeException uncertain) {
            return uncertainty(key, "PAYMENT_CLOSE_UNKNOWN");
        }
    }

    private Receipt observe(Row expected, PayGateway.QueryResult proof) {
        Request request = expected.receipt.request();
        if (proof == null
                || !proof.ok()
                || !proof.found()
                || !expected.external.equals(proof.outTradeNo())
                || !request.merchantNo().equals(proof.receiverNo()))
            return uncertainty(request.key(), "PAYMENT_PROOF_UNPROVEN");
        if (proof.paid()
                && (!"SUCCESS".equals(proof.providerState())
                        || proof.amountMinor() != request.amountMinor()
                        || !request.currency().equals(proof.currency())
                        || proof.actualPaidAt() == null
                        || Math.addExact(proof.actualPaidAt(), proof.paidAtPrecisionMs() - 1)
                                < request.preparedAt()
                        || proof.tradeNo() == null
                        || proof.tradeNo().isBlank()))
            return uncertainty(request.key(), "PAYMENT_PROOF_MISMATCH");
        return tx.execute(
                s -> {
                    Row current = lock(request.key());
                    if (current.receipt.state().equals("SUCCESS")) return current.receipt;
                    if (proof.paid())
                        jdbc.update(
                                "UPDATE stl_payment SET"
                                    + " status='SUCCESS',platform_handoff_state='PENDING',trade_no=?,succeeded_at=?,platform_receipt_json=?,err_code=NULL,version=version+1"
                                    + " WHERE payment_no=?",
                                proof.tradeNo(),
                                proof.actualPaidAt(),
                                json.writeValueAsString(proof),
                                current.receipt.paymentNo());
                    else if ("CLOSED".equals(proof.providerState()))
                        jdbc.update(
                                "UPDATE stl_payment SET"
                                    + " status='CLOSED',platform_close_fence=?,closed_at=?,platform_receipt_json=?,version=version+1"
                                    + " WHERE payment_no=?",
                                "PROVIDER_CLOSED:" + current.external,
                                System.currentTimeMillis(),
                                json.writeValueAsString(proof),
                                current.receipt.paymentNo());
                    return lock(request.key()).receipt;
                });
    }

    private Receipt uncertainty(Key key, String reason) {
        return tx.execute(
                s -> {
                    Row row = lock(key);
                    if (row == null) return unknown(key, null, reason);
                    if (terminal(row)) return row.receipt;
                    jdbc.update(
                            "UPDATE stl_payment SET err_code=?,version=version+1 WHERE"
                                + " payment_no=?",
                            reason,
                            row.receipt.paymentNo());
                    var r = row.receipt;
                    return new Receipt(
                            key,
                            r.paymentNo(),
                            r.request(),
                            "UNKNOWN",
                            r.providerNo(),
                            r.paidAt(),
                            r.checkoutParameters(),
                            null,
                            reason);
                });
    }

    Receipt localReceipt(Key key) { var row=lock(key); return row==null?null:row.receipt; }

    public OfflineInstructions configureOffline(OfflineProfile profile) { outside(); return offline.configure(profile); }
    public Optional<OfflineInstructions> offlineInstructions(String tenant, String profile, String currency) {
        outside(); return offline.current(tenant,profile,currency);
    }
    public ManualReceipt confirmOffline(OfflineProof proof) { outside(); return offline.confirm(proof); }
    public java.util.List<CashReceipt> receipts(String tenant, String orderNo) { outside(); return tx.execute(s -> offline.receipts(tenant,orderNo)); }
    public Receipt reconcileOffline(OfflineVoid decision) { outside(); return offline.reconcile(decision); }

    public Optional<Key> paymentKey(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        var requests =
                jdbc.queryForList(
                        "SELECT payment_request_json FROM stl_payment WHERE pay_scene=? AND"
                            + " direction='PAY' AND (payment_no=? OR out_trade_no=?)",
                        String.class,
                        BUSINESS,
                        id,
                        id);
        if (requests.size() != 1) return Optional.empty();
        var frozen = json.readValue(requests.getFirst(), Frozen.class);
        return BUSINESS.equals(frozen.business)
                ? Optional.of(frozen.request.key())
                : Optional.empty();
    }

    private PayGateway gatewayOf(String channel) {
        return gateways.stream()
                .filter(g -> g.payChannel().equals(channel))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("PAYMENT_ROUTE_UNAVAILABLE"));
    }
}
