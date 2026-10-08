package ai.neargo.shop.channel.express.display;

import ai.neargo.shop.spi.logistics.TraceDisplay;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 自建展示（TDD-物流轨迹多渠道 §2.3）：地图 + 步骤条 + 时间线，三端通用、不挑支付方式。
 *
 * <p><b>它是链尾兜底</b>，所以 {@link #supports} 恒真 —— 链尾放一个会挑单的渠道，
 * 等于某些单什么都不显示。
 *
 * <p>它<b>不自己查轨迹</b>：节点由调用方（{@code LogisticsServiceImpl}）从
 * {@code ful_shipment_trace} 读好传进来。两条轴互不知道对方存在，这里只负责「怎么呈现」。
 */
@Component
public class SelfMapDisplay implements TraceDisplay {

    @Override
    public String name() {
        return "self-map";
    }

    @Override
    public boolean supports(Surface surface, ShipmentCtx ctx) {
        return true;
    }

    /**
     * 节点与路线由调用方填。这里返回一个**空载荷占位**：路由只负责选中渠道，
     * 真正的节点是调用方在选中之后按渠道名装的 —— 让 provider 回头查库，
     * 等于把「读本地轨迹」这条路径又实现一遍。
     */
    @Override
    public Optional<DisplayPayload> prepare(ShipmentCtx ctx) {
        return Optional.of(new DisplayPayload(name(), null, List.of(), null, false));
    }
}
