package ai.neargo.shop.logistics.domain;

import ai.neargo.shop.event.DomainEvent;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.logistics.config.LogisticsProperties;
import ai.neargo.shop.logistics.entity.LgsWaybill;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillMapper;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillNodeMapper;
import ai.neargo.shop.spi.logistics.LogisticsEvents;
import ai.neargo.shop.spi.logistics.TraceResult;
import ai.neargo.shop.spi.logistics.TraceStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 一次轨迹应用到运单上之后发出去的事件（TDD-物流模块 批 4：通知靠它们）。 */
class WaybillProgressTest {

    private final WaybillMapper waybills = mock(WaybillMapper.class);
    private final WaybillNodeMapper nodes = mock(WaybillNodeMapper.class);
    private final OutboxEventBus events = mock(OutboxEventBus.class);
    private final WaybillProgress progress = new WaybillProgress(waybills, nodes, events, new LogisticsProperties());

    private static LgsWaybill waybill(String status, Integer atLocker) {
        LgsWaybill w = new LgsWaybill();
        w.setId(7L);
        w.setShipmentNo("SH7");
        w.setBizRef("SUB7");
        w.setCarrier("SF");
        w.setWaybillNo("SF100");
        w.setProfile(LgsWaybill.PROFILE_SELF);
        w.setStatus(status);
        w.setAtLocker(atLocker);
        return w;
    }

    private static TraceResult trace(TraceStatus s, boolean atLocker) {
        return new TraceResult("SF100", "SF", s, "kuaidi100",
                List.of(new TraceResult.TraceNode(1_000L, s, "节点", null)), null, atLocker);
    }

    private List<DomainEvent> published() {
        ArgumentCaptor<DomainEvent> c = ArgumentCaptor.forClass(DomainEvent.class);
        verify(events, atLeast(0)).publish(c.capture());
        return c.getAllValues();
    }

    private List<LogisticsEvents.WaybillProgressed> progressed() {
        return published().stream().filter(e -> e instanceof LogisticsEvents.WaybillProgressed)
                .map(e -> (LogisticsEvents.WaybillProgressed) e).toList();
    }

    @Test
    @DisplayName("★★★ 派件中途放进驿站 → 主状态没变也要单发一次（atLocker=true）—— 不发的话「去取件」永远到不了买家")
    void lockerFlipWhileDeliveringIsAnnounced() {
        progress.apply(waybill(WaybillStatus.DELIVERING, 0), trace(TraceStatus.DELIVERING, true),
                "kuaidi100", "PUSH", LgsWaybill.patch(7L), 2_000L);

        assertThat(progressed()).singleElement().satisfies(e -> {
            assertThat(e.status()).isEqualTo(WaybillStatus.DELIVERING);
            assertThat(e.atLocker()).isTrue();
            assertThat(e.carrier()).isEqualTo("SF");
            assertThat(e.waybillNo()).as("通知要说哪个单号，消费方不该再回头查物流").isEqualTo("SF100");
        });
    }

    @Test
    @DisplayName("★★ 已经标过在驿站 → 快递100 每次推全量，同一条不能再发")
    void lockerAlreadyKnownIsSilent() {
        progress.apply(waybill(WaybillStatus.DELIVERING, 1), trace(TraceStatus.DELIVERING, true),
                "kuaidi100", "PUSH", LgsWaybill.patch(7L), 2_000L);

        assertThat(progressed()).isEmpty();
    }

    @Test
    @DisplayName("★★ 进入派件 → 事件带承运商与单号；承运商被这一次纠正了的话带纠正后的")
    void transitionCarriesCarrierAndNumber() {
        LgsWaybill patch = LgsWaybill.patch(7L);
        patch.setCarrier("STO");
        progress.apply(waybill(WaybillStatus.IN_TRANSIT, 0), trace(TraceStatus.DELIVERING, false),
                "kuaidi100", "PUSH", patch, 2_000L);

        assertThat(progressed()).singleElement().satisfies(e -> {
            assertThat(e.status()).isEqualTo(WaybillStatus.DELIVERING);
            assertThat(e.atLocker()).isFalse();
            assertThat(e.carrier()).isEqualTo("STO");
        });
    }

    @Test
    @DisplayName("★★ 签收事件带承运商与单号")
    void signedCarriesCarrierAndNumber() {
        progress.apply(waybill(WaybillStatus.DELIVERING, 0), trace(TraceStatus.SIGNED, false),
                "kuaidi100", "PUSH", LgsWaybill.patch(7L), 2_000L);

        assertThat(published()).filteredOn(e -> e instanceof LogisticsEvents.WaybillSigned).singleElement()
                .satisfies(e -> {
                    LogisticsEvents.WaybillSigned s = (LogisticsEvents.WaybillSigned) e;
                    assertThat(s.carrier()).isEqualTo("SF");
                    assertThat(s.waybillNo()).isEqualTo("SF100");
                });
    }
}
