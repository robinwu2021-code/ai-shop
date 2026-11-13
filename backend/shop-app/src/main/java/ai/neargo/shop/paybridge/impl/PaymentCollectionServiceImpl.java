package ai.neargo.shop.paybridge.impl;

import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.paybridge.PaymentCollectionFact;
import ai.neargo.shop.paybridge.PaymentCollectionSecurity;
import ai.neargo.shop.paybridge.PaymentCollectionService;
import ai.neargo.shop.paybridge.PaymentCollectionWork;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;

import javax.sql.DataSource;

/** Receipt and the existing enabled platform notification work commit atomically. */
public final class PaymentCollectionServiceImpl implements PaymentCollectionService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final OutboxEventBus events;

    public PaymentCollectionServiceImpl(
            DataSource ds, PlatformTransactionManager manager, OutboxEventBus events) {
        jdbc = new JdbcTemplate(ds);
        transaction = new TransactionTemplate(manager);
        this.events = events;
    }

    @Override
    public Accepted receive(PaymentCollectionFact fact, String raw) {
        String hash = PaymentCollectionSecurity.hash(raw);
        return transaction.execute(
                status -> {
                    String receipt = BizKey.next("PCR");
                    jdbc.update(
                            "INSERT INTO payment_collection_receipt"
                                + " (environment,event_id,pay_order_no,payload_sha256,receipt_no,payload,created_at)"
                                + " VALUES ('SANDBOX',?,?,?,?,?,?) ON DUPLICATE KEY UPDATE"
                                + " receipt_no=receipt_no",
                            fact.eventId(),
                            fact.payOrderNo(),
                            hash,
                            receipt,
                            raw,
                            System.currentTimeMillis());
                    var rows =
                            jdbc.queryForList(
                                    "SELECT * FROM payment_collection_receipt WHERE"
                                            + " environment='SANDBOX' AND event_id=? FOR UPDATE",
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
                    var bindings =
                            jdbc.queryForList(
                                    "SELECT s.store_no,s.entity_no,e.market FROM"
                                        + " payment_collection_source b JOIN mch_store s ON"
                                        + " s.store_no=b.store_no AND s.entity_no=b.entity_no AND"
                                        + " s.deleted=0 JOIN mch_entity e ON"
                                        + " e.entity_no=s.entity_no AND e.deleted=0 WHERE"
                                        + " b.environment='SANDBOX' AND b.pay_app_id=? AND"
                                        + " b.source_type=? AND b.source_id=? AND"
                                        + " b.payment_store_id=? AND b.enabled=1",
                                    fact.payAppId(),
                                    fact.sourceType(),
                                    fact.sourceId(),
                                    fact.storeId());
                    if (bindings.size() != 1
                            || !Objects.equals(bindings.getFirst().get("market"), fact.market()))
                        throw new ResponseStatusException(
                                HttpStatus.FORBIDDEN, "untrusted source/store mapping");
                    var binding = bindings.getFirst();
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
}
