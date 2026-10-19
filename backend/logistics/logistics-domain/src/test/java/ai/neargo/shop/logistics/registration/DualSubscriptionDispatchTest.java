package ai.neargo.shop.logistics.registration;

import ai.neargo.shop.event.DomainEvent;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.logistics.entity.LgsWaybill;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillMapper;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillNodeMapper;
import ai.neargo.shop.logistics.wxbind.WxFollowRequested;
import ai.neargo.shop.spi.logistics.ShipmentSourcePort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.atLeast;

class DualSubscriptionDispatchTest {
    @Test
    void oneRegisteredShipmentDispatchesWechatAndKuaidi100Independently() {
        WaybillMapper waybills = mock(WaybillMapper.class);
        WaybillNodeMapper nodes = mock(WaybillNodeMapper.class);
        ShipmentSourcePort source = mock(ShipmentSourcePort.class);
        OutboxEventBus events = mock(OutboxEventBus.class);
        LgsWaybill waybill = new LgsWaybill();
        waybill.setShipmentNo("SH1");
        waybill.setBizRef("SUB1");
        waybill.setCarrier("YTO");
        waybill.setWaybillNo("YT1");
        waybill.setSourceShipmentNo("OS1");
        waybill.setSourceShipmentVersion(1L);
        when(waybills.selectOne(any())).thenReturn(waybill);
        when(source.lockedIdentityOf("SUB1")).thenReturn(Optional.of(
                new ShipmentSourcePort.ShipmentIdentity("OS1", 1L, "YTO", "YT1")));
        SysOutbox shipped = new SysOutbox();
        shipped.setEventType("SUB_ORDER_SHIPPED");
        shipped.setPayload("{\"subOrderNo\":\"SUB1\",\"fulfillment\":\"EXPRESS\","
                + "\"expressCompany\":\"YTO\",\"expressNo\":\"YT1\","
                + "\"sourceShipmentNo\":\"OS1\",\"sourceShipmentVersion\":1}");

        new ShipmentRegistrar(waybills, events, new ObjectMapper(), source, nodes).consume(shipped);

        ArgumentCaptor<DomainEvent> published = ArgumentCaptor.forClass(DomainEvent.class);
        verify(events, atLeast(2)).publish(published.capture());
        assertThat(published.getAllValues()).anyMatch(e -> e instanceof WxFollowRequested)
                .anyMatch(e -> e instanceof Kd100SubscribeRequested);
    }
}
