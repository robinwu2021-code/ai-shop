package ai.neargo.shop.logistics.channel.routing.port;

import ai.neargo.shop.logistics.channel.routing.TraceRoutingProperties;
import ai.neargo.shop.spi.logistics.TraceDisplay;
import ai.neargo.shop.spi.logistics.TraceDisplayPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@link TraceDisplayPort} 的实现：**按端的优先级链挑展示渠道**（TDD-物流轨迹多渠道 §2.3）。
 *
 * <p>与数据源那条链（{@code LogisticsTraceRouter}）是同一套回退语义：
 * 某一环 supports=false 或 prepare 失败，就落到下一环；全链走完才算空。
 *
 * <p>加第三个展示渠道只要实现 {@link TraceDisplay} + 在配置链里加一行，调用方与端上一行不改。
 */
@Component
public class TraceDisplayRouter implements TraceDisplayPort {

    private static final Logger log = LoggerFactory.getLogger(TraceDisplayRouter.class);

    private final Map<String, TraceDisplay> byName;
    private final TraceRoutingProperties routing;
    /** 最近一次失败原因。**只给运营看**，不下发端上 */
    private final ThreadLocal<String> lastFail = new ThreadLocal<>();

    public TraceDisplayRouter(List<TraceDisplay> displays, TraceRoutingProperties routing) {
        // 同名视为装配错误：两个渠道抢同一个名字，链指到谁完全看注册顺序
        this.byName = displays.stream().collect(Collectors.toMap(
                TraceDisplay::name, Function.identity(),
                (a, b) -> {
                    throw new IllegalStateException("两个 TraceDisplay 同名: " + a.name());
                }));
        this.routing = routing;
        log.info("[display] 渠道已注册: {}; 链: mp={} app={} ops={}", byName.keySet(),
                routing.getDisplay().getMp(), routing.getDisplay().getApp(), routing.getDisplay().getOps());
    }

    @Override
    public Optional<TraceDisplay.DisplayPayload> decide(TraceDisplay.Surface surface, TraceDisplay.ShipmentCtx ctx) {
        lastFail.remove();
        if (surface == null || ctx == null) {
            return Optional.empty();
        }
        List<String> chain = routing.displayChain(surface.name());
        if (chain == null || chain.isEmpty()) {
            log.warn("[display] 端 {} 没有配展示渠道链，这一单不显示轨迹", surface);
            return Optional.empty();
        }
        for (String name : chain) {
            TraceDisplay d = byName.get(name);
            if (d == null) {
                log.warn("[display] 链上的渠道 '{}' 不存在（端 {}），跳过", name, surface);
                continue;
            }
            if (!d.supports(surface, ctx)) {
                continue;   // 这一单它呈现不了（如没有微信支付单号），静默落下一个——这是常态不是异常
            }
            Optional<TraceDisplay.DisplayPayload> p = d.prepare(ctx);
            if (p.isPresent()) {
                return p;
            }
            // 备载荷失败才是值得记的：运营排查「买家说看不到物流」时第一个要看的就是它
            lastFail.set(name + " 备载荷失败");
            log.info("[display] 渠道 '{}' 备载荷失败（运单 {}），落到链上下一个", name, ctx.waybillNo());
        }
        return Optional.empty();
    }

    @Override
    public String lastFailReason() {
        return lastFail.get();
    }
}
