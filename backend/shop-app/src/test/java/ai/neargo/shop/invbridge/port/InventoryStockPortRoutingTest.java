package ai.neargo.shop.invbridge.port;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.client.CircuitBreaker;
import ai.neargo.inventory.client.InventoryReservationApi;
import ai.neargo.inventory.client.InventoryReservationClient;
import ai.neargo.inventory.client.InventoryStockApi;
import ai.neargo.inventory.domain.service.InventoryAclService;
import ai.neargo.inventory.domain.service.InventoryAclService.OwnerRoute;
import ai.neargo.inventory.domain.service.LocationService;
import ai.neargo.inventory.domain.service.ReservationService;
import ai.neargo.inventory.domain.service.StockCountService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.product.StockPort.SkuQty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 传统下单路径（{@code StockPort}）按商家切流（ADR-046 M8）：一张父订单跨几个商家时，
 * 内嵌写的那几家走内嵌，已翻到服务的走客户端；服务上任何一家失败，已占的几家撤回。
 */
class InventoryStockPortRoutingTest {

    InventoryAclService acl;
    ReservationService reservations;
    StockCountService counts;
    LocationService locations;
    InventoryStockPort port;
    List<String> calls;
    Set<String> failingMerchants;

    @BeforeEach
    void setUp() {
        acl = Mockito.mock(InventoryAclService.class);
        reservations = Mockito.mock(ReservationService.class);
        counts = Mockito.mock(StockCountService.class);
        locations = Mockito.mock(LocationService.class);
        port = new InventoryStockPort(counts, reservations, acl, locations);
        calls = new ArrayList<>();
        failingMerchants = new java.util.HashSet<>();

        route("SKU-E", "OWN-E", "E-EMB", "EMBEDDED");
        route("SKU-S1", "OWN-S1", "E-SVC1", "SVC");
        route("SKU-S2", "OWN-S2", "E-SVC2", "SVC");
        route("SKU-F", "OWN-F", "E-FLIP", "FLIPPING");
        when(acl.ownerOfSku("SKU-E")).thenReturn("OWN-E");
        when(acl.anyServiceWrittenOwner()).thenReturn(true);

        port.setRemote(new ServiceStockRoute(InventoryReservationClient.of(recording(InventoryReservationApi.class),
                recording(InventoryStockApi.class), new CircuitBreaker(5, Duration.ofSeconds(10), Clock.systemUTC()))));
    }

    @Test
    void settingOnHandForASkuNotYetOnTheBooksIsANoOp() {
        // 新建商品：商品页写库存时 SKU 还没投影成物料（SkuUpserted 在后面）—— 不能拿空业主去建库位
        port.setOnHand("SKU-NEW", "STORE-1", 0, null);
        verify(locations, never()).defaultLocation(any());
        verify(counts, never()).adjustOne(any(), any(), any(), anyInt(), any(), any());
        assertThat(calls).isEmpty();
    }

    @Test
    void aParentOrderSpanningMerchantsIsSplitByWriter() {
        port.lock("ORD-1", List.of(new SkuQty("SKU-E", 1), new SkuQty("SKU-S1", 2, "STORE-1")));
        verify(reservations).reserve(eq("OWN-E"), eq("ORD-1"), any(), anyLong(), anyInt());
        assertThat(calls).containsExactly("reserve:E-SVC1:ORD-1:SKU-S1@STORE-1x2");

        port.setOnHand("SKU-S1", "STORE-1", 9, null);
        assertThat(calls).contains("setOnHand:E-SVC1:SKU-S1@STORE-1=9");
        verify(counts, never()).adjustOne(any(), any(), any(), anyInt(), any(), any());

        port.release("ORD-1");
        verify(reservations).releaseByRef("ORD-1");
        assertThat(calls).contains("releaseByRef:ORD-1");
    }

    @Test
    void aFailureOnOneServiceMerchantCancelsTheOnesAlreadyReserved() {
        failingMerchants.add("E-SVC2");
        assertThatThrownBy(() -> port.lock("ORD-2",
                List.of(new SkuQty("SKU-S1", 1), new SkuQty("SKU-S2", 1))))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.STOCK_SHORT_ITEMS));
        assertThat(calls).containsExactly("reserve:E-SVC1:ORD-2:SKU-S1@nullx1", "reserve:E-SVC2:ORD-2:SKU-S2@nullx1",
                "cancel:E-SVC1:ORD-2");
    }

    @Test
    void flippingMerchantsAreRefusedAndPureEmbeddedOrdersNeverCallTheService() {
        assertThatThrownBy(() -> port.lock("ORD-3", List.of(new SkuQty("SKU-F", 1))))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        when(acl.anyServiceWrittenOwner()).thenReturn(false);
        port.confirm("ORD-4");
        verify(reservations).commitByRef("ORD-4", "SYSTEM");
        assertThat(calls).isEmpty();
    }

    private void route(String sku, String owner, String entity, String authority) {
        when(acl.routeOfSku(sku)).thenReturn(new OwnerRoute(owner, entity, authority));
    }

    /** 记下每次调用；对 failingMerchants 里的商家抛缺货 */
    @SuppressWarnings("unchecked")
    private <T> T recording(Class<T> api) {
        return (T) Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api}, (proxy, m, args) -> {
            switch (m.getName()) {
                case "reserve" -> {
                    String merchant = (String) args[0];
                    var body = (InventoryReservationApi.Reserve) args[3];
                    var l = body.lines().getFirst();
                    calls.add("reserve:" + merchant + ":" + body.ref() + ":" + l.itemRef() + "@" + l.locationRef()
                            + "x" + l.qty());
                    if (failingMerchants.contains(merchant)) {
                        throw InventoryException.of(InventoryError.STOCK_SHORT, l.itemRef());
                    }
                    return new InventoryReservationApi.View(body.ref(), "R", "HELD", 1, false, List.of());
                }
                case "cancel" -> {
                    calls.add("cancel:" + args[0] + ":" + args[3]);
                    return new InventoryReservationApi.View((String) args[3], "R", "RELEASED", 1, false, List.of());
                }
                case "setOnHand" -> {
                    var b = (InventoryStockApi.OnHand) args[3];
                    calls.add("setOnHand:" + args[0] + ":" + b.itemRef() + "@" + b.locationRef() + "=" + b.onHand());
                    return Map.of();
                }
                case "releaseByRef" -> {
                    calls.add("releaseByRef:" + args[1]);
                    return Map.of("ref", args[1]);
                }
                default -> {
                    calls.add(m.getName());
                    return m.getReturnType() == Map.class ? Map.of() : null;
                }
            }
        });
    }
}
