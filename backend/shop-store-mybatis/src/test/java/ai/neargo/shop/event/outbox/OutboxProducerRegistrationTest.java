package ai.neargo.shop.event.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.event.DomainEvent;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OutboxProducerRegistrationTest extends OutboxTestFixture {
    record Event(String aggregateId,String eventType) implements DomainEvent {
        @Override public String aggregateType() { return "FOOD_BATCH"; }
    }
    @Test void producerPersistsRealDeliveryMetadataWithoutAnExecutableHandler() {
        var producer=new UnifiedOutboxFacade(new ObjectMapper());
        producer.registerProducer(store,Map.of("ACCEPTED",Map.of("owner-accepted-v1",true)));
        tx.executeWithoutResult(status -> producer.publisher("PLATFORM").publish(
                new Event("B1","ACCEPTED"),new PublishOptions("FOOD_BATCH:B1",1)));
        var event=jdbc.queryForMap("SELECT * FROM sys_outbox");
        var delivery=jdbc.queryForMap("SELECT * FROM sys_outbox_delivery");
        assertThat(event.get("status")).isEqualTo("PENDING");
        assertThat(event.get("source_system")).isEqualTo("PLATFORM");
        assertThat(event.get("routing_mode")).isEqualTo("DELIVERY");
        assertThat(((Number)event.get("stream_sequence")).longValue()).isEqualTo(1);
        assertThat((String)event.get("payload_hash")).hasSize(64);
        assertThat(delivery.get("handler_id")).isEqualTo("owner-accepted-v1");
        assertThat(((Number)delivery.get("external_workflow")).intValue()).isEqualTo(1);
        assertThat(delivery.get("status")).isEqualTo("PENDING");
        assertThat(producer.handler("PLATFORM","owner-accepted-v1")).isNull();
        assertThatThrownBy(() -> producer.register(store,List.of())).isInstanceOf(IllegalStateException.class);
    }
    @Test void producerRollsBackWithBusinessAndOnlyExplicitFactsArchive() {
        var producer=new UnifiedOutboxFacade(new ObjectMapper());
        producer.registerProducer(store,Map.of("ACCEPTED",Map.of("owner-accepted-v1",true)));
        producer.setArchiveOnly(Set.of("PLATFORM:FACT"));
        tx.executeWithoutResult(status -> {
            producer.publisher("PLATFORM").publish(new Event("B1","ACCEPTED"),new PublishOptions("B1",1));
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox_delivery",Long.class)).isZero();
        tx.executeWithoutResult(status -> producer.publisher("PLATFORM").publish(
                new Event("B2","FACT"),new PublishOptions("B2",1)));
        tx.executeWithoutResult(status -> producer.publisher("PLATFORM").publish(
                new Event("B3","UNKNOWN_TYPE"),new PublishOptions("B3",1)));
        assertThat(jdbc.queryForObject("SELECT status FROM sys_outbox WHERE aggregate_id='B2'",String.class))
                .isEqualTo("ARCHIVED");
        assertThat(jdbc.queryForObject("SELECT last_error FROM sys_outbox WHERE aggregate_id='B3'",String.class))
                .isEqualTo("NO_CONSUMER");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox_delivery",Long.class)).isZero();
    }
}
