package ai.neargo.shop.paybridge.impl;

import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.paybridge.PaymentCollectionFact;
import ai.neargo.shop.paybridge.PaymentCollectionProperties;
import ai.neargo.shop.paybridge.PaymentCollectionSecurity;
import ai.neargo.shop.paybridge.PaymentCollectionService;
import ai.neargo.shop.paybridge.PaymentCollectionWork;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Objects;

import javax.sql.DataSource;

/** Receipt and the existing enabled platform notification work commit atomically. */
public final class PaymentCollectionServiceImpl implements PaymentCollectionService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final OutboxEventBus events;
    private final PaymentCollectionProperties properties;

    public PaymentCollectionServiceImpl(
            DataSource ds, PlatformTransactionManager manager, OutboxEventBus events,
            PaymentCollectionProperties properties) {
        jdbc = new JdbcTemplate(ds);
        transaction = new TransactionTemplate(manager);
        this.events = events;
        this.properties = properties;
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
        var bindings =
                jdbc.queryForList(
                        "SELECT s.store_no,s.entity_no,e.market FROM"
                                + " payment_collection_source b JOIN mch_store s ON"
                                + " s.store_no=b.store_no AND s.entity_no=b.entity_no AND"
                                + " s.deleted=0 JOIN mch_entity e ON"
                                + " e.entity_no=s.entity_no AND e.deleted=0 WHERE"
                                + " b.environment=? AND b.pay_app_id=? AND"
                                + " b.source_type=? AND b.source_id=? AND"
                                + " b.payment_store_id=? AND b.enabled=1",
                        environment,
                        fact.payAppId(),
                        fact.sourceType(),
                        fact.sourceId(),
                        fact.storeId());
        if (bindings.isEmpty() && fact.storeNo() != null)
            bindings =
                    jdbc.queryForList(
                            "SELECT s.store_no,s.entity_no,e.market FROM mch_store s JOIN mch_entity e"
                                    + " ON e.entity_no=s.entity_no AND e.deleted=0"
                                    + " WHERE s.store_no=? AND s.deleted=0",
                            fact.storeNo());
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
        var stores = jdbc.queryForList("""
                SELECT s.store_no, s.entity_no, s.tenant_no, e.market
                FROM mch_store s JOIN mch_entity e
                  ON e.tenant_no=s.tenant_no AND e.entity_no=s.entity_no AND e.deleted=0
                WHERE s.tenant_no=? AND s.id=? AND s.deleted=0
                """, fact.tenantNo(), fact.storeId());
        if (stores.size() != 1) throw untrustedStore();
        var store = stores.getFirst();
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

    private static ResponseStatusException untrustedStore() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "untrusted source/store mapping");
    }
}
