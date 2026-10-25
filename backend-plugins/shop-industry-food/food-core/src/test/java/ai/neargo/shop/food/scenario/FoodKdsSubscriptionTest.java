package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.assembler.FoodEventPrintDocumentAssembler;
import ai.neargo.shop.food.dto.FoodKdsContracts.EventDraft;
import ai.neargo.shop.food.dto.FoodKdsContracts.EventVO;
import ai.neargo.shop.food.dto.FoodKdsContracts.GrantReq;
import ai.neargo.shop.food.dto.FoodKdsContracts.RevokeReq;
import ai.neargo.shop.food.dto.FoodKdsContracts.SubscriptionVO;
import ai.neargo.shop.food.dto.PrintDocumentReq;
import ai.neargo.shop.food.mapper.FoodKdsMapper;
import ai.neargo.shop.food.service.impl.FoodKdsServiceImpl;
import ai.neargo.shop.spi.printing.PrintPort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Real persistent replay/grant tests; source written during coding phase, execution deferred. */
class FoodKdsSubscriptionTest {
    FoodKdsServiceImpl service;
    PrintPort printing;
    FoodKdsMapper mapper;

    @BeforeEach
    void setup() throws Exception {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:kds_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        var jdbc = new JdbcTemplate(ds);
        String sql =
                Files.readString(
                        Path.of(
                                "src/main/resources/db/industry/food/V18__food_kds_event_subscription.sql"));
        sql =
                sql.replace("CHARACTER SET utf8mb4 COLLATE utf8mb4_bin", "")
                        .replace("ENGINE=InnoDB", "");
        for (String statement : sql.split(";")) {
            if (!statement.isBlank()) {
                jdbc.execute(statement);
            }
        }
        printing = mock(PrintPort.class);
        when(printing.configuration(any())).thenReturn(configuration("ACTIVE", 1));
        mapper = new FoodKdsMapper(jdbc);
        service =
                new FoodKdsServiceImpl(
                        mapper,
                        printing,
                        JsonMapper.builder().build(),
                        new DataSourceTransactionManager(ds),
                        Clock.fixed(Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC));
    }

    PrintPort.Configuration configuration(String status, long epoch) {
        return new PrintPort.Configuration(
                List.of(
                        new PrintPort.DeviceVO(
                                "D", "V", "LAN", "BRIDGE", status, "{}", null, true, epoch)),
                List.of(),
                List.of());
    }

    SubscriptionVO grant() {
        return service.grant(
                "E", "S", "ADMIN", new GrantReq("D", "BRIDGE", List.of("ST"), 3600), "grant");
    }

    EventDraft event(String no, String station) {
        return new EventDraft(
                no,
                "E",
                "S",
                station,
                "KITCHEN_ACTION",
                "TASK",
                1,
                "{\"action\":\"READY\"}",
                "2026-10-24T00:00:00.123456Z");
    }

    @Test
    void originalEventReplaysOnceWithStableCursorAndNoOtherStationDisclosure() {
        var grant = grant();
        service.append(event("EV1", "OTHER"));
        service.append(event("EV2", "ST"));
        service.append(event("EV2", "ST"));
        var page = service.replay("E", "S", "BRIDGE", grant.subscriptionNo(), 0, 100);
        assertThat(page.events()).extracting(EventVO::eventNo).containsExactly("EV2");
        assertThat(page.nextCursor()).isEqualTo(2);
        assertThat(service.replay("E", "S", "BRIDGE", grant.subscriptionNo(), 2, 100).events())
                .isEmpty();
    }

    @Test
    void printUsesOnlyOriginalEventDeltaAndItsFrozenStation() {
        service.append(
                new EventDraft(
                        "CHANGE",
                        "E",
                        "S",
                        "ST",
                        "KITCHEN_ACTION",
                        "TASK",
                        1,
                        "{\"foodLineNo\":\"LINE\",\"action\":\"VOID\",\"qty\":0.5,\"buyerPhone\":\"SECRET\"}",
                        "2026-10-24T00:00:00Z"));
        var documents = new FoodEventPrintDocumentAssembler(mapper, JsonMapper.builder().build());
        var first =
                documents.freeze(
                        new PrintPort.Scope("E", "S", "ADMIN"),
                        new PrintDocumentReq("KITCHEN_CHANGE", "CHANGE", "ST"),
                        "one");
        assertThat(first.document().lines().getFirst().quantity()).isEqualTo("0.5");
        assertThat(first.document().lines().getFirst().note())
                .contains("VOID", "TASK")
                .doesNotContain("SECRET");
        assertThat(
                        documents.freeze(
                                new PrintPort.Scope("E", "S", "ADMIN"),
                                new PrintDocumentReq("KITCHEN_CHANGE", "CHANGE", "ST"),
                                "two"))
                .isEqualTo(first);
        assertThatThrownBy(
                        () ->
                                documents.freeze(
                                        new PrintPort.Scope("E", "S", "ADMIN"),
                                        new PrintDocumentReq("KITCHEN_CHANGE", "CHANGE", "OTHER"),
                                        "three"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void replayChecksActorAndRevocationAndStoreEveryTime() {
        var grant = grant();
        assertThatThrownBy(() -> service.replay("E", "S", "OTHER", grant.subscriptionNo(), 0, 100))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(
                        () ->
                                service.replay(
                                        "E", "OTHER", "BRIDGE", grant.subscriptionNo(), 0, 100))
                .isInstanceOf(BizException.class);
        service.revoke(
                "E",
                "S",
                "ADMIN",
                grant.subscriptionNo(),
                new RevokeReq(0, "lost device"),
                "revoke");
        assertThatThrownBy(() -> service.replay("E", "S", "BRIDGE", grant.subscriptionNo(), 0, 100))
                .isInstanceOf(BizException.class);
    }

    @Test
    void rebindCannotReviveOldGrantAndSameSourceCannotChangePayload() {
        var grant = grant();
        service.append(event("EV1", "ST"));
        assertThatThrownBy(() -> service.append(event("EV1", "OTHER")))
                .isInstanceOf(BizException.class);
        when(printing.configuration(any())).thenReturn(configuration("ACTIVE", 2));
        assertThatThrownBy(() -> service.replay("E", "S", "BRIDGE", grant.subscriptionNo(), 0, 100))
                .isInstanceOf(BizException.class);
    }

    @Test
    void firstPageNeverSkipsRemainingAuthorizedEventsAndGrantKeyCannotBroadenScope() {
        var grant = grant();
        service.append(event("EV1", "ST"));
        service.append(event("EV2", "ST"));
        var first = service.replay("E", "S", "BRIDGE", grant.subscriptionNo(), 0, 1);
        assertThat(first.hasMore()).isTrue();
        assertThat(
                        service.replay(
                                        "E",
                                        "S",
                                        "BRIDGE",
                                        grant.subscriptionNo(),
                                        first.nextCursor(),
                                        1)
                                .events())
                .extracting(EventVO::eventNo)
                .containsExactly("EV2");
        assertThatThrownBy(
                        () ->
                                service.grant(
                                        "E",
                                        "S",
                                        "ADMIN",
                                        new GrantReq("D", "BRIDGE", List.of("OTHER"), 3600),
                                        "grant"))
                .isInstanceOf(BizException.class);
    }
}
