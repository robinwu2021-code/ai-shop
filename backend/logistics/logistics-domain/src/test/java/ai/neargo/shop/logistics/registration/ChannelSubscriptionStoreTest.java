package ai.neargo.shop.logistics.registration;

import ai.neargo.shop.logistics.entity.LgsChannelSubscription;
import ai.neargo.shop.logistics.entity.LgsWaybill;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.ChannelSubscriptionMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChannelSubscriptionStoreTest {
    @Test
    void oneShipmentKeepsIndependentWechatAndKuaidi100Subscriptions() {
        ChannelSubscriptionMapper mapper = mock(ChannelSubscriptionMapper.class);
        List<LgsChannelSubscription> inserted = new ArrayList<>();
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(any(LgsChannelSubscription.class))).thenAnswer(invocation -> {
            inserted.add(invocation.getArgument(0));
            return 1;
        });
        ChannelSubscriptionStore store = new ChannelSubscriptionStore(mapper);
        LgsWaybill shipment = new LgsWaybill();
        shipment.setShipmentNo("SH1");
        shipment.setSourceShipmentNo("OS1");
        shipment.setSourceShipmentVersion(1L);
        shipment.setCarrier("YTO");
        shipment.setWaybillNo("YT1");

        store.ensurePending(shipment, "wx-follow");
        store.ensurePending(shipment, "kuaidi100");

        assertThat(inserted).extracting(LgsChannelSubscription::getChannel)
                .containsExactly("wx-follow", "kuaidi100");
        assertThat(inserted).extracting(LgsChannelSubscription::getIdentityKey)
                .containsOnly("OS1:1");
        assertThat(inserted).extracting(LgsChannelSubscription::getState)
                .containsOnly("PENDING");
    }

    @Test
    void resultUpdatesOnlyTheNamedChannel() {
        ChannelSubscriptionMapper mapper = mock(ChannelSubscriptionMapper.class);
        ChannelSubscriptionStore store = new ChannelSubscriptionStore(mapper);
        LgsChannelSubscription wx = new LgsChannelSubscription();
        wx.setId(7L);
        wx.setShipmentNo("SH1");
        wx.setIdentityKey("OS1:1");
        wx.setChannel("wx-follow");
        when(mapper.selectOne(any())).thenReturn(wx);

        store.mark("SH1", "OS1:1", "wx-follow", "DONE", "TOKEN", null);

        verify(mapper).update(org.mockito.ArgumentMatchers.isNull(), any());
    }

    @Test
    void correctionGetsNewIdentityWithoutChangingHistoricalSubscription() {
        LgsWaybill first = new LgsWaybill();
        first.setShipmentNo("SH1");
        first.setSourceShipmentNo("OS1");
        first.setSourceShipmentVersion(1L);
        first.setCarrier("YTO");
        first.setWaybillNo("YT1");
        LgsWaybill corrected = new LgsWaybill();
        corrected.setShipmentNo("SH1");
        corrected.setSourceShipmentNo("OS2");
        corrected.setSourceShipmentVersion(2L);
        corrected.setCarrier("YTO");
        corrected.setWaybillNo("YT2");

        assertThat(ChannelSubscriptionStore.identityKey(first)).isEqualTo("OS1:1");
        assertThat(ChannelSubscriptionStore.identityKey(corrected)).isEqualTo("OS2:2");
    }
}
