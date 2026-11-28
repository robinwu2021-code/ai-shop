package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.dto.FoodKdsContracts.GrantReq;
import ai.neargo.shop.food.dto.FoodKdsContracts.RevokeReq;
import ai.neargo.shop.food.dto.FoodKdsContracts.SubscriptionVO;
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

/** 厨显订阅的开通、吊销与每次核验（TDD-餐饮域迁移新库 §2.8 D-3：屏不再按序号拉事件）。 */
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

    @Test
    void authorizeReturnsTheGrantedStationsAndChecksActorStoreAndRevocationEveryTime() {
        var grant = grant();
        assertThat(service.authorize("E", "S", "BRIDGE", grant.subscriptionNo()).stationNos())
                .containsExactly("ST");
        assertThatThrownBy(() -> service.authorize("E", "S", "OTHER", grant.subscriptionNo()))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.authorize("E", "OTHER", "BRIDGE", grant.subscriptionNo()))
                .isInstanceOf(BizException.class);
        service.revoke(
                "E",
                "S",
                "ADMIN",
                grant.subscriptionNo(),
                new RevokeReq(0, "lost device"),
                "revoke");
        assertThatThrownBy(() -> service.authorize("E", "S", "BRIDGE", grant.subscriptionNo()))
                .isInstanceOf(BizException.class);
    }

    @Test
    void rebindingTheDeviceRetiresTheOldGrant() {
        var grant = grant();
        when(printing.configuration(any())).thenReturn(configuration("ACTIVE", 2));
        assertThatThrownBy(() -> service.authorize("E", "S", "BRIDGE", grant.subscriptionNo()))
                .isInstanceOf(BizException.class);
    }

    @Test
    void grantKeyCannotBroadenScope() {
        grant();
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
