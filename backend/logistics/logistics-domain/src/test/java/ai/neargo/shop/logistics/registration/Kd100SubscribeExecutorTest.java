package ai.neargo.shop.logistics.registration;

import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.logistics.capability.ChannelOutcome;
import ai.neargo.shop.logistics.capability.TrackingSubscriber;
import ai.neargo.shop.logistics.config.LogisticsProperties;
import ai.neargo.shop.logistics.entity.LgsChannelSubscription;
import ai.neargo.shop.logistics.entity.LgsKd100SubAttempt;
import ai.neargo.shop.logistics.entity.LgsWaybill;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillMapper;
import ai.neargo.shop.logistics.routing.ChannelRouter;
import ai.neargo.shop.spi.logistics.ShipmentSourcePort;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Kd100SubscribeExecutorTest {
    private final WaybillMapper waybills = mock(WaybillMapper.class);
    private final ShipmentSourcePort source = mock(ShipmentSourcePort.class);
    private final ChannelSubscriptionStore subscriptions = mock(ChannelSubscriptionStore.class);
    private final Kd100AttemptStore attempts = mock(Kd100AttemptStore.class);
    private final ChannelRouter router = mock(ChannelRouter.class);
    private final TrackingSubscriber kd = mock(TrackingSubscriber.class);
    private final SubscribeExecutor snapshots = mock(SubscribeExecutor.class);

    private Kd100SubscribeExecutor executor() {
        LogisticsProperties props = new LogisticsProperties();
        props.setSubscribeEnabled(true);
        return new Kd100SubscribeExecutor(waybills, source, subscriptions, attempts, router,
                snapshots, props, new ObjectMapper());
    }

    private SysOutbox event() {
        LgsWaybill w = new LgsWaybill();
        w.setId(1L);
        w.setShipmentNo("SH1");
        w.setBizRef("SUB1");
        w.setSourceShipmentNo("OS1");
        w.setSourceShipmentVersion(1L);
        w.setCarrier("YTO");
        w.setWaybillNo("YT1");
        w.setStatus("CREATED");
        w.setSubState("DONE"); // WeChat or legacy success must not suppress Kuaidi100.
        when(waybills.selectOne(any())).thenReturn(w);
        when(source.identityOf("SUB1")).thenReturn(Optional.of(
                new ShipmentSourcePort.ShipmentIdentity("OS1", 1L, "YTO", "YT1")));
        LgsChannelSubscription row = new LgsChannelSubscription();
        row.setState("PENDING");
        when(subscriptions.ensurePending(w, "kuaidi100")).thenReturn(row);
        LgsKd100SubAttempt attempt = new LgsKd100SubAttempt();
        attempt.setId(9L);
        when(attempts.reserveAttempt(eq("default"), eq("yuantong"), eq("YT1"), any(), eq("SH1"), eq("OS1:1")))
                .thenReturn(Optional.of(attempt));
        when(router.subscriber("kuaidi100")).thenReturn(Optional.of(kd));
        when(router.subscribeBlocker("kuaidi100", "YTO")).thenReturn(Optional.empty());
        when(router.codeOf("YTO", kd)).thenReturn(Optional.of("yuantong"));
        when(kd.channel()).thenReturn("kuaidi100");
        SysOutbox event = new SysOutbox();
        event.setEventType(Kd100SubscribeRequested.TYPE);
        event.setPayload("{\"shipmentNo\":\"SH1\",\"identityKey\":\"OS1:1\"}");
        return event;
    }

    @Test
    void kuaidi100IsSubscribedEvenWhenOtherChannelAlreadySucceeded() {
        SysOutbox event = event();
        when(kd.subscribe(any())).thenReturn(ChannelOutcome.ok("200", null));

        executor().consume(event);

        verify(kd).subscribe(any());
        verify(subscriptions).mark("SH1", "OS1:1", "kuaidi100", "DONE", null, null);
    }

    @Test
    void unknown501DoesNotClaimSuccessfulCurrentSubscription() {
        SysOutbox event = event();
        when(kd.subscribe(any())).thenReturn(ChannelOutcome.ok("501", null));

        executor().consume(event);

        verify(subscriptions).mark(eq("SH1"), eq("OS1:1"), eq("kuaidi100"), eq("CONFLICT"),
                eq(null), any());
    }
}
