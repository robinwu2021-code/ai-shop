package ai.neargo.shop.trade.api.mp;

import ai.neargo.shop.spi.notify.WxUrlLinkPort;
import ai.neargo.shop.trade.service.OrderService;
import ai.neargo.shop.trade.track.ShipTrackToken;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MpTrackMiniLinkTest {
    @Test
    void validTokenUsesRecognizedHomeEntry() {
        ShipTrackToken token = new ShipTrackToken("unit-test-key", 5);
        String signed = token.sign("SUB-1");
        WxUrlLinkPort link = mock(WxUrlLinkPort.class);
        when(link.generate("pages/home/index", "trackToken=" + signed))
                .thenReturn(Optional.of("https://wxaurl.cn/test"));

        MpTrackController controller = new MpTrackController(token, mock(OrderService.class), link,
                "pages/home/index");
        assertThat(controller.miniLink(signed).url()).isEqualTo("https://wxaurl.cn/test");
        verify(link).generate(eq("pages/home/index"), eq("trackToken=" + signed));
        assertThat(controller.miniLink("invalid")).isNull();
    }
}
