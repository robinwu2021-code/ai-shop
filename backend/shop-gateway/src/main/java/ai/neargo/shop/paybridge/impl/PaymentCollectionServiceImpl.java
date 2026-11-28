package ai.neargo.shop.paybridge.impl;

import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.paybridge.PaymentCollectionFact;
import ai.neargo.shop.paybridge.PaymentCollectionProperties;
import ai.neargo.shop.paybridge.PaymentCollectionSecurity;
import ai.neargo.shop.paybridge.PaymentCollectionService;
import ai.neargo.shop.paybridge.PaymentCollectionWork;
import ai.neargo.shop.spi.pay.PaidCollectionPort.PaidCollection;
import ai.neargo.shop.trade.service.StoreOrderSettlementService;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Objects;

import javax.sql.DataSource;

/**
 * Receipt and the existing enabled platform notification work commit atomically.
 *
 * <p>收款码（{@code BIZ_COLLECT}）挂门店零售单与回执同库：同一事务里直接调，不经发件箱。
 * 挂不上（不是本店零售单、币别不符、已挂过）只返回 false，不影响回执。
 */
public final class PaymentCollectionServiceImpl implements PaymentCollectionService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final OutboxEventBus events;
    private final PaymentCollectionProperties properties;
    private final StoreOrderSettlementService settlements;
    /** 门店与主体在平台基础库：核验经商户域端口，口径同原 SQL（门店、主体未删；按 id 时连租户） */
    private final ai.neargo.shop.spi.user.PaymentCollectionFactsPort facts;

    public PaymentCollectionServiceImpl(
            DataSource ds, PlatformTransactionManager manager, OutboxEventBus events,
            PaymentCollectionProperties properties, StoreOrderSettlementService settlements,
            ai.neargo.shop.spi.user.PaymentCollectionFactsPort facts) {
        jdbc = new JdbcTemplate(ds);
        transaction = new TransactionTemplate(manager);
        this.events = events;
        this.properties = properties;
        this.settlements = settlements;
        this.facts = facts;
    }

    @Override
    public Accepted receive(String environment, PaymentCollectionFact fact, String raw) {
        String hash = PaymentCollectionSecurity.hash(raw);
        return transaction.execute(
                status -> {
                    String receipt = BizKey.next("PCR");
                    jdbc.update(
                            "INSERT INTO payment_collection_receipt"
                                + " (environment,event_id,pay_order_no,payload_sha256,receipt_no,payload,created_at)"
                                + " VALUES (?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE"
                                + " receipt_no=receipt_no",
                            environment,
                            fact.eventId(),
                            fact.payOrderNo(),
                            hash,
                            receipt,
                            raw,
                            System.currentTimeMillis());
                    var rows =
                            jdbc.queryForList(
                                    "SELECT * FROM payment_collection_receipt WHERE"
                                            + " environment=? AND event_id=? FOR UPDATE",
                                    environment,
                                    fact.eventId());
                    if (rows.size() != 1
                            || !Objects.equals(rows.getFirst().get("payload_sha256"), hash))
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT, "immutable payment facts conflict");
                    var row = rows.getFirst();
                    boolean fresh = receipt.equals(row.get("receipt_no"));
                    if (!fresh)
                        return new Accepted(
                                fact.eventId(), hash, "DUPLICATE", (String) row.get("receipt_no"));
                    if (fact.amountMinor() != fact.paidMinor()
                            || ("CHECKOUT_QR".equals(fact.bizType())
                                    && !"CHECKOUT".equals(fact.sourceType())))
                        throw new ResponseStatusException(
                                HttpStatus.UNPROCESSABLE_ENTITY,
                                "not a normal merchant collection");
                    var binding = fact.schemaVersion() == 2
                            ? canonicalStore(environment, fact) : legacyStore(environment, fact);
                    jdbc.update(
                            "UPDATE payment_collection_receipt SET store_no=?,entity_no=? WHERE"
                                    + " receipt_no=?",
                            binding.get("store_no"),
                            binding.get("entity_no"),
                            receipt);
                    if ("BIZ_COLLECT".equals(fact.bizType())) {
                        settlements.attachCollection(new PaidCollection(fact.payOrderNo(),
                                (String) binding.get("store_no"), fact.bizNo(), fact.currency(),
                                fact.paidMinor(), fact.payChannel()));
                    }
                    events.publish(new PaymentCollectionWork(receipt));
                    return new Accepted(fact.eventId(), hash, "APPLIED", receipt);
                });
    }

    /**
     * Which store a verified result belongs to. An explicit source binding wins and must agree with the
     * store near-pay resolved; without one, the store number in the signed snapshot is trusted when the
     * store exists and its entity's market matches (TDD-沙箱收款消息与SSE, 2026-11-16 增量).
     */
    private Map<String, Object> legacyStore(String environment, PaymentCollectionFact fact) {
        // 来源绑定在本库；它指向的门店须仍在、主体须仍在且与绑定一致（门店与主体经商户域端口）
        var bindings = new java.util.ArrayList<Map<String, Object>>();
        for (var b : jdbc.queryForList(
                "SELECT store_no,entity_no FROM payment_collection_source WHERE"
                        + " environment=? AND pay_app_id=? AND source_type=? AND source_id=? AND"
                        + " payment_store_id=? AND enabled=1",
                environment, fact.payAppId(), fact.sourceType(), fact.sourceId(), fact.storeId())) {
            facts.storeByNo((String) b.get("store_no"))
                    .filter(s -> s.entityNo().equals(b.get("entity_no")))
                    .ifPresent(s -> bindings.add(storeRow(s)));
        }
        if (bindings.isEmpty() && fact.storeNo() != null)
            facts.storeByNo(fact.storeNo()).ifPresent(s -> bindings.add(storeRow(s)));
        if (bindings.size() != 1
                || !Objects.equals(bindings.getFirst().get("market"), fact.market())
                || fact.storeNo() != null
                        && !fact.storeNo().equals(bindings.getFirst().get("store_no")))
            throw untrustedStore();
        return bindings.getFirst();
    }

    private Map<String, Object> canonicalStore(String environment, PaymentCollectionFact fact) {
        if (!Objects.equals(fact.payAppId(), fact.sourceId())
                || fact.tenantNo() == null || fact.entityNo() == null
                || !canonicalSourceAllowed(environment, fact)) {
            throw untrustedStore();
        }
        var found = facts.storeById(fact.tenantNo(), fact.storeId());
        if (found.isEmpty()) throw untrustedStore();
        var store = storeRow(found.get());
        if (!fact.tenantNo().equals(store.get("tenant_no")) || !fact.entityNo().equals(store.get("entity_no"))
                || !fact.market().equals(store.get("market"))
                || fact.storeNo() != null && !fact.storeNo().equals(store.get("store_no"))) {
            throw untrustedStore();
        }

        var bindings = jdbc.query("""
                SELECT store_no, entity_no, enabled FROM payment_collection_source
                WHERE environment=? AND pay_app_id=? AND source_type=? AND source_id=? AND payment_store_id=?
                """, (rs, index) -> rs.getBoolean("enabled")
                        && Objects.equals(store.get("store_no"), rs.getString("store_no"))
                        && Objects.equals(store.get("entity_no"), rs.getString("entity_no")),
                environment, fact.payAppId(), fact.sourceType(), fact.sourceId(), fact.storeId());
        if (bindings.contains(Boolean.FALSE)) throw untrustedStore();
        return store;
    }

    private boolean canonicalSourceAllowed(String environment, PaymentCollectionFact fact) {
        String source = environment + ":" + fact.tenantNo() + ":" + fact.payAppId();
        return ("CHECKOUT".equals(fact.sourceType()) && "CHECKOUT_QR".equals(fact.bizType())
                && properties.getCanonicalCheckoutSources().contains(source))
                || ("BIZ".equals(fact.sourceType()) && "BIZ_COLLECT".equals(fact.bizType())
                && properties.getCanonicalBizCollectSources().contains(source));
    }

    private static Map<String, Object> storeRow(ai.neargo.shop.spi.user.PaymentCollectionFactsPort.StoreFact s) {
        var row = new java.util.HashMap<String, Object>();
        row.put("store_no", s.storeNo());
        row.put("entity_no", s.entityNo());
        row.put("tenant_no", s.tenantNo());
        row.put("market", s.market());
        return row;
    }

    private static ResponseStatusException untrustedStore() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "untrusted source/store mapping");
    }
}
