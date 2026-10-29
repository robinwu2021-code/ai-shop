package ai.neargo.shop.invbridge;

import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.invbridge.port.InventoryStockPort;
import ai.neargo.inventory.domain.service.InventoryAclService;
import ai.neargo.inventory.domain.service.LocationService;
import ai.neargo.inventory.domain.service.ReservationService;
import ai.neargo.shop.spi.product.StockPort.SkuQty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 一张父单跨两个商家时，镜像补记要按 SKU 的业主分组记。
 *
 * <p>锁号是父订单号，一次锁货覆盖这张单里所有商家的行。此前镜像拿<b>第一行</b>的业主
 * 记全部行：B 商家的货被记进 A 商家的账（A 的库位里根本没有这个条目，或者有同名的另一件货），
 * B 自己的账上那笔预留不存在 —— 双写对差时 A 多扣、B 少扣，两边各自看起来都像真的。
 * 直连那条路（{@code InventoryStockPort.lock}）早就按业主分组了，两条路必须一致。
 */
class MirrorMultiOwnerTest {

    private final ReservationService reservations = mock(ReservationService.class);
    private final InventoryAclService acl = mock(InventoryAclService.class);
    private final LocationService locations = mock(LocationService.class);
    private InventoryMirrorConsumer consumer;

    @BeforeEach
    void setUp() {
        when(acl.ownerOfSku("SA")).thenReturn("OWN_A");
        when(acl.ownerOfSku("SB")).thenReturn("OWN_B");
        when(acl.itemIdOfSku("SA")).thenReturn("IT_A");
        when(acl.itemIdOfSku("SB")).thenReturn("IT_B");
        when(acl.locationOfStore(anyString(), org.mockito.ArgumentMatchers.any())).thenAnswer(i -> "LOC_" + i.getArgument(0));
        when(locations.resolveStockLocation(anyString(), anyString())).thenAnswer(i -> i.getArgument(1));
        consumer = new InventoryMirrorConsumer(null, reservations, acl, locations, new ObjectMapper());
    }

    private void consume(String type, String payload) {
        SysOutbox event = new SysOutbox();
        event.setEventType(type);
        event.setPayload(payload);
        consumer.consume(event);
    }

    @Test
    @DisplayName("★★★ 两个商家的父单：预留按业主各记各的，B 的货不进 A 的账")
    void reserveGroupsByOwner() {
        consume(InvMirrorEvent.RESERVE, "{\"ref\":\"P1\",\"items\":["
                + "{\"skuNo\":\"SA\",\"qty\":2},{\"skuNo\":\"SB\",\"qty\":3}]}");

        verify(reservations).reserve(eq("OWN_A"), eq("P1"),
                eq(List.of(new ReservationService.Line("IT_A", "LOC_OWN_A", 2, null))), anyLong(), anyInt());
        verify(reservations).reserve(eq("OWN_B"), eq("P1"),
                eq(List.of(new ReservationService.Line("IT_B", "LOC_OWN_B", 3, null))), anyLong(), anyInt());
        verify(reservations, never()).reserve(eq("OWN_A"), eq("P1"),
                eq(List.of(new ReservationService.Line("IT_A", "LOC_OWN_A", 2, null),
                        new ReservationService.Line("IT_B", "LOC_OWN_A", 3, null))), anyLong(), anyInt());
    }

    @Test
    @DisplayName("★★ 回补（restore）同样按业主分组 —— 镜像与直连两条路")
    void restoreGroupsByOwner() {
        consume(InvMirrorEvent.RESTORE, "{\"ref\":\"AS1\",\"items\":["
                + "{\"skuNo\":\"SA\",\"qty\":1},{\"skuNo\":\"SB\",\"qty\":4}]}");
        verify(reservations).restore("OWN_A", "AS1",
                List.of(new ReservationService.Line("IT_A", "LOC_OWN_A", 1, null)), "MIRROR");
        verify(reservations).restore("OWN_B", "AS1",
                List.of(new ReservationService.Line("IT_B", "LOC_OWN_B", 4, null)), "MIRROR");

        InventoryStockPort port = new InventoryStockPort(null, reservations, acl, locations);
        port.restore("AS2", List.of(new SkuQty("SA", 1), new SkuQty("SB", 4)));
        verify(reservations).restore("OWN_A", "AS2",
                List.of(new ReservationService.Line("IT_A", "LOC_OWN_A", 1, null)), "SYSTEM");
        verify(reservations).restore("OWN_B", "AS2",
                List.of(new ReservationService.Line("IT_B", "LOC_OWN_B", 4, null)), "SYSTEM");
    }
}
