package ai.neargo.shop.merchant.reach;

import ai.neargo.shop.common.Fulfillments;
import ai.neargo.shop.spi.reach.ConsumerProfile;
import ai.neargo.shop.spi.user.CommunityQueryPort.CommunityRef;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「某家店的某一路送货方式，送不送得到某个小区」—— <b>全平台唯一的判定</b>
 * （方案-商品可见性改查询时关联 §2.3）。
 *
 * <p>买家看不看得见（展示）、下单落到哪家店、结算时能选哪几种送货方式，三处都调它。
 * 此前这件事有三份实现：展示把一家店所有 SUBSET 路取并集、整店一起裁；结算校验逐路判、
 * 但小区不含楼栋、不减 EXCLUDE；买家详情页的「销售区域」又是第三种。三份在四种情形下答案不同，
 * 表现是「看得见、结算说不送」或者反过来 —— 都不报错。
 *
 * <p>纯函数：不碰 Mapper。配置由 {@link StoreReachLoader} 读好传进来，小区信息由调用方给。
 *
 * <h2>规则（每条都在 ReachRuleTest 里有一行）</h2>
 * <ol>
 *   <li>EXCLUDE 优先，<b>对每一路一视同仁</b>，且不看 status（待审的排除也立即生效）</li>
 *   <li>某一路选了 SUBSET：只看这一路勾的那几块，与主体 ACTIVE INCLUDE 取交（主体已经不覆盖的不算）；
 *       <b>一块没勾 = 这一路哪儿都不送</b></li>
 *   <li>选「全部」且主体没框任何 INCLUDE：快递、自送 = 全部开放小区；自提 = 没有落点，谁也送不到</li>
 *   <li>选「全部」且主体框了 INCLUDE：按 INCLUDE 展开 —— 快递也尊重框选（#4②）</li>
 *   <li>COMMUNITY 级范围：点名的那个小区<b>不检查是否开放</b>；它下一层的楼栋只取开放的</li>
 *   <li>STREET / DISTRICT / CITY 级：区划码前缀匹配，只取开放小区</li>
 * </ol>
 *
 * <p>主体是否 ACTIVE、门店是否 ACTIVE <b>不在这里判</b>：那是状态闸，由调用方各自把守。
 * 这里只回答「地理上、这一路送不送得到」。
 */
public final class ReachRule {

    public static final String LEVEL_COMMUNITY = "COMMUNITY";

    private ReachRule() {
    }

    /** 一条范围项。{@code level=COMMUNITY} 时 {@code refCode} 是小区号，否则是区划码前缀 */
    public record Area(String areaNo, String level, String refCode) {
    }

    /**
     * 一路送货方式。
     *
     * @param subsetAreaNos {@code null} = 选的「全部」（主体足迹）；非空集合 = SUBSET 勾的那几块；
     *                      <b>空集合 = SUBSET 一块没勾</b>，与 {@code null} 含义相反，不能混
     */
    public record Route(String channel, Set<String> subsetAreaNos) {
        public static Route all(String channel) {
            return new Route(channel, null);
        }

        public boolean subset() {
            return subsetAreaNos != null;
        }
    }

    /**
     * 一家店判定所需的全部配置。经营范围是门店级的（V381）：includes/excludes 是<b>这家店</b>的。
     * 主体口径不再是一份合起来的配置，而是名下各店逐个判、取并集（见 StoreReachLoader.loadEach）——
     * 把各店的 INCLUDE/EXCLUDE 混成一份判是错的：A 店排除的楼会把 B 店纳入的同一栋一起减掉。
     *
     * @param includes 这家店 ACTIVE 的 INCLUDE
     * @param excludes 这家店全部 EXCLUDE（不看 status）
     */
    public record StoreReach(String entityNo, String storeNo, List<Route> routes,
                             List<Area> includes, List<Area> excludes) {
    }

    /** 这一路在这个小区送不送得到 */
    public static boolean covers(StoreReach s, Route r, CommunityRef c) {
        if (c == null || r == null || matchesAny(s.excludes(), c)) {
            return false;
        }
        if (r.subset()) {
            return s.includes().stream()
                    .filter(a -> r.subsetAreaNos().contains(a.areaNo()))
                    .anyMatch(a -> matches(a, c));
        }
        if (s.includes().isEmpty()) {
            return unlimitedWhenUnframed(r.channel()) && c.open();
        }
        return matchesAny(s.includes(), c);
    }

    /** 这家店在这个小区送不送得到 = 任一路送得到 */
    public static boolean covers(StoreReach s, CommunityRef c) {
        return s.routes().stream().anyMatch(r -> covers(s, r, c));
    }

    /**
     * 结算时这一路能不能选。与 {@link #covers(StoreReach, Route, CommunityRef)} 只差一处：
     * <b>自提选「全部」时不看买家在哪个小区</b> —— 自提的地理约束在取货点上
     * （下单那道「取货点这家店送不送」的闸），买家住哪儿与能不能去取无关。
     * 否则「自送不限范围、也支持到店自取」的商家，买家一选自提就被拒。
     *
     * <p>EXCLUDE 照样先减：商家明说了不服务那里。
     */
    public static boolean selectable(StoreReach s, Route r, CommunityRef c) {
        if (c == null || r == null || matchesAny(s.excludes(), c)) {
            return false;
        }
        if (!r.subset() && Fulfillments.isPickup(r.channel())) {
            return true;
        }
        return covers(s, r, c);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 新判定（ADR-034）：命中由 HitFinder 查、属性由快照给，这里只做组合。
    // 与上面那组的区别：没有「includes 为空 + 快递/自送 = 全平台」这条隐式分支 ——
    // 「不限」必须是显式的 UNLIMITED 范围项。上面那组在调用方迁完后删除。
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 这家店对这个消费者可不可见 —— <b>全平台唯一的组合规则</b>。
     *
     * <ol>
     *   <li><b>fail-closed</b>：店有行政级排除而消费者没有区划码、或有多边形排除而没有坐标 → 不可见。
     *       不能证明「不在排除里」就不放行（宁可少卖不可错卖）。
     *       聚落级排除<b>不</b>收紧 —— 聚落解析是尽力而为，收紧会让所有纯定位用户看不到任何
     *       「排除了某一栋楼」的店，不成比例。</li>
     *   <li><b>排除先算、永远赢</b>：任一排除项命中即不可见（含「框了深圳、点名纳入某小区」那种矛盾输入）。
     *       边界 cell 命中的排除多边形要精判。</li>
     *   <li><b>纳入再并</b>：确定命中的纳入项 ∪ 精判通过的边界纳入项。</li>
     *   <li><b>路再闸</b>：任一路可达即可见。SUBSET 路只认它勾的那几条纳入项（「不限」不参与子集）；
     *       「全部」路有任一纳入项即可达，否则看「不限」—— 而「不限」只对快递/自送为真，
     *       自提没有落点。</li>
     *   <li><b>没有任何纳入命中、也没有「不限」→ 不可见</b>。没框范围的店对谁都不可见。</li>
     * </ol>
     */
    public static boolean decide(StoreMeta s, StoreHits h, ConsumerProfile p, PolygonProbe polys) {
        if (s == null || p == null) {
            return false;
        }
        StoreHits hits = h == null ? StoreHits.empty() : h;
        if (excluded(s, hits, p, polys)) {
            return false;
        }
        Set<String> included = included(hits, p, polys);
        for (Route r : s.routes()) {
            if (routeReaches(s, included, r)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 结算时这一路能不能选。与 {@link #decide} 只差一处：
     * <b>自提选「全部」时不看买家在哪个小区</b> —— 自提的地理约束在取货点上
     * （下单那道「取货点这家店送不送」的闸），买家住哪儿与能不能去取无关。
     * 否则「自送不限范围、也支持到店自取」的商家，买家一选自提就被拒。
     *
     * <p>排除照样先减（含 fail-closed）：商家明说了不服务那里。
     */
    public static boolean selectable(StoreMeta s, StoreHits h, ConsumerProfile p, Route r, PolygonProbe polys) {
        if (s == null || p == null || r == null) {
            return false;
        }
        StoreHits hits = h == null ? StoreHits.empty() : h;
        if (excluded(s, hits, p, polys)) {
            return false;
        }
        if (!r.subset() && Fulfillments.isPickup(r.channel())) {
            return true;
        }
        return routeReaches(s, included(hits, p, polys), r);
    }

    /** 排除判定：fail-closed + 确定命中 + 边界精判 */
    private static boolean excluded(StoreMeta s, StoreHits h, ConsumerProfile p, PolygonProbe polys) {
        if (s.hasAdminExclude() && !p.hasRegion()) {
            return true;
        }
        if (s.hasPolygonExclude() && !p.hasCoords()) {
            return true;
        }
        if (!h.excludeAreaNos().isEmpty()) {
            return true;
        }
        for (String areaNo : h.boundaryExcludeAreaNos()) {
            if (polys.covers(areaNo, p)) {
                return true;
            }
        }
        return false;
    }

    /** 确定命中的纳入项 ∪ 精判通过的边界纳入项 */
    private static Set<String> included(StoreHits h, ConsumerProfile p, PolygonProbe polys) {
        if (h.boundaryIncludeAreaNos().isEmpty()) {
            return h.includeAreaNos();
        }
        Set<String> out = new LinkedHashSet<>(h.includeAreaNos());
        for (String areaNo : h.boundaryIncludeAreaNos()) {
            if (polys.covers(areaNo, p)) {
                out.add(areaNo);
            }
        }
        return out;
    }

    private static boolean routeReaches(StoreMeta s, Set<String> included, Route r) {
        if (r.subset()) {
            // 「不限」不参与子集：子集说的是「这一路只服务我框的其中几块」
            for (String areaNo : included) {
                if (r.subsetAreaNos().contains(areaNo)) {
                    return true;
                }
            }
            return false;
        }
        if (!included.isEmpty()) {
            return true;
        }
        return s.unlimited() && unlimitedChannel(r.channel());
    }

    /** 「不限」对哪几路成立：快递与自送没有落点约束；自提有（取货点），「不限」对它无意义 */
    private static boolean unlimitedChannel(String channel) {
        return Fulfillments.EXPRESS.equals(channel) || Fulfillments.MERCHANT_DELIVERY.equals(channel);
    }

    /** 在候选小区里筛出送得到的（正向展开）。保持候选的顺序、去重 */
    public static List<String> reachable(StoreReach s, Collection<CommunityRef> candidates) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (CommunityRef c : candidates) {
            if (covers(s, c)) {
                out.add(c.communityNo());
            }
        }
        return List.copyOf(out);
    }

    /**
     * 「不限地区」：有<b>显式的 {@code UNLIMITED} 纳入项</b>，且有一路选了「全部」的快递或自送。
     * 买家详情页「销售区域」那一行用它 —— 与可见性同一个判据，不另写一遍。
     *
     * <p>判据在 ADR-034 改过：此前是「没框任何 INCLUDE + 开了快递/自送」= 隐式不限。
     * 改成显式之后，存量那批店被迁移补上了 UNLIMITED 行 —— 若这里还按「includes 为空」判，
     * 补完行之后 includes 不空了，详情页会从「不限地区」变成列出一条没有地名的范围项。
     */
    public static boolean unlimited(StoreReach s) {
        boolean hasUnlimitedItem = s.includes().stream()
                .anyMatch(a -> ai.neargo.shop.merchant.entity.MchServiceArea.LEVEL_UNLIMITED.equals(a.level()));
        return hasUnlimitedItem && s.routes().stream()
                .anyMatch(r -> !r.subset() && unlimitedChannel(r.channel()));
    }

    /** 范围里直接点名的小区号（展开时要把它们也放进候选：它们可能没开放，不在开放小区全集里） */
    public static Set<String> namedCommunities(StoreReach s) {
        Set<String> out = new LinkedHashSet<>();
        for (Area a : s.includes()) {
            if (LEVEL_COMMUNITY.equals(a.level()) && a.refCode() != null) {
                out.add(a.refCode());
            }
        }
        return out;
    }

    /** 没框范围时「不限」的那几路：快递和自送没有落点约束；自提没框 = 没有落点 */
    private static boolean unlimitedWhenUnframed(String channel) {
        return Fulfillments.EXPRESS.equals(channel) || Fulfillments.MERCHANT_DELIVERY.equals(channel);
    }

    private static boolean matchesAny(List<Area> areas, CommunityRef c) {
        for (Area a : areas) {
            if (matches(a, c)) {
                return true;
            }
        }
        return false;
    }

    /** 一条范围项是否覆盖这个小区。INCLUDE 与 EXCLUDE 共用 —— 排除园区就排除它的楼栋 */
    static boolean matches(Area a, CommunityRef c) {
        String ref = a.refCode();
        if (ref == null || ref.isBlank()) {
            // 空前缀会匹配一切：「框了个空区划」不能变成覆盖全平台
            return false;
        }
        if (LEVEL_COMMUNITY.equals(a.level())) {
            return ref.equals(c.communityNo()) || (c.open() && ref.equals(c.parentNo()));
        }
        return c.open() && c.regionCode() != null && c.regionCode().startsWith(ref);
    }
}
