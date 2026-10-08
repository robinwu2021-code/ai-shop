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
                          String provider, List<TraceNode> nodes, Route route) {

    /** 不带城市路线的老形状。存量 provider（圆通、stub）与存量用例不用改 */
    public TraceResult(String waybillNo, String carrier, TraceStatus status,
                       String provider, List<TraceNode> nodes) {
        this(waybillNo, carrier, status, provider, nodes, null);
    }

    /**
     * 城市路线：出发 → 当前 → 目的。地图上三个标记用它，不必从节点里猜。
     *
     * <p>快递100 的 {@code routeInfo} 直接给（resultv2=4）。拿不到时整个 Route 为 null，
     * 端上退回「只画节点连成的折线」—— 不自己从节点里推断起终点：
     * 节点的行政区是**扫描地**，第一条未必是寄出地（可能是上门揽收的网点所在城市）。
     *
     * @param from 出发城市名，取不到为 null
     * @param cur  当前所在城市名
     * @param to   目的城市名
     */
    public record Route(String from, String cur, String to) {
    }

    /**
     * 一个轨迹节点。
     *
     * @param at       扫描时刻（毫秒）
     * @param status   这一节点的统一状态
     * @param info     处理信息（「【深圳市】已揽收」这类人话，承运商给什么用什么）
     * @param location 城市/网点，取不到为空
     * @param latE6    行政区中心纬度 ×1e6，地图用。**不是快件 GPS** —— 聚合器只给到行政区中心点
     * @param lngE6    行政区中心经度 ×1e6
     * @param statusCode 承运商/聚合器的高级状态码，步骤条区分「派送中」用；端上不直接显示
     */
    public record TraceNode(long at, TraceStatus status, String info, String location,
                            Integer latE6, Integer lngE6, String statusCode) {

        /** 不带坐标的老形状。圆通直连与 stub 还没有坐标，存量用例也用它 */
        public TraceNode(long at, TraceStatus status, String info, String location) {
            this(at, status, info, location, null, null, null);
        }
    }
}
