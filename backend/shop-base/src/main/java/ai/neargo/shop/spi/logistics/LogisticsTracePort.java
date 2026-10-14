package ai.neargo.shop.spi.logistics;

import java.util.Optional;

/**
 * 物流轨迹的**单一入口**（trade/core 只认这个，不直连 channel —— ArchUnit 守跨域只走 Port）。
 *
 * <p>它的实现是**路由**（{@code LogisticsTraceRouter}）：按承运商把请求派给对应的
 * {@link TraceProvider}。调用方不知道、也不需要知道背后是圆通直连还是别的 ——
 * 将来加 provider、改路由，这个契约一行不变（这就是「多方式并存」落在架构上的样子）。
 */
public interface LogisticsTracePort {

    /**
     * 查一个运单的轨迹。**按门店路由**到对应 provider（门店级物流路径，默认圆通）。
     * 没有能查的 provider 时返回 empty（调用方显示「暂无轨迹」，不白屏）。
     *
     * @param storeNo   这一单发货的门店 —— 不同门店可走不同物流路径（§4.2）
     * @param carrier   运单的承运商码（发货时填的，provider 真查时要用）
     * @param waybillNo 运单号
     */
    Optional<TraceResult> trace(String storeNo, String carrier, String waybillNo);

    /**
     * 同上，带收件人手机号（顺丰、中通在快递100 查询时必填）。手机号只往下传给 provider，不落日志。
     */
    default Optional<TraceResult> trace(String storeNo, String carrier, String waybillNo, String phone) {
        return trace(storeNo, carrier, waybillNo);
    }
}
