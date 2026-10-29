package ai.neargo.shop.invbridge.port;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.client.CircuitBreaker;
import ai.neargo.inventory.client.InventoryReservationApi;
import ai.neargo.inventory.client.InventoryReservationClient;
import ai.neargo.inventory.domain.service.InventoryAclService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.inventory.InventoryReservationPort;
import ai.neargo.shop.spi.inventory.InventoryReservationPort.Line;
import ai.neargo.shop.spi.inventory.InventoryReservationPort.Scope;
import ai.neargo.shop.spi.inventory.InventoryReservationPort.Snapshot;
import ai.neargo.shop.spi.inventory.InventoryReservationPort.State;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商城切流的路由（ADR-046 M8 T8.3）：商家的账由谁写，预留就交给谁；
 * 走服务的那一支按 SKU 号 / 门店号调用，错误码与内嵌时期同一套翻译，服务不可用即拒单。
 */
class RoutingInventoryReservationPortTest {

    private static final Scope SCOPE = new Scope("MAIN", "E100", "ORD-1");
    private static final List<Line> LINES = List.of(new Line("SKU-1", "STORE-1", 2, "F-1"));

    @Test
    void eachMerchantGoesToItsCurrentWriter() {
        InventoryAclService acl = Mockito.mock(InventoryAclService.class);
        InventoryReservationPort embedded = Mockito.mock(InventoryReservationPort.class);
        InventoryReservationPort service = Mockito.mock(InventoryReservationPort.class);
        RoutingInventoryReservationPort port = new RoutingInventoryReservationPort(acl, embedded, service);

        // 没开过账、内嵌写：与切流前一样
        when(acl.writeAuthorityOf("E100")).thenReturn(null);
        port.query(SCOPE);
        when(acl.writeAuthorityOf("E100")).thenReturn("EMBEDDED");
        port.reserve(SCOPE, LINES, 900, 1);
        verify(embedded).query(SCOPE);
        verify(embedded).reserve(SCOPE, LINES, 900, 1);
        verify(service, never()).reserve(SCOPE, LINES, 900, 1);

        // 已翻到服务：走客户端
        when(acl.writeAuthorityOf("E100")).thenReturn("SVC");
        port.reserve(SCOPE, LINES, 900, 1);
        verify(service).reserve(SCOPE, LINES, 900, 1);

        // 翻转对账中：拒单
        when(acl.writeAuthorityOf("E100")).thenReturn("FLIPPING");
        assertThatThrownBy(() -> port.reserve(SCOPE, LINES, 900, 1))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void flippedMerchantWithoutAConfiguredServiceIsRefusedNotSilentlyEmbedded() {
        InventoryAclService acl = Mockito.mock(InventoryAclService.class);
        InventoryReservationPort embedded = Mockito.mock(InventoryReservationPort.class);
        when(acl.writeAuthorityOf("E100")).thenReturn("SVC");
        RoutingInventoryReservationPort port = new RoutingInventoryReservationPort(acl, embedded, null);
        assertThatThrownBy(() -> port.reserve(SCOPE, LINES, 900, 1)).isInstanceOf(BizException.class);
        verify(embedded, never()).reserve(SCOPE, LINES, 900, 1);
    }

    @Test
    void servicePortSendsShopReferencesAndTranslatesErrors() {
        List<InventoryReservationApi.Reserve> sent = new ArrayList<>();
        List<String> owners = new ArrayList<>();
        Supplier<InventoryReservationApi.View>[] answer = new Supplier[]{() -> view("HELD")};
        InventoryReservationApi api = new InventoryReservationApi() {
            public View reserve(String o, String k, String a, Reserve b) {
                owners.add(o);
                sent.add(b);
                return answer[0].get();
            }
            public View query(String o, String r) { return answer[0].get(); }
            public View acceptUnpaid(String o, String k, String a, String r) { return answer[0].get(); }
            public View allocatePaid(String o, String k, String a, String r) { return answer[0].get(); }
            public View issue(String o, String k, String a, String r, Issue b) { return answer[0].get(); }
            public View cancel(String o, String k, String a, String r) { return answer[0].get(); }
        };
        ServiceInventoryReservationPort port = new ServiceInventoryReservationPort(InventoryReservationClient.of(api,
                new CircuitBreaker(5, Duration.ofSeconds(10), Clock.systemUTC())));

        Snapshot held = port.reserve(SCOPE, LINES, 900, 2);
        assertThat(owners).containsExactly("E100");
        assertThat(sent.getFirst().ref()).isEqualTo("ORD-1");
        assertThat(sent.getFirst().lifecycleVersion()).isEqualTo(2);
        assertThat(sent.getFirst().lines().getFirst().itemRef()).isEqualTo("SKU-1");
        assertThat(sent.getFirst().lines().getFirst().locationRef()).isEqualTo("STORE-1");
        assertThat(held.state()).isEqualTo(State.HELD);
        assertThat(held.originalLines().getFirst().skuNo()).isEqualTo("SKU-1");

        // 缺货：与内嵌时期同一个商城码
        answer[0] = () -> { throw InventoryException.of(InventoryError.STOCK_SHORT, "ITEM-1"); };
        assertThatThrownBy(() -> port.reserve(SCOPE, LINES, 900, 1))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.STOCK_SHORT_ITEMS));
        // 服务不可用：拒单
        answer[0] = () -> { throw InventoryException.of(InventoryError.UNAVAILABLE, "TIMEOUT"); };
        assertThatThrownBy(() -> port.reserve(SCOPE, LINES, 900, 1))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
        // 没有这笔预留：ABSENT，与内嵌一致
        answer[0] = () -> { throw InventoryException.of(InventoryError.NOT_FOUND, "reservation"); };
        assertThat(port.query(SCOPE).state()).isEqualTo(State.ABSENT);
        // 非主租户：与内嵌同样拒
        assertThatThrownBy(() -> port.query(new Scope("T2", "E100", "ORD-1"))).isInstanceOf(BizException.class);
    }

    private static InventoryReservationApi.View view(String status) {
        return new InventoryReservationApi.View("ORD-1", "RS1", status, 2, false,
                List.of(new InventoryReservationApi.Line("I1", "L1", "SKU-1", null, 2, null)));
    }
}
