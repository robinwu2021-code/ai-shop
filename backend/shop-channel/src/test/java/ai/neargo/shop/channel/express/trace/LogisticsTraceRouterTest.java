package ai.neargo.shop.channel.express.trace;

import ai.neargo.shop.spi.logistics.TraceProvider;
import ai.neargo.shop.spi.logistics.TraceResult;
import ai.neargo.shop.spi.logistics.TraceStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 轨迹路由（TDD-圆通物流直连 §4.2、§8）。守的是「多方式并存」真的按承运商分流、且回落不白屏。
 */
class LogisticsTraceRouterTest {

    /** 一个只认指定承运商的假 provider */
    private static TraceProvider provider(String name, String carrier, boolean available) {
        return new TraceProvider() {
            public String name() {
                return name;
            }

            public boolean covers(String c) {
                return carrier == null || carrier.equals(c);
            }

            public boolean available() {
                return available;
            }

            public Optional<TraceResult> trace(String c, String waybillNo) {
                return Optional.of(new TraceResult(waybillNo, c, TraceStatus.IN_TRANSIT, name, List.of()));
            }
        };
    }

    private static TraceRoutingProperties routing(Map<String, String> storeRoute, String def) {
        var r = new TraceRoutingProperties();
        r.setStoreRoute(storeRoute);
        r.setDefaultProvider(def);
        return r;
    }

    @Test
    @DisplayName("★★★ 按门店分流：A 店→圆通直连、没配的店→默认（并存的核心）")
    void routesByStore() {
        var router = new LogisticsTraceRouter(
                List.of(provider("yto", "YTO", true), provider("kuaidi100", null, true)),
                routing(Map.of("ST-A", "yto"), "kuaidi100"));

        // A 店配了圆通直连
        assertThat(router.trace("ST-A", "YTO", "WB1")).get()
                .extracting(TraceResult::provider).isEqualTo("yto");
        // 没配的店走默认（这里默认是聚合）
        assertThat(router.trace("ST-B", "YTO", "WB2")).get()
                .extracting(TraceResult::provider).isEqualTo("kuaidi100");
    }

    @Test
    @DisplayName("★★★ provider 不可用（缺凭据）→ 回落空，不抛、不白屏")
    void unavailableProviderFallsBackToEmpty() {
        var router = new LogisticsTraceRouter(
                List.of(provider("yto", "YTO", false), provider("stub", null, true)),
                routing(Map.of("ST-A", "yto"), "stub"));
        // 门店 ST-A 路由到 yto，但 yto 没配凭据 → 空（不去借别的：路由指谁就是谁，不可用就是暂无）
        assertThat(router.trace("ST-A", "YTO", "WB1")).isEmpty();
    }

    @Test
    @DisplayName("★★ 路由指了不存在的 provider / provider 不认该承运商 → 回落空")
    void misconfigFallsBackToEmpty() {
        var router1 = new LogisticsTraceRouter(
                List.of(provider("stub", null, true)),
                routing(Map.of("ST-A", "yto"), "stub"));   // yto 没注册
        assertThat(router1.trace("ST-A", "YTO", "WB1")).isEmpty();

        var router2 = new LogisticsTraceRouter(
                List.of(provider("yto", "YTO", true)),
                routing(Map.of("ST-A", "yto"), "yto"));    // A 店走圆通直连，却来了个申通单
        assertThat(router2.trace("ST-A", "STO", "WB1")).isEmpty();
    }

    @Test
    @DisplayName("★ 同名 provider = 装配错误，构造即抛（不靠注册顺序决定路由指谁）")
    void duplicateNameThrows() {
        try {
            new LogisticsTraceRouter(List.of(provider("dup", "A", true), provider("dup", "B", true)),
                    routing(Map.of(), "dup"));
            assertThat(false).as("同名应当抛").isTrue();
        } catch (IllegalStateException e) {
            assertThat(e.getMessage()).contains("同名");
        }
    }
}
