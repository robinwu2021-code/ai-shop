package ai.neargo.shop.scenario;

import ai.neargo.shop.event.DomainEvent;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.invbridge.InvMirrorEvent;
import ai.neargo.shop.spi.trade.OrderEvents;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "shop.outbox.sources.platform.mode=unified", "shop.outbox.sources.inventory.mode=unified",
        "shop.outbox.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:shop_outbox;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "shop.inventory.datasource.url=jdbc:h2:mem:inv_outbox_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class OutboxUnifiedFlowTest {
    @Autowired UnifiedOutboxFacade facade;
    @Autowired OutboxEventBus events;
    @Autowired @Qualifier("transactionManager") PlatformTransactionManager platformTx;
    @Autowired @Qualifier("invTransactionManager") PlatformTransactionManager inventoryTx;
    @Autowired @Qualifier("dataSource") DataSource platform;

    @Test
    void actualPlatformPublisherJoinsBusinessRollback() {
        String order="OB-"+UUID.randomUUID();
        new TransactionTemplate(platformTx).executeWithoutResult(s -> {
            events.publish(new OrderEvents.OrderPaid(order,"U1",1,"WECHAT"));
            s.setRollbackOnly();
        });
        assertThat(new JdbcTemplate(platform).queryForObject(
                "SELECT COUNT(*) FROM sys_outbox WHERE aggregate_id=?",Long.class,order)).isZero();
    }
    private record Posted(String ownerId,String docNo,String docKind) implements DomainEvent {
        public String aggregateType() { return "INVENTORY"; }
        public String aggregateId() { return ownerId; }
        public String eventType() { return "DocumentPosted"; }
    }
    @Test
    void inventoryAndPlatformUseDifferentStoresAndBridgePreservesSourceIdentity() {
        new TransactionTemplate(inventoryTx).executeWithoutResult(s ->
                facade.publish("INVENTORY",new Posted("OWNER-OB", "DOC-OB", "IN")));
        var inventory=facade.store("INVENTORY");
        var claim=inventory.tryClaim(inventory.findDue(Instant.now(),1).getFirst(),"claim",
                Instant.now(),Duration.ofSeconds(120)).orElseThrow();
        var bridge=facade.handler("INVENTORY","inventory-to-platform");
        bridge.handle(claim.event());
        bridge.handle(claim.event());
        assertThat(new JdbcTemplate(platform).queryForObject("SELECT COUNT(*) FROM sys_outbox"
                + " WHERE source_system='INVENTORY' AND source_event_no=?",Long.class,
                claim.event().sourceEventNo())).isEqualTo(1);
    }
    @Test
    void legacyInventoryIngressPreservesVersionOnePayloadAndOriginalIdentity() {
        String original = "LEGACY-"+UUID.randomUUID();
        String payload = "{\"docNo\":\"DOC-V1\",\"ownerId\":\"OWNER\"}";
        Instant occurred = Instant.parse("2026-10-19T00:00:00Z");
        var first = facade.acceptInventory(original,"OWNER","DocumentPosted",payload,occurred);
        var again = facade.acceptInventory(original,"OWNER","DocumentPosted",payload,occurred);
        assertThat(again.eventNo()).isEqualTo(first.eventNo());
        var row = new JdbcTemplate(platform).queryForMap("SELECT schema_version,stream_key,payload FROM sys_outbox WHERE event_no=?",first.eventNo());
        assertThat(((Number)row.get("schema_version")).intValue()).isEqualTo(1);
        assertThat(row.get("stream_key")).isEqualTo("INVENTORY:DOCUMENT:DOC-V1");
        assertThat(new tools.jackson.databind.ObjectMapper().readTree((String)row.get("payload")).path("payload").asString()).isEqualTo(payload);
    }

    private record ReturnReceiptPosted(String receiptNo) implements DomainEvent {
        public String aggregateType() { return "INVENTORY"; }
        public String aggregateId() { return receiptNo; }
        public String eventType() { return InvMirrorEvent.RETURN_RECEIPT; }
    }

    @Test
    void returnInventoryStatusCanReadUnifiedOutboxAcknowledgement() {
        String receipt = "RR-" + UUID.randomUUID();
        new TransactionTemplate(platformTx).executeWithoutResult(s ->
                facade.publish("PLATFORM", new ReturnReceiptPosted(receipt),
                        new ai.neargo.shop.event.outbox.PublishOptions("RETURN:" + receipt, 1)));
        assertThat(facade.statusOf("PLATFORM", "INVENTORY", receipt, InvMirrorEvent.RETURN_RECEIPT))
                .isEqualTo("PENDING");
    }
}
