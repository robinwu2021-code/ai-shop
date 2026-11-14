package ai.neargo.shop.invbridge;

import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.invbridge.port.InventoryStockPort;
import ai.neargo.inventory.domain.service.InventoryAclService;
import ai.neargo.inventory.domain.service.LocationService;
import ai.neargo.inventory.domain.service.ReservationService;
import ai.neargo.shop.spi.product.StockPort.SkuQty;
import ai.neargo.shop.trade.service.CloseRuleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 进销存预留的存活时长必须盖住「未支付自动关单」的窗口。
 *
 * <p>此前两处都写死 30 分钟，而关单时长运营可配到 1440 分钟：配成 2 小时的话，
 * 下单 30 分钟后预留就被回收、货被别人买走，买家在第 90 分钟照样付款成功 ——
 * 付了钱的单没有货。
 */
class ReservationHoldTtlTest {

    private static ReservationHoldTtl ttl(int unpaidMinutes, long maxTtlSeconds) {
        CloseRuleService rules = mock(CloseRuleService.class);
        when(rules.unpaidMinutes()).thenReturn(unpaidMinutes);
        return new ReservationHoldTtl(rules, maxTtlSeconds);
    }

    @Test
    @DisplayName("★★★ 存活时长 = 关单窗口 + 余量；封顶于预留上限")
    void derivedFromCloseWindowAndCapped() {
        assertThat(ttl(15, 86_400).seconds())
                .isEqualTo(15 * 60 + ReservationHoldTtl.MARGIN_SECONDS);
        assertThat(ttl(120, 86_400).seconds())
                .as("关单配成 2 小时，预留却只活 30 分钟 = 还能付款的单先丢了货")
                .isGreaterThan(120 * 60L);
        assertThat(ttl(1440, 86_400).seconds())
                .as("超过预留上限进销存会直接拒（BAD_REQUEST），下单就挂了 —— 必须封顶")
                .isEqualTo(86_400);
    }

    @Test
    @DisplayName("★★★ 镜像补记与进销存直连两处都按同一个时长预留")
    void bothReservePathsUseDerivedTtl() {
        ReservationHoldTtl ttl = ttl(120, 86_400);
        long expected = ttl.seconds();
        ReservationService reservations = mock(ReservationService.class);
        InventoryAclService acl = mock(InventoryAclService.class);
        LocationService locations = mock(LocationService.class);
        when(acl.ownerOfSku(anyString())).thenReturn("OWN");
        when(acl.itemIdOfSku(anyString())).thenAnswer(i -> "IT-" + i.getArgument(0));
        when(locations.resolveStockLocation(any(), any())).thenReturn("LOC");

        InventoryMirrorConsumer consumer = new InventoryMirrorConsumer(null, reservations, acl, locations,
                new ObjectMapper());
        consumer.setHoldTtl(ttl);
        SysOutbox event = new SysOutbox();
        event.setEventType(InvMirrorEvent.RESERVE);
        event.setPayload("{\"ref\":\"O1\",\"items\":[{\"skuNo\":\"S1\",\"qty\":2}]}");
        consumer.consume(event);
        verify(reservations).reserve(eq("OWN"), eq("O1"), anyList(), eq(expected), anyInt());

        InventoryStockPort port = new InventoryStockPort(null, reservations, acl, locations);
        port.setHoldTtl(ttl);
        port.lock("O2", List.of(new SkuQty("S1", 1)));
        verify(reservations).reserve(eq("OWN"), eq("O2"), anyList(), eq(expected), anyInt());
    }
}
