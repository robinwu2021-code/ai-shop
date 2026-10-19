package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.spi.entitlement.CoreEntitlementInternalApi;
import ai.neargo.shop.spi.entitlement.EntitlementEvents;
import ai.neargo.shop.spi.entitlement.EntitlementEventInternal;
import ai.neargo.shop.spi.entitlement.PayEntitlementEventCodec;
import tools.jackson.databind.ObjectMapper;
import ai.neargo.shop.spi.entitlement.EntitlementCoreInternal;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort;
import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.SourceDecisionPort;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.svc.ConfigServiceLocator;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.shop.trade.dto.AuthorizationCommand;
import ai.neargo.shop.trade.service.EntitlementAuthorizationService;
import ai.neargo.shop.trade.service.EntitlementSourceDecisionService;
import ai.neargo.svc.client.ServiceCallException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** A real HTTP client reads actual core orders, users, stores and durable authorizations, never fake source facts. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "shop.core.entitlement.authorization.enabled=true", "shop.services.internal-token=core-rpc-test",
        "spring.datasource.url=jdbc:h2:mem:ent-core-rpc;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class EntitlementCoreFactsHttpFlowTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired EntitlementAuthorizationService authorizations;
    @Autowired EntitlementSourceDecisionService decisions;
    private String order;
    private String sub;
    private String user;
    private String entity;
    private String store;
    private EntitlementContext context;
    @BeforeEach
    void setup() {
        String id = UUID.randomUUID().toString();
        order = "OF-O" + id;
        sub = "OF-SO" + id;
        user = "OF-U" + id;
        entity = "OF-M" + id;
        store = "OF-S" + id;
        context = new EntitlementContext("MAIN", "PAY", "FACTS", "a".repeat(64));
        jdbc.update("INSERT INTO usr_account(user_no,status,tenant_no,created_at,updated_at) "
                + "VALUES (?,'NORMAL','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", user);
        jdbc.update("INSERT INTO mch_entity(entity_no,owner_user_no,name,status,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'owner facts','ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", entity, user);
        jdbc.update("INSERT INTO mch_store(store_no,entity_no,name,status,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'facts store','ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", store, entity);
        jdbc.update("INSERT INTO ord_order(order_no,user_no,status,pay_amount,goods_amount,freight_amount,"
                + "discount_amount,currency,tenant_no,created_at,updated_at) VALUES (?,?,'WAIT_PAY',900,1000,0,100,"
                + "'CNY','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", order, user);
        jdbc.update("INSERT INTO ord_sub_order(sub_order_no,order_no,user_no,entity_no,store_no,status,fulfillment,"
                + "goods_amount,freight_amount,discount_amount,pay_amount,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,?,?,?,'WAIT_PAY','SERVICE',1000,0,100,900,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                sub, order, user, entity, store);
        jdbc.update("INSERT INTO ord_item(sub_order_no,order_no,goods_no,sku_no,title,qty,price,amount,paid_share_minor,"
                + "tenant_no,created_at,updated_at) VALUES (?,?,'GOODS1','SKU1','Care',1,1000,1000,1,'MAIN',"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", sub, order);
    }

    private SourceRef source() { return new SourceRef("CORE", "ORDER_USE", sub, null, null, null, 0); }
    private CoreEntitlementInternalApi api(String token) {
        var locator = new ConfigServiceLocator();
        locator.getTargets().put(ServiceName.PLATFORM, "http://127.0.0.1:" + port);
        return new InternalHttp(locator, token).client(ServiceName.PLATFORM, CoreEntitlementInternalApi.class,
                Duration.ofSeconds(5));
    }
    @Test void actualOrderSnapshotAndConsentRoundTripWithAllBindings() {
        var api = api("core-rpc-test");
        var fact = api.useSource(new EntitlementCoreInternal.SourceQuery(context, source()));
        assertThat(fact.tenantNo()).isEqualTo("MAIN"); assertThat(fact.userNo()).isEqualTo(user);
        assertThat(fact.entityNo()).isEqualTo(entity); assertThat(fact.storeNo()).isEqualTo(store);
        assertThat(fact.items().getFirst().amountMinor()).isEqualTo(900);
        String quote = "Q" + UUID.randomUUID();
        authorizations.stageTrustedIntent("MAIN", order, source(), quote, "b".repeat(64), 100, "CNY",
                Instant.now().plusSeconds(60));
        var confirmed = authorizations.confirm("MAIN", user, order,
                new AuthorizationCommand(0, quote, "b".repeat(64), "K" + UUID.randomUUID()));
        var consent = api.authorization(new EntitlementCoreInternal.AuthorizationQuery(context,
                confirmed.authorizationRef()));
        assertThat(consent.status()).isEqualTo(SourceDecisionPort.AuthorizationState.AUTHORIZED);
        assertThat(consent.userNo()).isEqualTo(user); assertThat(consent.orderSnapshotHash())
                .isEqualTo(fact.orderSnapshotHash());
        var missing = api.decision(new EntitlementCoreInternal.SourceQuery(context, source()));
        assertThat(missing.status()).isEqualTo(SourceDecisionPort.State.UNKNOWN);
        assertThat(missing.userNo()).isNull();
        var ref = decisions.record("MAIN", source(), "USE", DecisionRef.DecisionType.USE_COMMIT,
                confirmed.authorizationRef(), null, user, entity, store, "CNY", 100, fact.orderSnapshotHash(),
                Instant.now());
        var terminal = api.decision(new EntitlementCoreInternal.SourceQuery(context, source()));
        assertThat(terminal.status()).isEqualTo(SourceDecisionPort.State.COMMITTED);
        assertThat(terminal.decision()).isEqualTo(ref);
    }
    @Test void wrongTokenForeignTenantAndMissingSourceOwnerCannotLookSuccessful() {
        assertThatThrownBy(() -> api("wrong").useSource(new EntitlementCoreInternal.SourceQuery(context, source())))
                .isInstanceOfSatisfying(ServiceCallException.class, e -> assertThat(e.statusCode()).isEqualTo(401));
        var other = new EntitlementContext("OTHER", "PAY", "FACTS", "a".repeat(64));
        assertThatThrownBy(() ->
                api("core-rpc-test").useSource(new EntitlementCoreInternal.SourceQuery(other, source())))
                .isInstanceOfSatisfying(ServiceCallException.class, e -> assertThat(e.statusCode()).isEqualTo(404));
        var paid = new SourceRef("CORE", "PAID_ORDER", order, "L1", 1, 1, 0);
        var grant = new EntitlementGrantPort.GrantCommand("G1", paid, null, "V1", user, entity, 1,
                "a".repeat(64), "PF1");
        assertThatThrownBy(() -> api("core-rpc-test").grantSource(
                new EntitlementCoreInternal.GrantSourceQuery(context, grant)))
                .isInstanceOfSatisfying(ServiceCallException.class, e -> assertThat(e.statusCode()).isEqualTo(503));
        var unknown = api("core-rpc-test").authorization(new EntitlementCoreInternal.AuthorizationQuery(context,
                "MISSING"));
        assertThat(unknown.status()).isEqualTo(SourceDecisionPort.AuthorizationState.UNKNOWN);
        assertThat(unknown.userNo()).isNull();
    }
    @Test void eventHandoffAcknowledgesOnlyDurableIdenticalFacts() {
        String eventNo = UUID.randomUUID().toString();
        var event = new EntitlementEvents.EntitlementGranted(eventNo, "G1", user, entity, "V1", "TARGETED",
                "B1", System.currentTimeMillis());
        var wire = PayEntitlementEventCodec.encode(event, json);
        var api = api("core-rpc-test");
        assertThat(api.receiveEvent(wire).eventNo()).isEqualTo(eventNo);
        assertThat(api.receiveEvent(wire).accepted()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox WHERE event_no=?", Integer.class,
                eventNo)).isEqualTo(1);
        var conflicting = new EntitlementEvents.EntitlementGranted(eventNo, "G2", user, entity, "V1", "TARGETED",
                "B1", event.occurredAt());
        assertThatThrownBy(() -> api.receiveEvent(PayEntitlementEventCodec.encode(conflicting, json)))
                .isInstanceOfSatisfying(ServiceCallException.class, e -> assertThat(e.statusCode()).isEqualTo(409));
        String extra = wire.payload().substring(0, wire.payload().length() - 1) + ",\"token\":\"raw-secret\"}";
        var tampered = new EntitlementEventInternal.Envelope(wire.tenantNo(), wire.eventNo(), wire.eventType(),
                wire.schemaVersion(), wire.aggregateType(), wire.aggregateNo(), extra, wire.payloadHash(),
                wire.occurredAt());
        assertThatThrownBy(() -> api.receiveEvent(tampered))
                .isInstanceOfSatisfying(ServiceCallException.class, e -> assertThat(e.statusCode()).isEqualTo(400));
        assertThatThrownBy(() -> api("wrong").receiveEvent(wire))
                .isInstanceOfSatisfying(ServiceCallException.class, e -> assertThat(e.statusCode()).isEqualTo(401));
    }
}
