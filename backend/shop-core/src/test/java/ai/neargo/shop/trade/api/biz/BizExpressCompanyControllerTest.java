package ai.neargo.shop.trade.api.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.common.StoreMarkets;
import ai.neargo.shop.spi.fulfillment.ExpressPickupPort;
import ai.neargo.shop.spi.logistics.LogisticsPort;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 清单接口的两个能力位（TDD-商户App快递公司 E5、E10）。 */
class BizExpressCompanyControllerTest {
    private final StoreMarkets markets = mock(StoreMarkets.class);
    private final ExpressPickupPort pickup = mock(ExpressPickupPort.class);
    private final LogisticsPort logistics = mock(LogisticsPort.class);
    private final BizExpressCompanyController controller =
            new BizExpressCompanyController(markets, pickup, logistics);

    @BeforeEach
    void store() {
        BizContext.set(new BizContext("E1", Set.of(), Set.of()).withStore("S1"));
        when(pickup.carriers()).thenReturn(List.of("ZTO", "YTO", "SF"));
    }

    @AfterEach
    void clear() {
        BizContext.clear();
    }

    @Test
    @DisplayName("★★★ 台湾店：通道开着也不能叫快递 —— 通道的承运商全是大陆码，与台湾清单没有交集")
    void tw_store_reports_courier_pickup_unavailable() {
        when(markets.of("S1")).thenReturn("TW");
        when(pickup.enabled()).thenReturn(true);
        var list = controller.list();
        assertThat(list.market()).isEqualTo("TW");
        assertThat(list.courierPickupAvailable()).isFalse();
        assertThat(list.companies()).extracting(BizExpressCompanyController.Company::code)
                .startsWith("TCAT");
    }

    @Test
    @DisplayName("★★★ 大陆店：能不能叫快递只看通道开没开")
    void cn_store_reports_pickup_available_only_when_channel_enabled() {
        when(markets.of("S1")).thenReturn("CN");
        when(pickup.enabled()).thenReturn(false);
        assertThat(controller.list().courierPickupAvailable()).isFalse();
        when(pickup.enabled()).thenReturn(true);
        assertThat(controller.list().courierPickupAvailable()).isTrue();
    }

    @Test
    @DisplayName("★★ market 是回落后的值（StoreMarkets 给什么就报什么，App 不再自己猜）")
    void market_is_resolved_with_deploy_default() {
        when(markets.of("S1")).thenReturn("CN");
        assertThat(controller.list().market()).isEqualTo("CN");
    }

    @Test
    @DisplayName("★★ traceSupported 逐家取轨迹渠道覆盖")
    void trace_supported_comes_from_logistics_coverage() {
        when(markets.of("S1")).thenReturn("CN");
        when(logistics.traceCovers(anyString())).thenReturn(false);
        when(logistics.traceCovers("SF")).thenReturn(true);
        var companies = controller.list().companies();
        assertThat(companies.getFirst().traceSupported()).isTrue();
        assertThat(companies.get(1).traceSupported()).isFalse();
    }
}
