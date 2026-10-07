package ai.neargo.shop.merchant.reach;

import ai.neargo.shop.common.Fulfillments;
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
     * 一家店（或主体口径）判定所需的全部配置。
     *
     * @param storeNo  空 = 主体口径（各店开着的路取并集、一律按「全部」）
     * @param includes 主体 ACTIVE 的 INCLUDE
     * @param excludes 主体全部 EXCLUDE（不看 status）
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
     * 「不限地区」：主体没框任何 INCLUDE，且有一路选了「全部」的快递或自送。
     * 买家详情页「销售区域」那一行用它 —— 与可见性同一个判据，不另写一遍。
     */
    public static boolean unlimited(StoreReach s) {
        return s.includes().isEmpty() && s.routes().stream()
                .anyMatch(r -> !r.subset() && unlimitedWhenUnframed(r.channel()));
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
