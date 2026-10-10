package ai.neargo.shop.geo;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 可见范围的几何/网格/缓存参数（ADR-034）。前缀 {@code shop.reach}。
 *
 * <p>用 {@code @ConfigurationProperties} 不用 {@code @Value}：yaml 里写 kebab-case（{@code s2-min-level}）
 * 只有前者会松绑到驼峰字段，后者配了等于没配（见记忆「Spring 配置的静默失效」）。
 * 全部字段带默认值 —— 不挂 {@code ${ENV:}}，空串绑不进去会让整个上下文起不来。
 *
 * <ul>
 *   <li>S2 级别 12..16：边长约 2 km → 150 m。更细 cell 更多、精判更少；更粗反之。</li>
 *   <li>{@code s2MaxCells} 256：单个多边形的网格行上限；超大范围该用行政级而不是画图。</li>
 *   <li>{@code polygonMaxVertices} 200：防止一次保存几万个点拖慢每次匹配。</li>
 *   <li>{@code snapshotProbeSkipMs} 500：门店属性快照的探针跳过窗口。失效靠<b>版本探针</b>
 *       （{@code COUNT(*) + SUM(version)}，见 {@code ReachSnapshotCache}），不靠在写入口手工 evict ——
 *       那条路要覆盖二十多个事务方法，必漏一处，而漏接不报错。</li>
 * </ul>
 * 改了 S2 参数要跑一次 {@code ServiceAreaCells.rebuildAll()}，派生表本来就该能重建。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "shop.reach")
public class ReachGeoProps {

    private int s2MinLevel = 12;

    private int s2MaxLevel = 16;

    private int s2MaxCells = 256;

    private int polygonMaxVertices = 200;

    /**
     * 版本探针的跳过窗口（毫秒）。窗口内直接复用快照、连探针都省 ——
     * 一次目录请求会对几十家店反复问快照，那几十次不该各发一条探针 SQL。
     * 它同时是「商家改完多久生效」的上限：500ms 对商家感知是即时的。
     */
    private long snapshotProbeSkipMs = 500;
}
