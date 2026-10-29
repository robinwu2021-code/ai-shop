package ai.neargo.shop.pay.channel.legacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.pay.channel.PayGateway;
import ai.neargo.shop.pay.channel.api.AccessMode;
import ai.neargo.shop.pay.channel.api.ChannelAccountContext;
import ai.neargo.shop.pay.channel.api.ChannelGateway;
import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.PayScene;
import ai.neargo.shop.pay.channel.capability.ChannelCapabilityRegistry;
import ai.neargo.shop.pay.channel.capability.OnlinePayCapability;
import ai.neargo.shop.pay.channel.context.ChannelContextFactory;
import ai.neargo.shop.pay.channel.context.ChannelRoute;
import ai.neargo.shop.pay.channel.handler.ChannelHandlerRegistry;
import ai.neargo.shop.pay.channel.handler.ChannelProductCatalog;
import ai.neargo.shop.pay.channel.platform.InProcessChannel;
import ai.neargo.shop.spi.pay.PayInstruction;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FrozenAccountDispatchTest {
    private final ChannelGateway gateway = mock(ChannelGateway.class);
    private final ChannelCapabilityRegistry capabilities = mock(ChannelCapabilityRegistry.class);
    private final OnlinePayCapability online = mock(OnlinePayCapability.class);
    private final ChannelRoute route = new ChannelRoute("WECHAT", AccessMode.MERCHANT_OWN, "CN", "CNY", 17L,
            "PM-17", "SUB-17", 7);
    private final LegacyChannelBridge bridge = new LegacyChannelBridge(gateway, mock(ChannelHandlerRegistry.class),
            capabilities, new ChannelContextFactory(capabilities, mock(ChannelProductCatalog.class)));

    @BeforeEach
    void capability() {
        when(capabilities.find("WECHAT", AccessMode.MERCHANT_OWN, OnlinePayCapability.class))
                .thenReturn(Optional.of(online));
        when(online.onlineScenes()).thenReturn(Set.of(PayScene.ONLINE_SDK));
    }

    private PayGateway.PrepayCommand command(String coin, String sub) {
        return new PayGateway.PrepayCommand("OUT", 1990L, coin, "Goods", "JSAPI", sub, "OPEN");
    }

    @Test
    void unknownSigningAccountCannotFallBackToTheDeploymentAccount() {
        var result = bridge.prepayRouted(route, command("CNY", "SUB-17"));
        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo("ACQUIRING_ACCOUNT_UNVERIFIED");
        verifyNoInteractions(gateway);
    }

    @Test
    void dispatchRechecksProofEvenAfterAnEarlierAdmission() {
        when(online.supportsFrozenAccount(route)).thenReturn(true, false);
        assertThat(bridge.supportsRoutedAccount(route)).isTrue();
        assertThat(bridge.prepayRouted(route, command("CNY", "SUB-17")).success()).isFalse();
        verifyNoInteractions(gateway);
    }

    @Test
    void missingFrozenKeyVersionCannotUseTheCurrentKey() {
        var unknown = new ChannelRoute("WECHAT", AccessMode.MERCHANT_OWN, "CN", "CNY", 17L, "PM-17", "SUB-17", null);
        assertThat(bridge.prepayRouted(unknown, command("CNY", "SUB-17")).success()).isFalse();
        verifyNoInteractions(gateway);
    }

    @Test
    void commandCannotChangeTheFrozenSubMerchantOrCurrency() {
        when(online.supportsFrozenAccount(route)).thenReturn(true);
        assertThat(bridge.prepayRouted(route, command("USD", "SUB-17")).success()).isFalse();
        assertThat(bridge.prepayRouted(route, command("CNY", "OTHER")).success()).isFalse();
        verifyNoInteractions(gateway);
    }

    @Test
    void anAccountProvingHandlerReceivesExactlyTheFrozenIdentity() {
        when(online.supportsFrozenAccount(route)).thenReturn(true);
        when(gateway.execute(any(), any())).thenReturn(new ChannelResult.Pending(
                new PayInstruction.JsapiParams(Map.of("prepayId", "SIGNED")), null, "OK"));
        assertThat(bridge.prepayRouted(route, command("CNY", "SUB-17")).success()).isTrue();
        var context = ArgumentCaptor.forClass(ChannelAccountContext.class);
        verify(gateway).execute(context.capture(), any());
        assertThat(context.getValue().market()).isEqualTo("CN");
        assertThat(context.getValue().accessMode()).isEqualTo(AccessMode.MERCHANT_OWN);
        assertThat(context.getValue().payMerchantId()).isEqualTo(17L);
        assertThat(context.getValue().payMerchantNo()).isEqualTo("PM-17");
        assertThat(context.getValue().subMerchantId()).isEqualTo("SUB-17");
        assertThat(context.getValue().keyVersion()).isEqualTo(7);
    }

    @Test
    void aStatelessStubDoesNotProveARealSigningAccount() {
        assertThat(new InProcessChannel(mock(PayGateway.class)).supportsFrozenAccount(route)).isFalse();
    }
}
