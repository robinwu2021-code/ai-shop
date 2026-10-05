package ai.neargo.shop.channel.express.trace.port;

import ai.neargo.shop.channel.express.trace.TraceRoutingProperties;
import ai.neargo.shop.spi.logistics.LogisticsTracePort;
import ai.neargo.shop.spi.logistics.TraceProvider;
import ai.neargo.shop.spi.logistics.TraceResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@link LogisticsTracePort} 的实现：**按承运商把查询路由到对应的 {@link TraceProvider}**。
 *
 * <p>所有 provider 一起注册（Spring 注 {@code List<TraceProvider>}），这里按
 * {@link TraceRoutingProperties} 的路由表挑一个。加 provider、改路由，调用方一行不变 ——
 * 这就是「多方式并存」（TDD-圆通物流直连 §4.2、§7）。
 */
@Component
public class LogisticsTraceRouter implements LogisticsTracePort {

    private static final Logger log = LoggerFactory.getLogger(LogisticsTraceRouter.class);

    private final Map<String, TraceProvider> byName;
    private final TraceRoutingProperties routing;

    public LogisticsTraceRouter(List<TraceProvider> providers, TraceRoutingProperties routing) {
        // 名字重复视为装配错误：两个 provider 抢同一个名字，路由会指到谁完全看注册顺序
        this.byName = providers.stream().collect(Collectors.toMap(
                TraceProvider::name, Function.identity(),
                (a, b) -> {
                    throw new IllegalStateException("两个 TraceProvider 同名: " + a.name());
                }));
        this.routing = routing;
        log.info("[trace] provider 已注册: {}; 门店路由: {}; 默认: {}",
                byName.keySet(), routing.getStoreRoute(), routing.getDefaultProvider());
    }

    @Override
    public Optional<TraceResult> trace(String storeNo, String carrier, String waybillNo) {
        if (carrier == null || carrier.isBlank() || waybillNo == null || waybillNo.isBlank()) {
            return Optional.empty();
        }
        // 按门店挑物流路径：门店配了用门店的，没配用默认（生产=圆通）
        String providerName = storeNo == null ? routing.getDefaultProvider()
                : routing.getStoreRoute().getOrDefault(storeNo, routing.getDefaultProvider());
        TraceProvider p = byName.get(providerName);
        /*
         * 回落到空，不抛：
         *   · 门店路由指了一个不存在的 provider 名（配错）
         *   · provider 没配凭据（available=false）
         *   · provider 不认这个承运商（covers=false，门店选的路径与这单承运商对不上）
         * 任何一种，轨迹这一段空着好过整个订单详情 500。错误进日志，让运营看得见。
         */
        if (p == null) {
            log.warn("[trace] 门店 {} 路由到的 provider '{}' 不存在，这一单没有轨迹", storeNo, providerName);
            return Optional.empty();
        }
        if (!p.available()) {
            log.warn("[trace] provider '{}' 不可用（缺凭据？），门店 {} 暂无轨迹", providerName, storeNo);
            return Optional.empty();
        }
        if (!p.covers(carrier)) {
            log.warn("[trace] provider '{}' 不认承运商 {}（门店 {} 的物流路径与这单承运商对不上）",
                    providerName, carrier, storeNo);
            return Optional.empty();
        }
        return p.trace(carrier, waybillNo);
    }
}
