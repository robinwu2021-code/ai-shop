package ai.neargo.shop.portal.biz;

import ai.neargo.shop.platform.AddressHints;
import ai.neargo.shop.platform.GeoService;
import ai.neargo.shop.platform.RegionService;
import ai.neargo.shop.spi.platform.GeoPort;
import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * B 端 · 行政区划浏览（ADR-013 阶段二）。
 *
 * <p>商家框经营范围时要能逐级挑到街道。与运营端那套是同一个 Service，
 * <b>只差一个口径</b>：这里恒定 {@code enabledOnly=true} ——
 * 停用的区划是运营的维护对象，不该出现在商家的选择器里。
 * 让商家看见一个自己选不了（或选了会被驳回）的选项，只会让他反复来问。
 *
 * <p>放在 {@code api} profile 而不是 {@code ops}：这是商家侧的路由，
 * 以 ops 起服务时它就该 404 —— 隔离靠路由不存在，不靠安全链。
 */
@Profile("api")
@RestController
public class BizRegionController {

    private final RegionService regionService;
    private final ai.neargo.shop.community.service.CommunityService communityService;
    private final GeoService geoService;

    public BizRegionController(RegionService regionService,
                               ai.neargo.shop.community.service.CommunityService communityService,
                               GeoService geoService) {
        this.regionService = regionService;
        this.communityService = communityService;
        this.geoService = geoService;
    }

    /**
     * 跨级搜索（P1）：选择器「任何一级都能搜」的正式版。
     *
     * <p>一次返回两类命中：区划（市/区县/街道，带从省到父级的路径）与已开通聚落
     * （小区/村，带所在街道路径）。此前端上只能过滤当前层 + 全部聚落，
     * 在省级列表里搜「转塘」什么都搜不到。
     * 路径按区划码去重回溯：同一街道下几十个小区共用一条路径，不该查几十次。
     */
    @GetMapping("/biz/regions/search")
    public SearchVO search(@RequestParam String kw,
                           @RequestParam(required = false) Integer latE6,
                           @RequestParam(required = false) Integer lngE6) {
        java.util.Map<String, String> pathCache = new java.util.HashMap<>();
        java.util.function.Function<String, String> pathOf = code -> pathCache.computeIfAbsent(code,
                c -> regionService.path(c).stream()
                        .map(RegionService.RegionVO::name)
                        .collect(java.util.stream.Collectors.joining(" / ")));
        // 坐标同时喂给区划搜索：同名的「城关街道」全国上百个，不按距离排等于让人从一堆同名里猜
        List<RegionHit> regions = regionService.search(kw, 24, latE6, lngE6).stream()
                .map(r -> new RegionHit(r.regionCode(), r.level(), r.name(),
                        // 路径不含自己：列表主标题已经是名字
                        pathOf.apply(r.parentCode() == null ? "" : r.parentCode())))
                .toList();
        String q = kw == null ? "" : kw.trim();
        List<CommunityHit> communities = q.isEmpty() ? List.of() : communityService.all().stream()
                .filter(c -> c.name() != null && c.name().contains(q))
                .limit(30)
                .map(c -> new CommunityHit(c.communityNo(), c.name(), c.regionCode(),
                        c.regionCode() == null ? "" : pathOf.apply(c.regionCode()),
                        c.kind(), c.originCode(), c.originName(), c.rural(), c.latE6(), c.lngE6()))
                .toList();
        /*
         * **村也要能直接搜到**。此前搜索只认市/区/街道与已开通的聚落 ——
         * 商家心里的「我做哪儿」多半就是一个村名，他打「狮径」什么也搜不到，
         * 只能自己一级级点到街道，才发现名录里一直有这一条。
         *
         * 已开通的那条走上面的 communities（能直接勾），这里只出还没开通的，
         * 避免同一个地方在两组里各出现一次。
         */
        /*
         * **按归一化名字去重，不按原字符串**。已开通的聚落存的是商家随手起的名
         * （「景滑」），官方村名录里是原始机构名（「景滑村委会」）——两个字符串
         * 精确比对永远不相等，这正是搜「景滑村」出两条的根因（PlaceNames 类注释里写了）。
         */
        java.util.Set<String> openedNames = communities.stream()
                .map(c -> ai.neargo.shop.platform.PlaceNames.norm(c.name()))
                .collect(java.util.stream.Collectors.toSet());
        List<VillageHit> villages = q.isEmpty() ? List.of()
                : regionService.searchVillages(q, 20, latE6, lngE6).stream()
                .filter(v -> !openedNames.contains(ai.neargo.shop.platform.PlaceNames.norm(v.name())))
                .map(v -> new VillageHit(v.regionCode(), v.name(),
                        v.parentCode() == null ? "" : v.parentCode(),
                        v.parentCode() == null ? "" : pathOf.apply(v.parentCode()),
                        v.latE6(), v.lngE6(), v.rural()))
                .toList();
        /*
         * **本地没有才问地图**（v5：搜索框统一走服务端，App 不再自己调原生 SDK）。
         *
         * 「本地没有」按村/小区这一类命中数为 0 判 —— 省市区街道这类行政区划命中不算，
         * 那不是商家真正要落的点。「福安雅园」「嘉怡花园」这类地图上真实存在、
         * 库里还没人开过的小区，就是从这条路径出现在搜索结果里的。
         */
        List<GeoPort.Tip> places = List.of();
        if (communities.isEmpty() && villages.isEmpty() && !q.isEmpty() && geoService.available()) {
            List<String> segs = AddressHints.segments(q);
            String target = AddressHints.target(segs);
            String city = AddressHints.cityHint(segs);
            places = geoService.tips(target, city);
        }
        return new SearchVO(regions, communities, villages, places);
    }

    /** 从省到自身的整条链路：选择器从搜索命中下钻时要把面包屑换成真实路径 */
    @GetMapping("/biz/regions/path")
    public List<RegionService.RegionVO> path(@RequestParam String code) {
        return regionService.path(code).stream()
                .filter(r -> !"VILLAGE".equals(r.level()))
                .toList();
    }

    public record RegionHit(String regionCode, String level, String name, String path) {
    }

    /**
     * @param originCode 官方村码，只有 kind=VILLAGE 且经官方名录开通的才有。**下钻要用它**，
     *                   不能用 {@link #regionCode}（那是它挂的街道/镇，不是它自己）
     * @param originName origin_code 对应的原始官方名（如「景滑村委会」）。判「城区还是农村」用它
     */
    public record CommunityHit(String communityNo, String name, String regionCode, String path,
                               String kind, String originCode, String originName, boolean rural,
                               Integer latE6, Integer lngE6) {
    }

    /**
     * @param streetCode 这个村挂的街道码（9 位）。端上提报要挂到它下面
     * @param latE6      村中心坐标，可能为空（只有补录过的城市有）
     */
    public record VillageHit(String regionCode, String name, String streetCode, String path,
                             Integer latE6, Integer lngE6, boolean rural) {
    }

    public record SearchVO(List<RegionHit> regions, List<CommunityHit> communities,
                           List<VillageHit> villages, List<GeoPort.Tip> places) {
    }

    /**
     * 某区划的直接下级。{@code parent} 为空取省级。
     *
     * <p>带上当前商家：他补录过、运营还没确认的村只对他自己可见。
     * 不带的话他刚录完就在选择器里找不到，看起来像没保存上。
     */
    @GetMapping("/biz/regions")
    public List<RegionService.RegionVO> children(@RequestParam(required = false) String parent) {
        /*
         * 聚落模型（2026-08-22）：**导航止于街道/镇（L4）**。
         *
         * 村级 62 万行退出导航、转为提报时的名称词典 —— 商家在 L4 下看到的是
         * 「聚落列表 + 提报入口」，不是再往下钻一层行政区划。
         * 所以这里滤掉 VILLAGE 行，并把街道的 hasChild 压成 false：
         * 不压的话端上看到 ›，点进去却是空的，像坏了。
         */
        return regionService.children(parent, true, null).stream()
                .filter(r -> !"VILLAGE".equals(r.level()))
                .map(r -> "STREET".equals(r.level())
                        ? new RegionService.RegionVO(r.regionCode(), r.parentCode(), r.level(),
                                r.name(), r.enabled(), false, r.source(), r.pending(),
                                r.auditStatus(), r.rejectReason(), r.latE6(), r.lngE6(), r.rural())
                        : r)
                .toList();
    }

    /**
     * 街道/镇下的官方村级<b>词典</b>（提报村时的名称联想与查重）。
     *
     * <p>不是导航层级：62 万村级行已退出选择器，但它是全国村名的唯一权威清单 ——
     * 没有它，提报村全靠商家手打，同一个村会被打出三种写法。
     * 选中词典项的提报带上 origin_code，运营裁决时据此查重（一村一聚落）。
     */
    @GetMapping("/biz/regions/villages")
    public List<RegionService.RegionVO> villageDict(@RequestParam String street,
                                                    @RequestParam(required = false) String keyword) {
        String kw = keyword == null ? "" : keyword.trim();
        return regionService.children(street, true, null).stream()
                .filter(r -> "VILLAGE".equals(r.level()))
                .filter(r -> kw.isEmpty() || r.name().contains(kw))
                .limit(50)
                .toList();
    }

}
