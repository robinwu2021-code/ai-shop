package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import ai.neargo.shop.food.dto.FoodKdsContracts.EventDraft;
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
import java.util.concurrent.CountDownLatch;
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
            FoodStationRuntimeService runtime, String subscription) {}

    private Fixture fixture(org.springframework.boot.test.context.assertj.AssertableApplicationContext c) {
        var f = prepare(c);
        new ResourceDatabasePopulator(new FileSystemResource(
                "src/main/resources/db/industry/food/V18__food_kds_event_subscription.sql")).execute(f.source());
        f.sql().execute("CREATE TABLE fnb_station(entity_no VARCHAR(32),store_no VARCHAR(32),station_no VARCHAR(32),deleted INT)");
        f.sql().update("INSERT INTO fnb_station VALUES('E','S','ST',0)");
        var printing = mock(PrintPort.class);
        when(printing.configuration(any())).thenReturn(new PrintPort.Configuration(List.of(
                new PrintPort.DeviceVO("D", "V", "LAN", "BRIDGE", "ACTIVE", "{}", null, true, 1)), List.of(), List.of()));
        var mapper = new FoodKdsMapper(f.sql());
        var json = c.getBean(ObjectMapper.class);
        var manager = c.getBean(PlatformTransactionManager.class);
        var kds = spy(new FoodKdsServiceImpl(mapper, printing, json, manager, clock));
        var subscription = kds.grant("E", "S", "ADMIN", new GrantReq("D", "BRIDGE", List.of("ST"), 3600), "grant");
        var runtime = new FoodStationRuntimeServiceImpl(new FoodStationRuntimeMapper(f.sql()), mapper, kds, printing, manager, clock, json);
        return new Fixture(f.sql(), mapper, kds, runtime, subscription.subscriptionNo());
    }

    @Test void controlsCommitWithOriginalKdsPayloadCursorAndIdempotencyWithoutOutbox() {
        runner().run(c -> {
            var f = fixture(c);
            var request = new ControlReq(0, "PAUSE", "congestion", null, null);
            var paused = f.runtime.control("E", "S", "ADMIN", "ST", request, "pause");
            assertThat(f.runtime.control("E", "S", "ADMIN", "ST", request, "pause")).isEqualTo(paused);
            f.runtime.control("E", "S", "ADMIN", "ST", new ControlReq(1, "RESUME", "clear", null, null), "resume");
            var events = f.kds.replay("E", "S", "BRIDGE", f.subscription, 0, 10).events();
            assertThat(events).hasSize(2);
            assertThat(events).extracting(e -> e.cursor()).containsExactly(1L, 2L);
            assertThat(events).extracting(e -> e.sourceVersion()).containsExactly(1L, 2L);
            assertThat(events.getFirst().sourceNo()).isEqualTo(paused.commandNo());
            assertThat(events.getFirst().eventType()).isEqualTo("STATION_CONTROL");
            assertThat(c.getBean(ObjectMapper.class).readTree(events.getFirst().payload()).path("action").asString()).isEqualTo("PAUSE");
            assertThat(f.sql.queryForObject("SELECT COUNT(*) FROM sys_outbox", Long.class)).isZero();
            assertThat(f.runtime.state("E", "S", "ST").status()).isEqualTo("RUNNING");
        });
    }

    @Test void voiceEventsFollowActualQueuedSentAndPlayedReceiptsWithNoDuplicateAck() {
        runner().run(c -> {
            var f = fixture(c);
            var command = f.runtime.control("E", "S", "ADMIN", "ST", new ControlReq(0, "CALL", "ready", "D", "A12"), "call");
            var claim = f.runtime.claim("E", "S", "BRIDGE", f.subscription);
            var receipt = new VoiceReceipt(claim.commandNo(), claim.leaseToken(), "PLAYED", "OK");
            var played = f.runtime.acknowledge("E", "S", "BRIDGE", f.subscription, receipt);
            assertThat(f.runtime.acknowledge("E", "S", "BRIDGE", f.subscription, receipt)).isEqualTo(played);
            var events = f.kds.replay("E", "S", "BRIDGE", f.subscription, 0, 10).events();
            assertThat(events).hasSize(3);
            assertThat(events).extracting(e -> e.eventType()).containsOnly("VOICE_CALL");
            assertThat(events).extracting(e -> e.sourceNo()).containsOnly(command.commandNo());
            var statuses = events.stream().map(e -> c.getBean(ObjectMapper.class).readTree(e.payload()).path("status").asString()).toList();
            assertThat(statuses).containsExactly("QUEUED", "SENT", "PLAYED");
            assertThat(f.sql.queryForObject("SELECT COUNT(*) FROM sys_outbox", Long.class)).isZero();
        });
    }

    @Test void failingLocalProjectionRollsBackCommandStateEventAndCursorTogether() {
        runner().run(c -> {
            var f = fixture(c);
            doAnswer(i -> { i.callRealMethod(); throw new IllegalStateException("projection rollback"); })
                    .when(f.kds).append(any(EventDraft.class));
            assertThatThrownBy(() -> f.runtime.control("E", "S", "ADMIN", "ST", new ControlReq(0, "PAUSE", "hold", null, null), "rollback"))
                    .isInstanceOf(IllegalStateException.class).hasMessage("projection rollback");
            assertThat(f.runtime.state("E", "S", "ST").version()).isZero();
            assertThat(f.runtime.state("E", "S", "ST").status()).isEqualTo("RUNNING");
            assertThat(f.sql.queryForObject("SELECT COUNT(*) FROM fnb_kds_voice_command", Long.class)).isZero();
            assertThat(f.sql.queryForObject("SELECT COUNT(*) FROM fnb_kds_event", Long.class)).isZero();
            assertThat(f.mapper.head("E", "S", false)).isZero();
        });
    }

    @Test void concurrentControlAndVoiceClaimSerializeOnOriginalCursorAndKeepAllEvents() throws Exception {
        runner().run(c -> {
            var f = fixture(c);
            f.runtime.control("E", "S", "ADMIN", "ST", new ControlReq(0, "CALL", "ready", "D", "A12"), "call");
            var holding = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            doAnswer(i -> {
                EventDraft event = i.getArgument(0);
                if ("STATION_CONTROL".equals(event.eventType())) {
                    holding.countDown();
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("control gate timeout");
                }
                return i.callRealMethod();
            }).when(f.kds).append(any(EventDraft.class));
            try (var pool = Executors.newFixedThreadPool(2)) {
                var control = pool.submit(() -> f.runtime.control("E", "S", "ADMIN", "ST", new ControlReq(1, "PAUSE", "hold", null, null), "pause"));
                try {
                    assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
                    var voice = pool.submit(() -> f.runtime.claim("E", "S", "BRIDGE", f.subscription));
                    release.countDown();
                    assertThat(control.get(10, TimeUnit.SECONDS).status()).isEqualTo("APPLIED");
                    assertThat(voice.get(10, TimeUnit.SECONDS).commandNo()).isNotBlank();
                } finally { release.countDown(); }
            }
            var events = f.kds.replay("E", "S", "BRIDGE", f.subscription, 0, 10).events();
            assertThat(events).extracting(e -> e.cursor()).containsExactly(1L, 2L, 3L);
            assertThat(events).extracting(e -> e.eventNo()).doesNotHaveDuplicates();
            assertThat(f.sql.queryForObject("SELECT COUNT(*) FROM sys_outbox", Long.class)).isZero();
        });
    }
}
