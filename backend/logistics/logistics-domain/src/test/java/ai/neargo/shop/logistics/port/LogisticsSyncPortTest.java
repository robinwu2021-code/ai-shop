package ai.neargo.shop.logistics.port;

import ai.neargo.shop.logistics.capability.WaybillFollower;
import ai.neargo.shop.logistics.config.LogisticsProperties;
import ai.neargo.shop.logistics.domain.PhoneCipher;
import ai.neargo.shop.logistics.domain.WaybillProgress;
import ai.neargo.shop.logistics.entity.LgsChannelSubscription;
import ai.neargo.shop.logistics.entity.LgsWaybill;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.ChannelSubscriptionMapper;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillMapper;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillNodeMapper;
import ai.neargo.shop.logistics.registration.ChannelSubscriptionStore;
import ai.neargo.shop.logistics.routing.ChannelRouter;
import ai.neargo.shop.spi.logistics.TraceProvider;
import ai.neargo.shop.spi.logistics.TraceResult;
import ai.neargo.shop.spi.logistics.TraceStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LogisticsSyncPortTest {
    private final WaybillMapper waybills = mock(WaybillMapper.class);
    private final LogisticsPortImpl port = new LogisticsPortImpl(waybills, mock(WaybillNodeMapper.class),
            mock(ChannelRouter.class), mock(WaybillProgress.class), mock(PhoneCipher.class),
            new LogisticsProperties(), () -> 1234L);

    @Test
    void existingWechatTokenIsQueriedBeforeKuaidi100() {
        LgsWaybill w = waybill();
        when(waybills.selectList(any())).thenReturn(List.of(w));
        ChannelSubscriptionStore subscriptions = mock(ChannelSubscriptionStore.class);
        LgsChannelSubscription wx = new LgsChannelSubscription();
        wx.setRef("wx-token");
        when(subscriptions.find("LGS-1", "OS-1:1", "wx-follow")).thenReturn(wx);
        port.setSubscriptions(subscriptions);
        WaybillFollower follower = mock(WaybillFollower.class);
        when(follower.queryFollow("wx-token", "STO", "STO123"))
                .thenReturn(Optional.of(new TraceResult("STO123", "STO", TraceStatus.SIGNED,
                        "wx-follow", List.of())));
        port.setFollower(follower);
        TraceProvider kd = mock(TraceProvider.class);
        when(kd.name()).thenReturn("kuaidi100");
        port.setPrecheckProviders(List.of(kd));

        var result = port.precheck("SUB-1", "STO", "STO123", "13900000000");

        assertThat(result.source()).isEqualTo("wx-follow");
        assertThat(result.status()).isEqualTo("SIGNED");
        assertThat(result.confirmationRequired()).isTrue();
        verify(kd, never()).trace(any(), any(), any());
    }

    @Test
    void activeOtherOrderIsInvalidAndUnknownChannelResultStaysUnknown() {
        LgsWaybill other = waybill();
        other.setBizRef("OTHER");
        when(waybills.selectList(any())).thenReturn(List.of(other));
        assertThat(port.precheck("SUB-1", "STO", "STO123", null).verdict()).isEqualTo("INVALID");
        when(waybills.selectList(any())).thenReturn(List.of());
        assertThat(port.precheck("SUB-1", "STO", "STO123", null).verdict()).isEqualTo("UNKNOWN");
    }

    @Test
    void summariesReadShipmentsAndChannelsInTwoQueries() {
        LgsWaybill w = waybill();
        when(waybills.selectList(any())).thenReturn(List.of(w));
        ChannelSubscriptionMapper channels = mock(ChannelSubscriptionMapper.class);
        LgsChannelSubscription wx = new LgsChannelSubscription();
        wx.setShipmentNo("LGS-1");
        wx.setIdentityKey("OS-1:1");
        wx.setChannel("wx-follow");
        wx.setState("DONE");
        LgsChannelSubscription kd = new LgsChannelSubscription();
        kd.setShipmentNo("LGS-1");
        kd.setIdentityKey("OS-1:1");
        kd.setChannel("kuaidi100");
        kd.setState("RETRYABLE");
        kd.setError("快递100暂时繁忙");
        when(channels.selectList(any())).thenReturn(List.of(wx, kd));
        port.setChannelRows(channels);

        var result = port.summariesOf(List.of("SUB-1", "SUB-2"));

        assertThat(result).containsOnlyKeys("SUB-1");
        assertThat(result.get("SUB-1").pending()).isTrue();
        assertThat(result.get("SUB-1").reason()).isEqualTo("快递100暂时繁忙");
        verify(waybills).selectList(any());
        verify(channels).selectList(any());
    }

    private static LgsWaybill waybill() {
        LgsWaybill w = new LgsWaybill();
        w.setShipmentNo("LGS-1");
        w.setBizRef("SUB-1");
        w.setSourceShipmentNo("OS-1");
        w.setSourceShipmentVersion(1L);
        w.setCarrier("STO");
        w.setWaybillNo("STO123");
        w.setStatus("CREATED");
        return w;
    }
}
