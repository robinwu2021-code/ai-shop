package ai.neargo.shop.merchant.reach;

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
 *   <li>{@code snapshotTtlSeconds} 300：门店元数据快照的兜底过期；写路径会主动失效，这只是多实例下的上限。</li>
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

    private int snapshotTtlSeconds = 300;
}
