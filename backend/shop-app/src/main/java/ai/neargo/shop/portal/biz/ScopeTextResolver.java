package ai.neargo.shop.portal.biz;

import ai.neargo.shop.common.PlaceNames;
import ai.neargo.shop.community.dto.CommunityVO;
import ai.neargo.shop.community.service.CommunityService;
import ai.neargo.shop.platform.RegionService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 经营范围文字录入 · 地名认码（TDD-经营范围文字录入 §2「认地名」）。
 *
 * <p><b>码一律来自库</b>：省走简称表，其余走 {@link RegionService#search} 与聚落表 ——
 * 与选择器搜索同一个来源。<b>只认「名字相同」</b>（或差一个行政后缀），不做包含式模糊：
 * 同名多处给候选，认不出就原样退回 —— 宁可让店主手动选，不替他猜。
 */
final class ScopeTextResolver {

    /** 一条认出来的范围项。{@code name} 是整条路径（与选择器存的 name 同一形状） */
    record Hit(String level, String refCode, String name) {
    }

    /** @param hits 1 条 = 认准；多条 = 同名候选；0 条 = 认不出 */
    record Result(List<Hit> hits) {
    }

    private static final List<String> SUFFIXES = List.of("省", "市", "区", "县", "旗", "镇", "乡", "街道", "自治县", "新区");
    private static final int MAX_CANDIDATES = 6;
    /** 「阳光花园3栋」「阳光花园 3 幢」「阳光花园3号楼」 */
    private static final Pattern BUILDING = Pattern.compile("^(.+?)\\s*(\\d+)\\s*(栋|幢|号楼|座)$");
    private static final Pattern BUILDING_NAME = Pattern.compile("^\\s*(\\d+)\\s*(栋|幢|号楼|座)\\s*$");

    private final RegionService regions;
    private final CommunityService communities;
    private final Integer latE6;
    private final Integer lngE6;
    private final Map<String, String> pathCache = new LinkedHashMap<>();
    private List<CommunityVO> allCommunities;

    ScopeTextResolver(RegionService regions, CommunityService communities, Integer latE6, Integer lngE6) {
        this.regions = regions;
        this.communities = communities;
        this.latE6 = latE6;
        this.lngE6 = lngE6;
    }

    /** 一个短语可能是粘连的几个省 —— 那就是几条，各自认准 */
    List<Result> resolveAll(String phrase) {
        List<String> provinces = ScopeTextParser.splitProvinces(phrase);
        if (provinces != null) {
            return provinces.stream().map(c -> new Result(List.of(province(c)))).toList();
        }
        return List.of(resolve(phrase));
    }

    Result resolve(String phrase) {
        String p = phrase.trim();
        String prov = ScopeTextParser.provinceCode(p);
        if (prov != null) {
            return new Result(List.of(province(prov)));
        }
        List<Hit> exact = regionExact(p, null);
        if (!exact.isEmpty()) {
            return new Result(cap(exact));
        }
        /*
         * 「深圳龙华区」「北京朝阳区」：前半截是上级，后半截在它底下找。
         * 上级必须唯一认准才用 —— 这同时就是同名区的消歧：「朝阳区」有两个，「北京朝阳区」只有一个。
         * 上级只认「省或区划名相同」、**不再往下拆**：递归拆的话查询次数随长度指数涨（接口预算 < 1s）。
         */
        for (int i = p.length() - 2; i >= 2; i--) {
            String left = p.substring(0, i);
            String leftProv = ScopeTextParser.provinceCode(left);
            List<Hit> leftHits = leftProv != null ? List.of(province(leftProv)) : regionExact(left, null);
            if (leftHits.size() != 1) {
                continue;
            }
            List<Hit> under = regionExact(p.substring(i), leftHits.get(0).refCode());
            if (!under.isEmpty()) {
                return new Result(cap(under));
            }
        }
        Matcher b = BUILDING.matcher(p);
        if (b.matches()) {
            List<Hit> buildings = building(b.group(1), b.group(2));
            if (!buildings.isEmpty()) {
                return new Result(cap(buildings));
            }
        }
        return new Result(cap(communityExact(p)));
    }

    private Hit province(String code) {
        return new Hit("PROVINCE", code, ai.neargo.shop.common.Provinces.NAME_BY_CODE.get(code));
    }

    /** 名字相同或「短语 + 行政后缀」相同的区划；{@code under} 非空时只留它底下的 */
    private List<Hit> regionExact(String p, String under) {
        if (p.length() < 2) {
            return List.of();
        }
        List<Hit> out = new ArrayList<>();
        for (var r : regions.search(p, 30, latE6, lngE6)) {
            if (!sameName(p, r.name())) {
                continue;
            }
            if (under != null && (r.regionCode() == null || !r.regionCode().startsWith(under))) {
                continue;
            }
            out.add(new Hit(r.level(), r.regionCode(), pathOf(r.regionCode())));
        }
        return out;
    }

    private static boolean sameName(String phrase, String name) {
        if (name == null) {
            return false;
        }
        if (name.equals(phrase)) {
            return true;
        }
        for (String s : SUFFIXES) {
            if (name.equals(phrase + s)) {
                return true;
            }
        }
        return false;
    }

    /** 聚落名按归一化比（「阳光花园」= 库里的「阳光花园小区」）；楼栋不在这一步出，要带上所属小区才认 */
    private List<Hit> communityExact(String p) {
        String n = PlaceNames.norm(p);
        if (n.isEmpty()) {
            return List.of();
        }
        return communities().stream()
                .filter(c -> c.parentNo() == null || c.parentNo().isBlank())
                .filter(c -> n.equals(PlaceNames.norm(c.name())))
                .map(this::communityHit)
                .toList();
    }

    /** 「X 3 栋」：先认 X（必须唯一），再在它的子聚落里找同号的那一栋 */
    private List<Hit> building(String estate, String no) {
        List<Hit> parents = communityExact(estate.trim());
        if (parents.size() != 1) {
            return List.of();
        }
        String parentNo = parents.get(0).refCode();
        return communities().stream()
                .filter(c -> parentNo.equals(c.parentNo()))
                .filter(c -> {
                    Matcher m = BUILDING_NAME.matcher(c.name() == null ? "" : c.name());
                    return m.matches() && m.group(1).equals(no);
                })
                .map(this::communityHit)
                .toList();
    }

    private Hit communityHit(CommunityVO c) {
        String path = c.regionCode() == null ? "" : pathOf(c.regionCode());
        return new Hit("COMMUNITY", c.communityNo(), path.isEmpty() ? c.name() : path + " / " + c.name());
    }

    private List<CommunityVO> communities() {
        if (allCommunities == null) {
            allCommunities = communities.all();
        }
        return allCommunities;
    }

    /** 从省到自己的整条名字（「广东省 / 深圳市 / 龙华区」），与选择器勾选时存的 name 同形 */
    private String pathOf(String code) {
        return pathCache.computeIfAbsent(code, c -> regions.path(c).stream()
                .map(RegionService.RegionVO::name)
                .collect(Collectors.joining(" / ")));
    }

    private static List<Hit> cap(List<Hit> hits) {
        return hits.size() > MAX_CANDIDATES ? List.copyOf(hits.subList(0, MAX_CANDIDATES)) : hits;
    }
}
