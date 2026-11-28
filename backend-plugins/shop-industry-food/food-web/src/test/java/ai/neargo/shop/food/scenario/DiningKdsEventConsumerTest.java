package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.event.outbox.DeliveryResult;
import ai.neargo.shop.event.outbox.OutboxEnvelope;
import ai.neargo.shop.food.event.DiningKdsEventConsumer;
import ai.neargo.shop.food.spi.FoodDeviceRefresh;
import ai.neargo.shop.food.spi.FoodDeviceRefresh.Signal;
import ai.neargo.shop.food.spi.InProcessFoodDeviceRefresh;
import ai.neargo.shop.spi.food.DiningOrderAccepted;
import ai.neargo.shop.spi.food.DiningServiceEvent;
import ai.neargo.shop.spi.printing.PrintPort;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 设备出口（TDD-餐饮域迁移新库 §2.8 D-3）：原始业务事件到达后让相关的屏刷新、按路由打事件单据；
 * 不再写厨显事件表，单据内容直接由事件定格。
 */
class DiningKdsEventConsumerTest {
    private final JsonMapper json = JsonMapper.builder().build();

    private static List<Signal> recording(FoodDeviceRefresh refresh) {
        var signals = new ArrayList<Signal>();
        refresh.listen(signals::add);
        return signals;
    }

    private OutboxEnvelope kitchen(DiningServiceEvent source, String eventNo) {
        return new OutboxEnvelope(eventNo, "PLATFORM", eventNo, "FOOD_KITCHEN_TASK", source.taskNo(),
                source.eventType(), 1, "TASK:" + source.taskNo(), 2, json.writeValueAsString(source),
                source.occurredAt());
    }

    private static DiningServiceEvent action(String action, String qty) {
        return new DiningServiceEvent("FSEV", "E", "S", "BATCH", "TICKET", "TASK", "LINE", "COMP", "ST",
                "MAKE", 1, action, new BigDecimal(qty), "ready", "ACTOR", Instant.parse("2026-10-24T00:00:00Z"));
    }

    @Test
    void custodyHandoverRefreshesItsStationWithoutTouchingThePrintOwner() {
        var refresh = new InProcessFoodDeviceRefresh();
        var signals = recording(refresh);
        var printing = mock(PrintPort.class);
        var consumer = new DiningKdsEventConsumer(refresh, json, () -> printing);
        var source = new DiningServiceEvent("FSEV", "E", "S", "B", "K", "T", "L", null, "ST", "MAKE", 1,
                "CUSTODY_HANDOVER", BigDecimal.ONE, null, "A", Instant.parse("2026-10-30T00:00:00Z"));
        assertThat(source.eventType()).isEqualTo("DINING_CUSTODY_HANDOVER");
        assertThat(consumer.handle(kitchen(source, "O")).kind()).isEqualTo(DeliveryResult.Kind.SENT);
        assertThat(signals).containsExactly(new Signal("E", "S", FoodDeviceRefresh.KITCHEN, List.of("ST")));
        verifyNoInteractions(printing);
    }

    @Test
    void stationRuntimeEventsNoLongerComeThroughTheOutbox() {
        var consumer = new DiningKdsEventConsumer(new InProcessFoodDeviceRefresh(), json);
        var source = new ai.neargo.shop.spi.food.DiningStationRuntimeChanged("E", "S", "ST", "CMD", "PAUSE", 3,
                "PAUSED", "hold", Instant.parse("2026-10-30T00:00:00Z"));
        assertThat(consumer.supports(source.eventType())).isFalse();
        var envelope = new OutboxEnvelope("OLD", "PLATFORM", "OLD", "FOOD_STATION_RUNTIME", "ST", source.eventType(), 1,
                "FOOD_STATION:ST", 7, json.writeValueAsString(source), source.occurredAt());
        assertThatThrownBy(() -> consumer.handle(envelope)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readyPrintsALabelFrozenFromTheEventItselfAndRefreshesTheStation() {
        var refresh = new InProcessFoodDeviceRefresh();
        var signals = recording(refresh);
        var printing = mock(PrintPort.class);
        when(printing.configuration(any()))
                .thenReturn(new PrintPort.Configuration(List.of(), List.of(),
                        List.of(new PrintPort.RouteReq("R1", "LABEL", "ST", "DEV", "TPL", 1),
                                new PrintPort.RouteReq("R2", "LABEL", "OTHER", "DEV", "TPL", 1))));
        var consumer = new DiningKdsEventConsumer(refresh, json, () -> printing);
        var envelope = kitchen(action("READY", "0.5"), "EV");
        consumer.handle(envelope);
        assertThat(signals).containsExactly(new Signal("E", "S", FoodDeviceRefresh.KITCHEN, List.of("ST")));
        var captured = ArgumentCaptor.forClass(PrintPort.Enqueue.class);
        verify(printing).enqueue(eq(new PrintPort.Scope("E", "S", "SYSTEM")), captured.capture());
        var document = captured.getValue().document();
        assertThat(captured.getValue().stationNo()).isEqualTo("ST");
        assertThat(captured.getValue().partNo()).isEqualTo("EV");
        assertThat(document.title()).isEqualTo("制作标签");
        assertThat(document.lines()).singleElement().satisfies(line -> {
            assertThat(line.label()).isEqualTo("LINE");
            assertThat(line.quantity()).isEqualTo("0.5");
            assertThat(line.note()).contains("READY", "TASK", "ST");
        });
        // 出品要打标签，没有打印归属方就失败，由发件箱重投
        assertThatThrownBy(() -> new DiningKdsEventConsumer(refresh, json).handle(envelope))
                .isInstanceOf(IllegalStateException.class).hasMessage("KDS print owner unavailable");
    }

    @Test
    void rejectEnvelopeWhoseAggregateDoesNotMatchOriginalBusinessFact() {
        var consumer = new DiningKdsEventConsumer(new InProcessFoodDeviceRefresh(), json);
        var source = new DiningOrderAccepted("BATCH", "E", "S", "CHECK", "ORDER", "SUB",
                LocalDate.of(2026, 10, 24), "PICKUP", Instant.parse("2026-10-24T00:00:00Z"));
        var envelope = new OutboxEnvelope("EV", "PLATFORM", "EV", "FOOD_BATCH", "OTHER", "DINING_ORDER_ACCEPTED", 1,
                "BATCH:BATCH", 1, json.writeValueAsString(source), source.acceptedAt());
        assertThatThrownBy(() -> consumer.handle(envelope)).isInstanceOf(IllegalArgumentException.class);
    }
}
