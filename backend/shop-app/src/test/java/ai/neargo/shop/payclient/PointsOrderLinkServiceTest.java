package ai.neargo.shop.payclient;
import ai.neargo.shop.trade.order.points.OrderPointsBindingService;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class PointsOrderLinkServiceTest {
    @Test void links_require_same_ledger_holder_and_never_guess_missing_bindings() {
        var bindings=mock(OrderPointsBindingService.class);
        var binding=mock(OrderPointsBindingService.Binding.class);
        var admission=mock(OrderPointsBindingService.Admission.class);
        when(bindings.find("S")).thenReturn(binding);when(binding.admission()).thenReturn(admission);
        when(admission.userNo()).thenReturn("U");when(admission.storeNo()).thenReturn("ST");
        var links=new PointsOrderLinkService(bindings);
        assertThat(links.forUser("S","U")).isEqualTo("S");assertThat(links.forStore("S","ST")).isEqualTo("S");
        assertThat(links.forUser("S","OTHER")).isNull();assertThat(links.forStore("S","OTHER")).isNull();
        assertThat(links.forUser("MISSING","U")).isNull();
    }
}
