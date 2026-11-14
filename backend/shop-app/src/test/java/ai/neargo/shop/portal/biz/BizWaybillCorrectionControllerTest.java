package ai.neargo.shop.portal.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.common.StoreMarkets;
import ai.neargo.shop.spi.logistics.LogisticsAdminPort;
import ai.neargo.shop.spi.logistics.LogisticsPort;
import ai.neargo.shop.trade.dto.OrderVO;
import ai.neargo.shop.trade.service.MerchantOrderService;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * 改运单不因承运商校验而失败（TDD-商户App快递公司 E9）：不改承运商放行、清单内的值归一、
 * 清单外的新值照旧写入并记 WARN —— 拒了就是已发货的订单改不了运单。
 */
class BizWaybillCorrectionControllerTest {
    private final MerchantOrderService orders = mock(MerchantOrderService.class);
    private final LogisticsAdminPort admin = mock(LogisticsAdminPort.class);
    private final LogisticsPort logistics = mock(LogisticsPort.class);
    private final StoreMarkets markets = mock(StoreMarkets.class);
    private final BizWaybillCorrectionController controller =
            new BizWaybillCorrectionController(orders, admin, logistics, markets);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void setUp() {
        BizContext.set(new BizContext("E1", Set.of(), Set.of()).withStore("S1"));
        OrderVO order = mock(OrderVO.class);
        when(order.fulfillment()).thenReturn("EXPRESS");
        when(order.status()).thenReturn("FULFILLING");
        // 存量单：当前承运商不在任何清单里（自由文本时代写进去的）
        when(order.expressCompany()).thenReturn("黑猫");
        when(order.store()).thenReturn(new OrderVO.StoreBrief("S1", null));
        when(orders.detail("E1", "S1", "SUB1")).thenReturn(order);
        LogisticsAdminPort.Shipment shipment = mock(LogisticsAdminPort.Shipment.class);
        when(shipment.shipmentNo()).thenReturn("LGS1");
        when(shipment.orderNo()).thenReturn("SUB1");
        when(shipment.entityNo()).thenReturn("E1");
        when(shipment.storeNo()).thenReturn("S1");
        when(admin.list(any())).thenReturn(new LogisticsAdminPort.ShipmentPage(List.of(shipment), 1));
        when(logistics.syncAfterShip("SUB1")).thenReturn(new LogisticsPort.SyncResult("PENDING", null, null, true, ""));
        when(markets.of("S1")).thenReturn("TW");
        logs.start();
        ((Logger) LoggerFactory.getLogger(BizWaybillCorrectionController.class)).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(BizWaybillCorrectionController.class)).detachAppender(logs);
        BizContext.clear();
    }

    @Test
    @DisplayName("★★★ 承运商与当前相同（即便不在清单里）→ 放行，不记 WARN")
    void unchanged_carrier_passes_even_if_not_in_list() {
        controller.correct("SUB1", new BizWaybillCorrectionController.Correction("NO2", " 黑猫 ", "单号填错"));
        verify(admin).changeWaybill(eq("LGS1"), eq("黑猫"), eq("NO2"), eq("单号填错"), eq(Set.of("E1")));
        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("★★ 不传承运商 → 交给运单沿用原值")
    void blank_carrier_keeps_current() {
        controller.correct("SUB1", new BizWaybillCorrectionController.Correction("NO2", null, "单号填错"));
        verify(admin).changeWaybill(eq("LGS1"), eq(null), eq("NO2"), eq("单号填错"), eq(Set.of("E1")));
        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("★★★ 改成本市场清单里的名称 → 归一成码写入")
    void listed_name_is_normalized() {
        controller.correct("SUB1", new BizWaybillCorrectionController.Correction("NO2", "新竹物流", "换快递"));
        verify(admin).changeWaybill(eq("LGS1"), eq("HCT"), eq("NO2"), eq("换快递"), eq(Set.of("E1")));
        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("★★★ 改成清单外的新值 → 照旧写入，记 WARN，不拒")
    void unknown_new_carrier_is_written_and_warned_not_rejected() {
        controller.correct("SUB1", new BizWaybillCorrectionController.Correction("NO2", "顺丰速运", "换快递"));
        verify(admin).changeWaybill(eq("LGS1"), eq("顺丰速运"), eq("NO2"), eq("换快递"), eq(Set.of("E1")));
        assertThat(warnings()).singleElement().asString().contains("SUB1").contains("S1").contains("顺丰速运");
    }

    private List<String> warnings() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
