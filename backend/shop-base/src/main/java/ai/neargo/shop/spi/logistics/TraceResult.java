package ai.neargo.shop.spi.logistics;

import java.util.List;

/**
 * 一个运单的物流轨迹（统一结构，与承运商无关）。
 *
 * @param waybillNo 运单号
 * @param carrier   承运商码（ExpressCompanies，如 YTO）
 * @param status    统一状态（最新一档）
 * @param provider  这条是哪个 provider 查来的（yto / kuaidi100 / stub）—— 排查用，端上不显示
 * @param nodes     轨迹节点，**按时间倒序**（最新在前，端上从上往下读）
 */
public record TraceResult(String waybillNo, String carrier, TraceStatus status,
                          String provider, List<TraceNode> nodes) {

    /**
     * 一个轨迹节点。
     *
     * @param at       扫描时刻（毫秒）
     * @param status   这一节点的统一状态
     * @param info     处理信息（「【深圳市】已揽收」这类人话，承运商给什么用什么）
     * @param location 城市/网点，取不到为空
     */
    public record TraceNode(long at, TraceStatus status, String info, String location) {
    }
}
