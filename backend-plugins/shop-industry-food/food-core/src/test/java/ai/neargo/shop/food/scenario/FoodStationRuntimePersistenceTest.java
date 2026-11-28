package ai.neargo.shop.food.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.spi.FoodDeviceRefresh;
import ai.neargo.shop.food.spi.FoodDeviceRefresh.Signal;
import ai.neargo.shop.food.spi.InProcessFoodDeviceRefresh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.food.dto.FoodKdsContracts.GrantReq;
import ai.neargo.shop.food.dto.FoodStationRuntimeContracts.ControlReq;
import ai.neargo.shop.food.dto.FoodStationRuntimeContracts.VoiceReceipt;
import ai.neargo.shop.food.mapper.FoodKdsMapper;
import ai.neargo.shop.food.mapper.FoodStationRuntimeMapper;
import ai.neargo.shop.food.service.FoodKdsService;
import ai.neargo.shop.food.service.FoodStationRuntimeService;
import ai.neargo.shop.food.service.impl.FoodKdsServiceImpl;
import ai.neargo.shop.food.service.impl.FoodStationRuntimeServiceImpl;
import ai.neargo.shop.spi.printing.PrintPort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** Actual MySQL9 V18 tables and transactions; remote device configuration remains a test owner. */
@EnabledIfEnvironmentVariable(named = "FOOD_TEST_DISPOSABLE", matches = "true")
class FoodStationRuntimePersistenceTest extends FoodWriteGuardFixture {
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-30T00:00:00Z"), ZoneOffset.UTC);

    private record Fixture(JdbcTemplate sql, FoodKdsMapper mapper, FoodKdsService kds,
            FoodStationRuntimeService runtime, String subscription, List<Signal> signals) {}

    private Fixture fixture(org.springframework.boot.test.context.assertj.AssertableApplicationContext c) {
        var f = prepare(c);
        new ResourceDatabasePopulator(new FileSystemResource(
                "src/main/resources/db/industry/food/V18__food_kds_event_subscription.sql")).execute(f.source());
        f.sql().execute("CREATE TABLE fnb_station(entity_no VARCHAR(32),store_no VARCHAR(32),station_no VARCHAR(32),deleted INT,runtime_status VARCHAR(10),version BIGINT DEFAULT 0)");
        f.sql().update("INSERT INTO fnb_station(entity_no,store_no,station_no,deleted) VALUES('E','S','ST',0)");
        var printing = mock(PrintPort.class);
        when(printing.configuration(any())).thenReturn(new PrintPort.Configuration(List.of(
                new PrintPort.DeviceVO("D", "V", "LAN", "BRIDGE", "ACTIVE", "{}", null, true, 1)), List.of(), List.of()));
        var mapper = new FoodKdsMapper(f.sql());
        var json = c.getBean(ObjectMapper.class);
        var manager = c.getBean(PlatformTransactionManager.class);
        var kds = new FoodKdsServiceImpl(mapper, printing, json, manager, clock);
        var subscription = kds.grant("E", "S", "ADMIN", new GrantReq("D", "BRIDGE", List.of("ST"), 3600), "grant");
        var runtime = new FoodStationRuntimeServiceImpl(new FoodStationRuntimeMapper(f.sql()), mapper, kds, printing, manager, clock, json);
        var refresh = new InProcessFoodDeviceRefresh();
        var signals = new CopyOnWriteArrayList<Signal>();
        refresh.listen(signals::add);
        runtime.setDeviceRefresh(refresh);
        return new Fixture(f.sql(), mapper, kds, runtime, subscription.subscriptionNo(), signals);
    }

    private static final Signal STATION = new Signal("E", "S", FoodDeviceRefresh.STATION, List.of("ST"));

    /** 开停提交后让看这个工位的屏刷新（TDD §2.8 D-3）；同键重放不再发。 */
    @Test void controlsCommitIdempotentlyAndSignalTheStationAfterCommitWithoutOutbox() {
        runner().run(c -> {
            var f = fixture(c);
            var request = new ControlReq(0, "PAUSE", "congestion", null, null);
            var paused = f.runtime.control("E", "S", "ADMIN", "ST", request, "pause");
            assertThat(f.runtime.control("E", "S", "ADMIN", "ST", request, "pause")).isEqualTo(paused);
            // 开停写在工位行上，版本号即工位版本号（TDD §2.8 D-2）。
            assertThat(f.sql.queryForMap("SELECT runtime_status,version FROM fnb_station WHERE station_no='ST'"))
                    .containsEntry("runtime_status", "PAUSED").containsEntry("version", 1L);
            f.runtime.control("E", "S", "ADMIN", "ST", new ControlReq(1, "RESUME", "clear", null, null), "resume");
            assertThat(f.signals).containsExactly(STATION, STATION);
            assertThat(f.sql.queryForObject("SELECT COUNT(*) FROM sys_outbox", Long.class)).isZero();
            assertThat(f.runtime.state("E", "S", "ST").status()).isEqualTo("RUNNING");
        });
    }

    @Test void voiceCommandSignalsOnQueueSendAndPlayedReceiptWithNoDuplicateAck() {
        runner().run(c -> {
            var f = fixture(c);
            f.runtime.control("E", "S", "ADMIN", "ST", new ControlReq(0, "CALL", "ready", "D", "A12"), "call");
            var claim = f.runtime.claim("E", "S", "BRIDGE", f.subscription);
            var receipt = new VoiceReceipt(claim.commandNo(), claim.leaseToken(), "PLAYED", "OK");
            var played = f.runtime.acknowledge("E", "S", "BRIDGE", f.subscription, receipt);
            assertThat(f.runtime.acknowledge("E", "S", "BRIDGE", f.subscription, receipt)).isEqualTo(played);
            assertThat(played.status()).isEqualTo("PLAYED");
            assertThat(f.signals).containsExactly(STATION, STATION, STATION);
            assertThat(f.sql.queryForObject("SELECT COUNT(*) FROM sys_outbox", Long.class)).isZero();
        });
    }

    @Test void rejectedControlChangesNothingAndSignalsNothing() {
        runner().run(c -> {
            var f = fixture(c);
            assertThatThrownBy(() -> f.runtime.control("E", "S", "ADMIN", "ST", new ControlReq(5, "PAUSE", "hold", null, null), "stale"))
                    .isInstanceOf(BizException.class);
            assertThat(f.runtime.state("E", "S", "ST").version()).isZero();
            assertThat(f.runtime.state("E", "S", "ST").status()).isEqualTo("RUNNING");
            assertThat(f.sql.queryForObject("SELECT COUNT(*) FROM fnb_kds_voice_command", Long.class)).isZero();
            assertThat(f.signals).isEmpty();
        });
    }

    @Test void concurrentControlAndVoiceClaimBothCommit() throws Exception {
        runner().run(c -> {
            var f = fixture(c);
            f.runtime.control("E", "S", "ADMIN", "ST", new ControlReq(0, "CALL", "ready", "D", "A12"), "call");
            try (var pool = Executors.newFixedThreadPool(2)) {
                var control = pool.submit(() -> f.runtime.control("E", "S", "ADMIN", "ST", new ControlReq(1, "PAUSE", "hold", null, null), "pause"));
                var voice = pool.submit(() -> f.runtime.claim("E", "S", "BRIDGE", f.subscription));
                assertThat(control.get(10, TimeUnit.SECONDS).status()).isEqualTo("APPLIED");
                assertThat(voice.get(10, TimeUnit.SECONDS).commandNo()).isNotBlank();
            }
            assertThat(f.sql.queryForObject("SELECT COUNT(*) FROM sys_outbox", Long.class)).isZero();
        });
    }
}
