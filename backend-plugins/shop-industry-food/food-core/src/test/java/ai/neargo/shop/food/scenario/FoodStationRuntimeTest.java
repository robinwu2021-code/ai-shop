package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.event.outbox.OutboxPublisher;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Real state/command persistence. Queued commands are never asserted as played. Execution deferred
 * by batch instruction.
 */
class FoodStationRuntimeTest {
    FoodStationRuntimeService runtime;
    FoodKdsService kds;
    FoodStationRuntimeMapper commands;
    FoodKdsMapper kdsMapper;
    PrintPort printing;
    DataSourceTransactionManager transactions;
    OutboxPublisher outbox;
    String subscription;
    final Clock clock = Clock.fixed(Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setup() throws Exception {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:runtime_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        var jdbc = new JdbcTemplate(ds);
        String schema =
                Files.readString(
                                Path.of(
                                        "src/main/resources/db/industry/food/V18__food_kds_event_subscription.sql"))
                        .replace("CHARACTER SET utf8mb4 COLLATE utf8mb4_bin", "")
                        .replace("ENGINE=InnoDB", "");
        for (String statement : schema.split(";")) {
            if (!statement.isBlank()) {
                jdbc.execute(statement);
            }
        }
        jdbc.execute(
                "CREATE TABLE fnb_station(entity_no VARCHAR(32),store_no VARCHAR(32),station_no"
                        + " VARCHAR(32),deleted INT)");
        jdbc.update("INSERT INTO fnb_station VALUES('E','S','ST',0)");
        transactions = new DataSourceTransactionManager(ds);
        commands = new FoodStationRuntimeMapper(jdbc);
        kdsMapper = new FoodKdsMapper(jdbc);
        printing = mock(PrintPort.class);
        outbox = mock(OutboxPublisher.class);
        when(printing.configuration(any()))
                .thenReturn(
                        new PrintPort.Configuration(
                                List.of(
                                        new PrintPort.DeviceVO(
                                                "D", "V", "LAN", "BRIDGE", "ACTIVE", "{}", null,
                                                true, 1)),
                                List.of(),
                                List.of()));
        kds =
                new FoodKdsServiceImpl(
                        kdsMapper, printing, JsonMapper.builder().build(), transactions, clock);
        subscription =
                kds.grant(
                                "E",
                                "S",
                                "ADMIN",
                                new GrantReq("D", "BRIDGE", List.of("ST"), 3600),
                                "grant")
                        .subscriptionNo();
        runtime = service(clock);
    }

    FoodStationRuntimeService service(Clock clock) {
        return new FoodStationRuntimeServiceImpl(
                commands,
                kdsMapper,
                kds,
                printing,
                outbox,
                transactions,
                clock,
                JsonMapper.builder().build());
    }

    @Test
    void pauseBlocksProductionUntilVersionedResumeAndOriginalKeyNeverReapplies() {
        var paused =
                runtime.control(
                        "E",
                        "S",
                        "ADMIN",
                        "ST",
                        new ControlReq(0, "PAUSE", "congestion", null, null),
                        "pause");
        assertThatThrownBy(() -> runtime.requireRunning("E", "S", "ST"))
                .isInstanceOf(BizException.class);
        runtime.control(
                "E",
                "S",
                "ADMIN",
                "ST",
                new ControlReq(1, "RESUME", "clear", null, null),
                "resume");
        runtime.requireRunning("E", "S", "ST");
        assertThat(
                        runtime.control(
                                "E",
                                "S",
                                "ADMIN",
                                "ST",
                                new ControlReq(0, "PAUSE", "congestion", null, null),
                                "pause"))
                .isEqualTo(paused);
        assertThat(runtime.state("E", "S", "ST").status()).isEqualTo("RUNNING");
    }

    @Test
    void voiceRequiresFencedRealReceiptAndDoesNotAutoReplayUnknown() {
        var queued =
                runtime.control(
                        "E",
                        "S",
                        "ADMIN",
                        "ST",
                        new ControlReq(0, "CALL", "ready", "D", "取餐 A12"),
                        "call");
        assertThat(queued.status()).isEqualTo("QUEUED");
        var claim = runtime.claim("E", "S", "BRIDGE", subscription);
        assertThat(claim.voiceText()).isEqualTo("取餐 A12");
        assertThat(runtime.claim("E", "S", "BRIDGE", subscription)).isNull();
        assertThatThrownBy(
                        () ->
                                runtime.acknowledge(
                                        "E",
                                        "S",
                                        "BRIDGE",
                                        subscription,
                                        new VoiceReceipt(
                                                claim.commandNo(), "wrong", "PLAYED", "OK")))
                .isInstanceOf(BizException.class);
        var late = service(Clock.offset(clock, Duration.ofSeconds(31)));
        assertThat(late.claim("E", "S", "BRIDGE", subscription)).isNull();
        assertThat(
                        FoodStationRuntimeMapper.text(
                                commands.command("E", "S", queued.commandNo(), false), "status"))
                .isEqualTo("UNKNOWN");
        assertThat(
                        late.acknowledge(
                                        "E",
                                        "S",
                                        "BRIDGE",
                                        subscription,
                                        new VoiceReceipt(
                                                claim.commandNo(),
                                                claim.leaseToken(),
                                                "PLAYED",
                                                "OK"))
                                .status())
                .isEqualTo("PLAYED");
    }
}
