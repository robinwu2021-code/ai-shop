package ai.neargo.shop.spi.logistics;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 交易域读物流的入口（TDD-物流模块 §2.3.1，物流-API §3.1）。
 *
 * <p><b>物流不认识用户</b>：调用方（交易域的 controller / service）必须先按当前登录人查到子单、判过属主，
 * 再拿业务键来问 —— 物流这一侧只认业务键。
 */
public interface LogisticsPort {

    /**
     * 一张运单给某个界面看的样子。
     *
     * <p>{@code surface=MP} 且是微信支付单、有 token、未终态、距上次 ≥ 10 分钟时，会顺带问一次微信 query_trace 校正状态；
     * {@code refresh=true} 时按探测链问一次（只问对该界面放行的渠道）。其余情况纯读库。
     */
    Optional<TrackView> track(TrackQuery q);

    /** 子单号 → 签收时间（毫秒）。没签收的不在结果里。自动确认收货与确认收货提醒用 */
    Map<String, Long> signedAtOf(Collection<String> subOrderNos);

    /**
     * @param bizType  今天只有 SUB_ORDER
     * @param surface  MP / APP / H5 / BIZ / OPS
     * @param refresh  界面上点了「刷新」
     */
    record TrackQuery(String bizType, String bizRef, String surface, boolean refresh) {

        public static TrackQuery subOrder(String subOrderNo, String surface, boolean refresh) {
            return new TrackQuery("SUB_ORDER", subOrderNo, surface, refresh);
        }
    }

    /**
     * @param displayMode  {@code wx-plugin} / {@code self-map}
     * @param displayToken 微信插件的 waybillToken，只在 wx-plugin 时有
     * @param freshAt      最近一次有新进展的时刻（毫秒）
     * @param refreshable  这个界面的「刷新」能不能真的去问渠道
     */
    record TrackView(String shipmentNo, String carrier, String waybillNo, String status, Long signedAt,
                     boolean atLocker, List<Node> nodes, String displayMode, String displayToken,
                     Long freshAt, boolean refreshable) {
    }

    /** @param at 毫秒；nodes 倒序（最新在前） */
    record Node(long at, String text, String location, Integer latE6, Integer lngE6, String statusCode) {
    }
}
