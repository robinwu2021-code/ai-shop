package ai.neargo.shop.logistics.channel.routing.port;

import ai.neargo.shop.logistics.channel.routing.TraceRoutingProperties;
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
        return trace(storeNo, carrier, waybillNo, null);
    }

    /**
     * 顺着**数据源优先级链**查（TDD-物流轨迹多渠道 §2.2）：第一个「可用 + 认这个承运商 + 真查到了」的胜出。
     *
     * <p>与改造前的差别：以前是「按门店挑一个，挑中谁就是谁，不可用就空着」。
     * 现在不可用 / 不认这个承运商 / 查不到，都**继续链上下一个**。
     *
     * <p><b>代价要说清楚</b>：一单可能连查两家、花两份钱。所以默认链只有一个，
     * 多级链只给确有直连账号的承运商配（配置注释里也写了）。
     */
    @Override
    public Optional<TraceResult> trace(String storeNo, String carrier, String waybillNo, String phone) {
        if (carrier == null || carrier.isBlank() || waybillNo == null || waybillNo.isBlank()) {
            return Optional.empty();
        }
        List<String> chain = routing.sourceChain(storeNo, carrier);
        if (chain == null || chain.isEmpty()) {
            log.warn("[trace] 门店 {} 承运商 {} 没有配数据源链，这一单没有轨迹", storeNo, carrier);
            return Optional.empty();
        }
        for (String name : chain) {
            TraceProvider p = byName.get(name);
            /*
             * 任何一种「这一环用不了」都只是**落到下一环**，不抛：
             *   · 链里写了一个不存在的 provider 名（配错）
             *   · provider 没配凭据（available=false）
             *   · provider 不认这个承运商
             *   · 查到了空（这家查不到，换一家也许有）
             * 全链走完仍空 → 轨迹这一段空着，好过整个订单详情 500。原因进日志，让运营看得见。
             */
            if (p == null) {
                log.warn("[trace] 链上的 provider '{}' 不存在（门店 {}），跳过", name, storeNo);
                continue;
            }
            if (!p.available()) {
                log.info("[trace] provider '{}' 不可用（缺凭据？），落到链上下一个", name);
                continue;
            }
            if (!p.covers(carrier)) {
                log.info("[trace] provider '{}' 不认承运商 {}，落到链上下一个", name, carrier);
                continue;
            }
            Optional<TraceResult> hit = p.trace(carrier, waybillNo, phone);
            if (hit.isPresent()) {
                return hit;
            }
            log.info("[trace] provider '{}' 查不到 {} {}，落到链上下一个", name, carrier, waybillNo);
        }
        log.info("[trace] 链 {} 走完仍无结果：{} {}", chain, carrier, waybillNo);
        return Optional.empty();
    }
}
