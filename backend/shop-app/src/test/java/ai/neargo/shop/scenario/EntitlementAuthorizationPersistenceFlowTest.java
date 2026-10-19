package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.SourceDecisionPort;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.trade.entitlement.config.EntitlementAuthorizationProperties;
import ai.neargo.shop.trade.entitlement.port.EntitlementSourceDecisionPortImpl;
import ai.neargo.shop.trade.dto.AuthorizationCommand;
import ai.neargo.shop.trade.mapper.TradeMappers.EntitlementAuthorizationMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.EntitlementSourceDecisionMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderItemMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import ai.neargo.shop.trade.service.EntitlementAuthorizationService;
import ai.neargo.shop.trade.service.EntitlementSourceDecisionService;
import ai.neargo.shop.trade.service.impl.EntitlementAuthorizationServiceImpl;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real core H2 rows prove confirmation and source decisions survive service re-instantiation. */
@SpringBootTest(properties = {
        "shop.core.entitlement.authorization.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:ent-auth-flow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class EntitlementAuthorizationPersistenceFlowTest {
    @Autowired private EntitlementAuthorizationService authorization;
    @Autowired private EntitlementSourceDecisionService sourceDecisions;
    @Autowired private SourceDecisionPort sourceDecisionPort;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntitlementAuthorizationMapper authorizationMapper;
    @Autowired private EntitlementSourceDecisionMapper decisionMapper;
    @Autowired private OrderMapper orderMapper;
    @Autowired private SubOrderMapper subOrderMapper;
    @Autowired private OrderItemMapper itemMapper;
    @Autowired private ObjectMapper json;
    @Autowired private EntitlementAuthorizationProperties properties;
    private String orderNo;
    private String subOrderNo;
    private String userNo;

    @BeforeEach
    void prepareOrder() {
        orderNo = "EA-ORDER-" + UUID.randomUUID();
        subOrderNo = "EA-SUB-" + UUID.randomUUID();
        userNo = "EA-USER-" + UUID.randomUUID();
        jdbc.update("INSERT INTO ord_order(order_no,user_no,status,pay_amount,currency,tenant_no,created_at,updated_at,"
                + "version,deleted) VALUES (?,?,'WAIT_PAY',1000,'CNY','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0)",
                orderNo, userNo);
        jdbc.update("INSERT INTO ord_sub_order(sub_order_no,order_no,user_no,entity_no,store_no,status,fulfillment,"
                + "pay_amount,tenant_no,created_at,updated_at,version,deleted) VALUES (?,?,?,'EA-ENTITY','EA-STORE',"
                + "'WAIT_PAY','SERVICE',1000,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0)",
                subOrderNo, orderNo, userNo);
        jdbc.update("INSERT INTO ord_item(sub_order_no,order_no,goods_no,sku_no,title,price,qty,amount,tenant_no,"
                + "created_at,updated_at,version,deleted) VALUES (?,?,'EA-GOODS','EA-SKU','测试项目',1000,1,1000,'MAIN',"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0)", subOrderNo, orderNo);
    }

    @Test
    void sourceDecisionReplyUsesTheSamePrecisionAsDurableQuery() {
        var source = source(0);
        var ref = sourceDecisions.record("MAIN", source, "USE", DecisionRef.DecisionType.USE_COMMIT,
                "NANO-AUTH", null, userNo, "EA-ENTITY", "EA-STORE", "CNY", 1000,
                "b".repeat(64), Instant.parse("2026-10-19T00:00:00.123456Z"));
        var context = new EntitlementContext("MAIN", "PAY-SERVICE", "NANO", "c".repeat(64));
        assertThat(sourceDecisionPort.query(context, source).decision()).isEqualTo(ref);
    }

    @Test
    void confirmsOnlyCurrentConsumerAndReplaysSameRequestAfterServiceRestart() {
        var source = source(0);
        var quote = "EA-QUOTE-" + UUID.randomUUID();
        authorization.stageTrustedIntent("MAIN", orderNo, source, quote, "a".repeat(64), 1000, "CNY",
                Instant.now().plusSeconds(180));
        var command = new AuthorizationCommand(0, quote, "a".repeat(64), "EA-REQUEST-1");
        var first = authorization.confirm("MAIN", userNo, orderNo, command);
        var restartedService = new EntitlementAuthorizationServiceImpl(authorizationMapper, orderMapper,
                subOrderMapper, itemMapper, json, properties);
        var replay = restartedService.confirm("MAIN", userNo, orderNo, command);
        assertThat(replay).isEqualTo(first);
        var context = new EntitlementContext("MAIN", "PAY-SERVICE", "EA-CHECK", "b".repeat(64));
        var restartedQuery = new EntitlementSourceDecisionPortImpl(authorizationMapper, decisionMapper, json);
        assertThat(restartedQuery.authorization(context, first.authorizationRef()).status())
                .isEqualTo(SourceDecisionPort.AuthorizationState.AUTHORIZED);
        assertThat(restartedQuery.authorization(context, first.authorizationRef()).userNo()).isEqualTo(userNo);
        assertThatThrownBy(() -> authorization.confirm("MAIN", "EA-OTHER", orderNo,
                new AuthorizationCommand(0, quote, "a".repeat(64), "EA-REQUEST-2")))
                .isInstanceOf(BizException.class);
    }

    @Test
    void orderLinePriceChangeInvalidatesPendingAuthorization() {
        var source = source(0);
        var quote = "EA-QUOTE-" + UUID.randomUUID();
        authorization.stageTrustedIntent("MAIN", orderNo, source, quote, "c".repeat(64), 1000, "CNY",
                Instant.now().plusSeconds(180));
        jdbc.update("UPDATE ord_item SET price=900,amount=900 WHERE order_no=?", orderNo);
        assertThatThrownBy(() -> authorization.confirm("MAIN", userNo, orderNo,
                new AuthorizationCommand(0, quote, "c".repeat(64), "EA-REQUEST-3")))
                .isInstanceOf(BizException.class);
    }

    @Test
    void cancelledIntentCanBeFollowedByOneSuccessButNotAnotherRevisionSuccess() {
        var context = new EntitlementContext("MAIN", "ORDER-SERVICE", "EA-DECISION", "d".repeat(64));
        var revision0 = source(0);
        sourceDecisions.record("MAIN", revision0, "USE", DecisionRef.DecisionType.USE_CANCEL, null, null,
                userNo, "EA-ENTITY", "EA-STORE", "CNY", 1000, "e".repeat(64), Instant.now());
        var revision1 = source(1);
        var committed = sourceDecisions.record("MAIN", revision1, "USE", DecisionRef.DecisionType.USE_COMMIT,
                "EA-AUTH", null, userNo, "EA-ENTITY", "EA-STORE", "CNY", 1000, "f".repeat(64), Instant.now());
        assertThat(sourceDecisionPort.query(context, revision1).status()).isEqualTo(SourceDecisionPort.State.COMMITTED);
        assertThat(sourceDecisionPort.query(context, revision1).decision().decisionNo()).isEqualTo(committed.decisionNo());
        assertThat(sourceDecisionPort.query(context, source(2)).status()).isEqualTo(SourceDecisionPort.State.COMMITTED);
        assertThatThrownBy(() -> sourceDecisions.record("MAIN", source(2), "USE", DecisionRef.DecisionType.USE_COMMIT,
                "EA-AUTH-2", null, userNo, "EA-ENTITY", "EA-STORE", "CNY", 1000, "f".repeat(64), Instant.now()))
                .isInstanceOf(RuntimeException.class);
    }

    private SourceRef source(long revision) {
        return new SourceRef("CORE", "SUB_ORDER_USE", subOrderNo, null, null, null, revision);
    }
    @Test
    void orderRefundVocabularyResolvesTheReversePurposeInsteadOfFallingBackToGrant() {
        var context = new EntitlementContext("MAIN", "AFTER-SALE", "REFUND-FACTS", "d".repeat(64));
        var source = new SourceRef("CORE", "ORDER_REFUND", "AS" + UUID.randomUUID(), "ORIGINAL-USAGE", null, null, 1);
        var ref = sourceDecisions.record("MAIN", source, "REVERSE", DecisionRef.DecisionType.ORDER_REVERSE,
                null, null, userNo, "EA-ENTITY", "EA-STORE", "CNY", 100, "e".repeat(64), Instant.now());
        var port = new EntitlementSourceDecisionPortImpl(authorizationMapper, decisionMapper, json);
        assertThat(port.query(context, source).status()).isEqualTo(SourceDecisionPort.State.COMMITTED);
        assertThat(port.query(context, source).decision()).isEqualTo(ref);
    }

}
