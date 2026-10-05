package ai.neargo.shop.spi.trade;

import java.util.List;
import java.util.Optional;

/**
 * trade → fulfillment：**按子单读缓存里的物流轨迹**（TDD-圆通物流直连 Y4）。
 *
 * <p>订单详情（C 端 / B 端）要显示轨迹，但轨迹数据在 fulfillment 域的
 * {@code ful_shipment}/{@code ful_shipment_trace} 里。trade 不直连那边的 Mapper
 * （ArchUnit 守跨域只走 Port），经这个只读 Port 取。
 *
 * <p><b>只读缓存，不触发承运商查询</b>：实时拉取是轮询 Job 的事（{@code LogisticsTracePollingJob}），
 * 详情页读到的是 Job 落好的那份。缓存里没有（没发货 / 没凭据查不到 / Job 还没跑）就返回 empty，
 * 端上显示「暂无轨迹」，不白屏。
 */
public interface ShipmentTraceQueryPort {

    /** 子单的轨迹缓存。没有运单记录或无轨迹节点时返回 empty。 */
    Optional<CachedTrace> traceOf(String subOrderNo);

    /**
     * @param status 运单状态（CREATED/PICKED_UP/IN_TRANSIT/DELIVERED/EXCEPTION，同 {@code ful_shipment}）
     * @param nodes  轨迹节点，**按时间倒序**（最新在前）
     */
    record CachedTrace(String status, List<Node> nodes) {
        /** @param at 毫秒时刻 · @param text 原样来自承运商 · @param location 城市/网点，可空 */
        public record Node(long at, String text, String location) {
        }
    }
}
