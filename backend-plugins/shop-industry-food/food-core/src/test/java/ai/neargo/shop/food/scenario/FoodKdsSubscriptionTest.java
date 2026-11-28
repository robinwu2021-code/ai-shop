package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.FoodKdsContracts.DeviceStationsReq;
import ai.neargo.shop.food.dto.FoodKdsContracts.DeviceStatusReq;
import ai.neargo.shop.food.dto.FoodKdsContracts.RegisterDeviceReq;
import ai.neargo.shop.food.dto.FoodKdsContracts.StoreDeviceVO;
import ai.neargo.shop.food.mapper.FoodKdsMapper;
import ai.neargo.shop.food.service.impl.FoodKdsServiceImpl;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * 门店设备与厨显工位（TDD-餐饮域迁移新库 §2.8 D-5）：屏用店员账号登录，设备须本店在册、在用；厨显还要设定了工位。
 */
class FoodKdsSubscriptionTest {
    FoodKdsServiceImpl service;
    JdbcTemplate sql;

    @BeforeEach
    void setup() {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:kds_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        sql = new JdbcTemplate(ds);
        IndustrySchema.create(ds, "ind_store_device", "fnb_kds_subscription", "fnb_station");
        for (String station : List.of("HOT", "COLD"))
            sql.update("INSERT INTO fnb_station(tenant_no,entity_no,store_no,station_no,name,station_type,enabled,"
                    + "deleted,version) VALUES('MAIN','E','S',?,?,'HOT',1,0,0)", station, station);
        service =
                new FoodKdsServiceImpl(
                        new FoodKdsMapper(sql),
                        JsonMapper.builder().build(),
                        new DataSourceTransactionManager(ds),
                        Clock.fixed(Instant.parse("2026-11-28T00:00:00Z"), ZoneOffset.UTC));
    }

    private StoreDeviceVO kds() {
        return service.register("E", "S", "ADMIN", new RegisterDeviceReq("KDS", "热菜厨显", "SN1"), "reg-kds");
    }

    private static void fails(ErrorCode code, ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    @Test
    void registerIsActiveAndReplaysByRequestKeyButRejectsTheSameKeyWithOtherContent() {
        var first = kds();
        assertThat(first.status()).isEqualTo("ACTIVE");
        assertThat(first.deviceType()).isEqualTo("KDS");
        assertThat(kds()).isEqualTo(first);
        assertThat(sql.queryForObject("SELECT tenant_no FROM ind_store_device", String.class)).isEqualTo("MAIN");
        fails(ErrorCode.CONFLICT, () -> service.register(
                "E", "S", "ADMIN", new RegisterDeviceReq("KDS", "凉菜厨显", "SN1"), "reg-kds"));
        fails(ErrorCode.BAD_REQUEST, () -> service.register(
                "E", "S", "ADMIN", new RegisterDeviceReq("PRINTER", "打印机", null), "reg-printer"));
    }

    @Test
    void kdsNeedsItsOwnStoresStationsBeforeAScreenCanConnect() {
        var device = kds();
        fails(ErrorCode.FORBIDDEN, () -> service.authorize("E", "S", "staff", device.deviceNo()));
        fails(ErrorCode.BAD_REQUEST, () -> service.assignStations(
                "E", "S", "ADMIN", device.deviceNo(), new DeviceStationsReq(0, List.of("HOT", "NOPE"))));
        var assigned = service.assignStations(
                "E", "S", "ADMIN", device.deviceNo(), new DeviceStationsReq(0, List.of("HOT", "HOT")));
        assertThat(assigned.stationNos()).containsExactly("HOT");
        // 同样的工位按重放返回；改工位要带上读到的版本
        assertThat(service.assignStations(
                "E", "S", "ADMIN", device.deviceNo(), new DeviceStationsReq(0, List.of("HOT")))).isEqualTo(assigned);
        var moved = service.assignStations(
                "E", "S", "ADMIN", device.deviceNo(), new DeviceStationsReq(assigned.stationVersion(), List.of("COLD", "HOT")));
        assertThat(moved.stationNos()).containsExactly("COLD", "HOT");
        assertThat(moved.stationVersion()).isEqualTo(assigned.stationVersion() + 1);
        fails(ErrorCode.CONFLICT, () -> service.assignStations(
                "E", "S", "ADMIN", device.deviceNo(), new DeviceStationsReq(assigned.stationVersion(), List.of("COLD"))));
        assertThat(service.authorize("E", "S", "staff", device.deviceNo()).stationNos()).containsExactly("COLD", "HOT");
        fails(ErrorCode.FORBIDDEN, () -> service.authorize("E", "OTHER", "staff", device.deviceNo()));
        assertThat(service.list("E", "S")).singleElement().satisfies(d ->
                assertThat(d.stationNos()).containsExactly("COLD", "HOT"));
    }

    @Test
    void suspendingADeviceLocksItsScreenOutUntilResumed() {
        var device = kds();
        service.assignStations("E", "S", "ADMIN", device.deviceNo(), new DeviceStationsReq(0, List.of("HOT")));
        var suspended = service.changeStatus(
                "E", "S", "ADMIN", device.deviceNo(), new DeviceStatusReq(device.version(), "SUSPENDED"));
        assertThat(suspended.status()).isEqualTo("SUSPENDED");
        fails(ErrorCode.FORBIDDEN, () -> service.authorize("E", "S", "staff", device.deviceNo()));
        // 已是目标状态按重放返回；停用的厨显不能改工位
        assertThat(service.changeStatus(
                "E", "S", "ADMIN", device.deviceNo(), new DeviceStatusReq(device.version(), "SUSPENDED")))
                .isEqualTo(suspended);
        fails(ErrorCode.CONFLICT, () -> service.assignStations(
                "E", "S", "ADMIN", device.deviceNo(), new DeviceStationsReq(1, List.of("COLD"))));
        service.changeStatus("E", "S", "ADMIN", device.deviceNo(), new DeviceStatusReq(suspended.version(), "ACTIVE"));
        assertThat(service.authorize("E", "S", "staff", device.deviceNo()).stationNos()).containsExactly("HOT");
    }

    @Test
    void callScreenConnectsWithoutStationsAndOtherDeviceTypesCannotConnect() {
        var call = service.register("E", "S", "ADMIN", new RegisterDeviceReq("CALL_SCREEN", "门口叫号屏", null), "reg-call");
        assertThat(service.authorize("E", "S", "staff", call.deviceNo()).deviceType()).isEqualTo("CALL_SCREEN");
        fails(ErrorCode.CONFLICT, () -> service.assignStations(
                "E", "S", "ADMIN", call.deviceNo(), new DeviceStationsReq(0, List.of("HOT"))));
        var pos = service.register("E", "S", "ADMIN", new RegisterDeviceReq("OFFLINE_POS", "前台收银", null), "reg-pos");
        fails(ErrorCode.FORBIDDEN, () -> service.authorize("E", "S", "staff", pos.deviceNo()));
        fails(ErrorCode.FORBIDDEN, () -> service.authorize("E", "S", "staff", "UNKNOWN"));
    }
}
