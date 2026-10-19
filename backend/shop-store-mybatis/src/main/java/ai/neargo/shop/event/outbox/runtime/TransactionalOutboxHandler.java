package ai.neargo.shop.event.outbox.runtime;

import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.event.outbox.DeliveryResult;
import ai.neargo.shop.event.outbox.OutboxEnvelope;
import ai.neargo.shop.event.outbox.OutboxHandler;
import ai.neargo.shop.event.outbox.RetryableOutboxException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;

/** Inbox and local effects share the target transaction; remote workflows never hold it open. */
public final class TransactionalOutboxHandler implements OutboxHandler {
    private final OutboxConsumer consumer;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final String inbox;

    public TransactionalOutboxHandler(OutboxConsumer consumer, DataSource ds,
                                      PlatformTransactionManager manager, String inbox) {
        if (!"sys_event_consumed".equals(inbox) && !"inv_event_consumed".equals(inbox)) {
            throw new IllegalArgumentException("Unsupported inbox");
        }
        OutboxTransactions.register(manager);
        this.consumer = consumer;
        this.jdbc = new JdbcTemplate(ds);
        this.inbox = inbox;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @Override
    public String handlerId() { return consumer.handlerId(); }
    @Override
    public boolean supports(String type) { return consumer.supports(type); }
    @Override
    public boolean externalWorkflow() { return consumer.externalWorkflow(); }

    @Override
    public DeliveryResult handle(OutboxEnvelope event) {
        try {
            if (externalWorkflow()) {
                return consumer.deliver(convert(event));
            }
            return transaction.execute(status -> {
                try {
                    jdbc.update("INSERT INTO " + inbox + " (event_no,handler,consumed_at) VALUES (?,?,?)",
                            event.eventNo(), handlerId(), Timestamp.from(Instant.now()));
                } catch (DuplicateKeyException alreadyConsumed) {
                    return DeliveryResult.sent();
                }
                DeliveryResult result = consumer.deliver(convert(event));
                if (result.kind() != DeliveryResult.Kind.SENT) { status.setRollbackOnly(); }
                return result;
            });
        } catch (RetryableOutboxException ex) {
            return DeliveryResult.retry("CHANNEL_RETRYABLE", ex.getClass().getSimpleName());
        } catch (RuntimeException ex) {
            // Store only the exception class; SQL/HTTP messages may contain personal data or credentials.
            return externalWorkflow() ? DeliveryResult.unknown(ex.getClass().getSimpleName())
                    : DeliveryResult.retry("HANDLER_FAILED", ex.getClass().getSimpleName());
        }
    }

    private SysOutbox convert(OutboxEnvelope e) {
        SysOutbox row = new SysOutbox();
        row.setEventNo(e.eventNo()); row.setEventType(e.eventType()); row.setPayload(e.payload());
        row.setAggregateType(e.aggregateType()); row.setAggregateId(e.aggregateId());
        row.setSourceSystem(e.sourceSystem()); row.setSourceEventNo(e.sourceEventNo());
        row.setSchemaVersion(e.schemaVersion()); row.setStreamKey(e.streamKey());
        row.setStreamSequence(e.streamSequence()); row.setStatus(SysOutbox.PENDING);
        return row;
    }
}
