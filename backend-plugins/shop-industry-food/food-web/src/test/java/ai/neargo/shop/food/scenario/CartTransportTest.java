package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.api.biz.BizCartController;
import ai.neargo.shop.food.dto.AuthorizeCartSessionReq;
import ai.neargo.shop.food.dto.CartResultVO;
import ai.neargo.shop.food.dto.CartSessionGrantVO;
import ai.neargo.shop.food.dto.CartVO;
import ai.neargo.shop.food.dto.CartVersionReq;
import ai.neargo.shop.food.dto.CartVersionVO;
import ai.neargo.shop.food.dto.OperationResultVO;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.dto.OrderLineInput;
import ai.neargo.shop.food.dto.ReplaceCartLineReq;
import ai.neargo.shop.food.foodclient.RemoteCheckCartService;
import ai.neargo.shop.food.spi.CartInternalApi;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class CartTransportTest {
    @Test void everyStaffWriteNeedsVerifyAndReadsNeedOrderView() {
        for (var method : BizCartController.class.getDeclaredMethods()) {
            var rule = method.getAnnotation(PreAuthorize.class);
            if (rule != null) {
                assertThat(rule.value()).contains(method.getName().equals("getCart")
                        ? BizPerms.ORDER_VIEW : BizPerms.VERIFY);
            }
        }
        assertThat(BizCartController.class.getDeclaredMethods()).filteredOn(m ->
                m.getAnnotation(PreAuthorize.class) != null).hasSize(6);
    }

    @Test void remoteRejectsForeignCartAndSkippedCasButAcceptsOriginalReplay() {
        var api = mock(CartInternalApi.class);
        var service = new RemoteCheckCartService(api);
        var lookup = new CartInternalApi.Identity("E", "S", "C");
        when(api.get(lookup)).thenReturn(new CartResultVO(new CartVO("OTHER", "S", null, "G", null,
                "OPEN", "QR", "DINE_IN", null, 0L, List.of())));
        assertThatThrownBy(() -> service.getCart("E", "S", "C")).isInstanceOf(IllegalStateException.class);
        var req = new CartVersionReq(3L);
        var command = new CartInternalApi.ArchiveLineCommand("E", "S", "C", "L", "A", "K", req);
        when(api.archiveLine(command)).thenReturn(new CartVersionVO("C", 5L));
        assertThatThrownBy(() -> service.archiveLine("E", "S", "C", "L", "A", req, "K"))
                .isInstanceOf(IllegalStateException.class);
        when(api.archiveLine(command)).thenReturn(new CartVersionVO("C", 4L));
        assertThat(service.archiveLine("E", "S", "C", "L", "A", req, "K").version()).isEqualTo(4L);
        assertThat(service.archiveLine("E", "S", "C", "L", "A", req, "K").version()).isEqualTo(4L);
    }

    @Test void receiptCannotSwitchStoreSessionGrantOrArchiveResult() {
        var api = mock(CartInternalApi.class);
        var service = new RemoteCheckCartService(api);
        when(api.get(new CartInternalApi.Identity("E", "S", "C")))
                .thenReturn(new CartResultVO(new CartVO("C", "FOREIGN", "CHECK", null, null,
                        "OPEN", "POS", "DINE_IN", null, 0L, List.of())));
        assertThatThrownBy(() -> service.getCart("E", "S", "C")).isInstanceOf(IllegalStateException.class);
        var grant = new AuthorizeCartSessionReq("G", 3L);
        when(api.authorize(new CartInternalApi.AuthorizeCommand("E", "S", "CHECK", "A", "K", grant)))
                .thenReturn(new CartSessionGrantVO("FOREIGN", "CHECK", 4L));
        assertThatThrownBy(() -> service.authorizeSession("E", "S", "CHECK", "A", grant, "K"))
                .isInstanceOf(IllegalStateException.class);
        var req = new CartVersionReq(3L);
        when(api.archive(new CartInternalApi.ArchiveCommand("E", "S", "C", "A", "K", req)))
                .thenReturn(new OperationResultVO(new OperationVO("OP", "SUCCEEDED", 4L, "FOREIGN")));
        assertThatThrownBy(() -> service.archiveCart("E", "S", "C", "A", req, "K"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void replaceCannotChangeSourceLineIdentityOnWire() {
        var api = mock(CartInternalApi.class);
        var service = new RemoteCheckCartService(api);
        var req = new ReplaceCartLineReq(new OrderLineInput("OTHER", "M", "MI", "R", "G", "SKU",
                BigDecimal.ONE, null, null, List.of(), List.of()), 0L);
        assertThatThrownBy(() -> service.replaceLine("E", "S", "C", "L", "A", req, "K"))
                .isInstanceOf(BizException.class);
        verifyNoInteractions(api);
    }
}
