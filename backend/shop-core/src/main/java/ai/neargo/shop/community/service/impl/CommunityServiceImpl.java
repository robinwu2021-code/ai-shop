package ai.neargo.shop.community.service.impl;

import ai.neargo.shop.community.service.CommunityService;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import ai.neargo.shop.spi.platform.MasterDataPort;
import ai.neargo.shop.community.dto.RegionOptionVO;

import ai.neargo.shop.community.dto.CommunityVO;
import ai.neargo.shop.community.entity.CmtCommunity;
import ai.neargo.shop.community.entity.CmtPickupPoint;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.user.MerchantQueryPort.MerchantBrief;
import ai.neargo.shop.community.mapper.CommunityMappers.CommunityMapper;
import ai.neargo.shop.community.mapper.CommunityMappers.PickupPointMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class CommunityServiceImpl implements CommunityService {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(CommunityServiceImpl.class);

    private final CommunityMapper communityMapper;
    private final PickupPointMapper pickupMapper;
    /*
     * 自提点要显示「归属商家的名字与 logo」，而商家表属于 merchant 域。
     * 原先这里直接注入 MchEntityMapper —— 社区域能读写整张 mch_entity，
     * 商家域改一个列，社区列表跟着炸，且没有任何编译期提示。
     * 改走 Port 之后，社区只拿到它真正需要的两个字段。
     */
    private final MerchantQueryPort merchantQueryPort;
    /** 区划码 → 名字。走 Port 而不是直接读 sys_region：社区域不该拿到平台域整张表 */
    private final MasterDataPort masterDataPort;

    /**
     * 「附近」的半径（米）。默认 5 公里 —— <b>自提是走过去取的</b>，
     * 几公里之外的自提点在物理上就不成立。
     *
     * <p>做成配置而不是常量：这条闸管着 C 端第一屏，设小了已开通社区的用户也会看到空态。
     * 真出问题改一个配置项即可，不用发版。
     */
    private final int nearbyRadiusM;

    /** 坐标 → 「这儿叫什么」。聚落围栏那一档排在它前面，见 PlaceResolver 的类注释 */
    private final ai.neargo.shop.community.service.PlaceResolver placeResolver;
    private final ai.neargo.shop.community.mapper.CommunityMappers.GeoPlaceMapper placeMapper;
    /** **跨域只走 spi 的 Port** —— 直接注入 platform 域的 Service 会让两个域长在一起 */
    private final ai.neargo.shop.spi.platform.GeoPort geoPort;
    private final ai.neargo.shop.community.support.MapBreaker mapBreaker;

    /** 本地与地图各取多少条。合并后端上还会再截，这里只防「一次拉回几百条」 */
    private static final int PLACE_PER_SOURCE = 10;
    /** 围着当前位置搜的半径。太大会把邻市的同名点搜进来 */
    private static final int PLACE_AROUND_M = 5000;

    /**
     * 「没落进任何围栏时，最远肯给到多远的默认归属」（米，M6）。
     *
     * <p><b>它是「同城」这个业务边界的代理，不是判定。</b> 冷启动期全市只有一两个聚落，
     * 「不在围栏里」是常态 —— 龙华的买家离福田的聚落 20 公里，他该看得到那儿的货；
     * 北京的买家不该。而端上手里只有坐标，没有城市判定，聚落距离是已有数据里最接近它的那把尺。
     *
     * <p>默认 50 公里。做成配置不是常量：这条闸管着 C 端第一屏，
     * 而「同城」在不同城市尺度差得很远（深圳约 80 公里、上海约 120 公里）。
     */
    private final int defaultBindRadiusM;

    public CommunityServiceImpl(CommunityMapper communityMapper, PickupPointMapper pickupMapper,
                                MerchantQueryPort merchantQueryPort,
                                MasterDataPort masterDataPort,
                                ai.neargo.shop.community.service.PlaceResolver placeResolver,
                                ai.neargo.shop.community.mapper.CommunityMappers.GeoPlaceMapper placeMapper,
                                ai.neargo.shop.spi.platform.GeoPort geoPort,
                                ai.neargo.shop.community.support.MapBreaker mapBreaker,
                                @Value("${shop.community.nearby-radius-m:5000}") int nearbyRadiusM,
                                @Value("${shop.community.default-bind-radius-m:50000}")
                                int defaultBindRadiusM) {
        this.communityMapper = communityMapper;
        this.pickupMapper = pickupMapper;
        this.merchantQueryPort = merchantQueryPort;
        this.masterDataPort = masterDataPort;
        this.placeResolver = placeResolver;
        this.placeMapper = placeMapper;
        this.geoPort = geoPort;
        this.mapBreaker = mapBreaker;
        this.nearbyRadiusM = nearbyRadiusM;
        this.defaultBindRadiusM = defaultBindRadiusM;
    }

    @Override
    public List<CommunityVO> all() {
        // 复用 nearby 的组装：不传坐标 → distance 恒 0，排序退化为库序。
        // 单独写一套查询只会让「社区带哪些自提点」在两处各实现一遍
        return nearby(null, null);
    }

    @Override
    public List<CommunityVO> all(String regionCode) {
        List<CommunityVO> list = all();
        if (regionCode == null || regionCode.isBlank()) {
            return list;
        }
        /*
         * **按前缀筛。** 国标区划码本身就是层级前缀（省 2 / 市 4 / 区县 6 / 街道 9），
         * 所以「3301」自然覆盖整个杭州市、「330106」只覆盖西湖区 ——
         * 不必先查一遍子区划再拼一个 IN 列表（那既慢又会在区划表更新时漏掉新街道）。
         */
        Set<String> hit = communityMapper.selectList(Wrappers.<CmtCommunity>lambdaQuery()
                        .eq(CmtCommunity::getStatus, "OPEN")
                        // 归档的不进发现型列表（与自提点同一条规矩）
                        .isNull(CmtCommunity::getArchivedAt)
                        .likeRight(CmtCommunity::getRegionCode, regionCode))
                .stream().map(CmtCommunity::getCommunityNo).collect(Collectors.toSet());
        return list.stream().filter(v -> hit.contains(v.communityNo())).toList();
    }

    /**
     * 区县码：社区可能挂在街道级（9 位），聚合到区县要截到前 6 位。
     *
     * <p>不到 6 位的（省、市）原样返回 —— 那说明它本来就没挂到区，
     * 截不出来也不能编一个：拿它当前缀去筛，筛出来的仍是「他确实在的那个范围」，只是粗一档。
     *
     * @return 空码返回 null，调用方据此走「推不出位置」那一支
     */
    private static String districtOf(String regionCode) {
        if (regionCode == null || regionCode.isBlank()) {
            return null;
        }
        return regionCode.length() >= 6 ? regionCode.substring(0, 6) : regionCode;
    }

    @Override
    public List<RegionOptionVO> openRegions() {
        List<CmtCommunity> open = communityMapper.selectList(Wrappers.<CmtCommunity>lambdaQuery()
                .eq(CmtCommunity::getStatus, "OPEN")
                .isNull(CmtCommunity::getArchivedAt)
                .isNotNull(CmtCommunity::getRegionCode));

        // 区县码 → 社区数
        Map<String, Integer> countByDistrict = new LinkedHashMap<>();
        for (CmtCommunity c : open) {
            String code = c.getRegionCode();
            if (code == null || code.isBlank()) {
                continue;
            }
            countByDistrict.merge(districtOf(code), 1, Integer::sum);
        }
        if (countByDistrict.isEmpty()) {
            return List.of();
        }

        // 区名与市名一次查完：一条一查会打出 2N 次查询
        Set<String> codes = new LinkedHashSet<>(countByDistrict.keySet());
        countByDistrict.keySet().stream()
                .filter(d -> d.length() >= 4)
                .map(d -> d.substring(0, 4))
                .forEach(codes::add);
        Map<String, String> names = masterDataPort.regionNames(codes);

        return countByDistrict.entrySet().stream()
                .map(e -> {
                    String district = e.getKey();
                    String city = district.length() >= 4 ? district.substring(0, 4) : district;
                    return new RegionOptionVO(district, names.get(district),
                            city, names.get(city), e.getValue());
                })
                /*
                 * **查不到名字的区划直接丢掉**（`names` 里没有这个码）。
                 * 那是脏数据 —— 社区挂了一个区划表里不存在的码。
                 * 让它带着空名字出现在选择器里，用户只会看到一行空白，
                 * 点进去还有社区，然后开始怀疑是自己手机的问题。
                 */
                .filter(v -> v.name() != null && !v.name().isBlank())
                .sorted(Comparator.comparing(RegionOptionVO::cityCode)
                        .thenComparing(RegionOptionVO::regionCode))
                .toList();
    }

    @Override
    public List<CommunityVO> nearby(Integer latE6, Integer lngE6) {
        boolean located = latE6 != null && lngE6 != null;
        List<CmtCommunity> open = communityMapper.selectList(Wrappers.<CmtCommunity>lambdaQuery()
                .eq(CmtCommunity::getStatus, "OPEN")
                .isNull(CmtCommunity::getArchivedAt));

        /*
         * **先按半径筛，再去富化。顺序错了这条接口就是秒级的。**
         *
         * 2026-09-18 线上实测：这个端点要 8.8 秒，而且**与返回条数无关**
         * （27 条与 6 条同为 8.7s）—— 典型的「代价乘在输入上、不在输出上」。
         * 原来的顺序是「全读 → 富化 → 过滤」：龙华开城之后 OPEN 聚落有 2783 条，
         * 下面那两句 `masterDataPort.regionNames/regionRural` 就拿着 2783 个
         * origin_code 去 62 万行的区划表里反查，而这些结果里 99% 当场被半径筛掉。
         * 开城之前全库只有 2 条，这段代价一直看不见。
         *
         * 症状伪装得很像端上的缺陷：选择地点页首屏「附近」整块空着、
         * 「当前位置」只有一行占位文字 —— 看起来是页面没渲染，其实是还没回来。
         *
         * **没用外接矩形下推到 SQL**：`withinRadius` 判的是每个聚落**自己的**
         * `fence_radius`，不是全局那一个值。按全局半径框矩形会把宽围栏的聚落
         * 误删，而那种删除是静默的。2783 行本身读起来不慢，贵的是富化 ——
         * 把过滤提前就够了。表再大一个量级时再谈下推，那时要框的是 max(fence_radius)。
         */
        List<CmtCommunity> communities = located
                ? open.stream().filter(c -> withinRadius(c, latE6, lngE6)).toList()
                : open;
        if (communities.isEmpty()) {
            return List.of();
        }

        List<String> communityNos = communities.stream().map(CmtCommunity::getCommunityNo).toList();
        // 只取常驻点：NEIGHBOR 是某个团的临时点，不该出现在「选自提点」列表里（ADR-005）
        List<CmtPickupPoint> pickups = pickupMapper.selectList(Wrappers.<CmtPickupPoint>lambdaQuery()
                .in(CmtPickupPoint::getCommunityNo, communityNos)
                .eq(CmtPickupPoint::getType, "STORE")
                .eq(CmtPickupPoint::getStatus, "ACTIVE"));

        Map<String, MerchantBrief> owners = loadOwners(pickups);
        Map<String, List<CmtPickupPoint>> byCommunity = pickups.stream()
                .collect(Collectors.groupingBy(CmtPickupPoint::getCommunityNo));
        /*
         * **原始机构名，批量取**。已开通聚落存的是商家起的口语名（「景滑」），
         * 而经营范围选择器要按「是社区/居委会还是村委会」分开搜法（住宅小区 vs 村，
         * 见 EstateCacheService#resolve）——这个判据只有原始官方名（「景滑村委会」）
         * 的后缀能给，开通之后就丢了，只能从 origin_code 反查回去。
         */
        List<String> originCodes = communities.stream()
                .map(CmtCommunity::getOriginCode).filter(java.util.Objects::nonNull).toList();
        Map<String, String> originNames = masterDataPort.regionNames(originCodes);
        Map<String, Boolean> originRural = masterDataPort.regionRural(originCodes);

        /*
         * **算不出距离的排最后，不是最前。**
         *
         * 没配经纬度的社区距离恒为 0（见 distance 的兜底），而升序排序会把 0 顶到第一位 ——
         * 用户打开选点页，最上面那个正是**离他最远、甚至在别的城市**的那一个。
         * 不报错、不空白，只是排序完全错了，而这条路径是 C 端的第一屏。
         *
         * 没配坐标的社区会一直有：商家提报审过之后建出来的那些只有名字与区划（ADR-013 阶段三），
         * 坐标要运营后补。所以这不是「补完数据就没事」的临时状况，得在排序里认。
         *
         * 不带定位时（all()）全部为 0，此时保持库序 —— 那种场景本来就没有「近」可言。
         */
        return communities.stream()
                .map(c -> toVO(c, byCommunity.getOrDefault(c.getCommunityNo(), List.of()), owners, latE6, lngE6,
                        c.getOriginCode() == null ? null : originNames.get(c.getOriginCode()),
                        c.getOriginCode() != null && Boolean.TRUE.equals(originRural.get(c.getOriginCode()))))
                /*
                 * **判据是「有没有坐标」，不是「距离等于 0」。**
                 *
                 * 此前这里写的是 `v.distance() == 0 ? MAX : distance` —— 用「距离为 0」
                 * 当「算不出距离」的替身。两者绝大多数时候一致，但**距离恰好为 0 还有另一种
                 * 含义：我正站在它上面**。于是站在一个小区的中心点，它被排到最后一位，
                 * 而 700 米外的邻居小区排第一。
                 *
                 * 不报错、不空白，只是把最该在第一位的那个放到了最末 ——
                 * 而这条路径是 C 端的第一屏。2026-09-04 做位置解析时撞到：
                 * 探针取种子社区的精确坐标，`nearby[0]` 给的是另一个社区。
                 *
                 * 原注释想防的事没变（没配坐标的排最后），只是把替身换成了真判据。
                 */
                .sorted(Comparator.comparingInt(
                        v -> located && v.latE6() == null ? Integer.MAX_VALUE : v.distance()))
                .toList();
    }

    @Override
    public CommunityVO detail(String communityNo) {
        CmtCommunity c = communityMapper.selectOne(Wrappers.<CmtCommunity>lambdaQuery()
                .eq(CmtCommunity::getCommunityNo, communityNo).last("limit 1"));
        if (c == null) {
            throw ai.neargo.shop.common.BizException.of(ai.neargo.shop.common.ErrorCode.NOT_FOUND);
        }
        List<CmtPickupPoint> pickups = pickupMapper.selectList(Wrappers.<CmtPickupPoint>lambdaQuery()
                .eq(CmtPickupPoint::getCommunityNo, communityNo)
                .eq(CmtPickupPoint::getType, "STORE")
                .eq(CmtPickupPoint::getStatus, "ACTIVE"));
        String originName = c.getOriginCode() == null ? null
                : masterDataPort.regionNames(java.util.List.of(c.getOriginCode())).get(c.getOriginCode());
        boolean rural = c.getOriginCode() != null
                && Boolean.TRUE.equals(masterDataPort.regionRural(java.util.List.of(c.getOriginCode())).get(c.getOriginCode()));
        return toVO(c, pickups, loadOwners(pickups), null, null, originName, rural);
    }

    @Override
    public CommunityVO.PickupVO pickupDetail(String pickupNo) {
        CmtPickupPoint p = pickupMapper.selectOne(Wrappers.<CmtPickupPoint>lambdaQuery()
                .eq(CmtPickupPoint::getPickupNo, pickupNo).last("limit 1"));
        if (p == null) {
            throw ai.neargo.shop.common.BizException.of(ai.neargo.shop.common.ErrorCode.NOT_FOUND);
        }
        MerchantBrief owner = loadOwners(List.of(p)).get(p.getOwnerRef());
        return new CommunityVO.PickupVO(p.getPickupNo(), p.getName(), p.getAddress(), 0,
                p.getOwnerRef(), owner == null ? p.getName() : owner.merchantName(),
                owner == null ? "" : owner.logo(), p.getOpenHours(), p.getArrivalDesc(),
                p.getLatE6(), p.getLngE6());
    }

    /**
     * 自提点承接方的展示信息（商家名 + logo）。
     *
     * <p><b>owner_ref 在 STORE 类型下存的是 store_no（V16 起）</b>，而名字与 logo
     * 仍挂在主体上 —— 所以要先门店 → 主体再查。
     * 返回的 Map <b>仍按 owner_ref（门店号）索引</b>，调用方不必知道这一层。
     *
     * <p>不把「门店名」拿来当展示名：顾客认的是「张记杂货」，
     * 不是「张记杂货·河坊街店」—— 自提点自己已经有名字和地址了，
     * 这里要回答的是「这是谁家的点」。
     */
    @Override
    public List<PickupCandidate> pickupCandidates(java.util.Collection<String> communityNos, String ownerStoreNo) {
        List<CmtPickupPoint> rows = new ArrayList<>();
        if (communityNos != null && !communityNos.isEmpty()) {
            rows.addAll(ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                    pickupMapper.selectList(Wrappers.<CmtPickupPoint>lambdaQuery()
                            .in(CmtPickupPoint::getCommunityNo, communityNos)
                            .in(CmtPickupPoint::getType, List.of("STORE", "PLATFORM"))
                            .eq(CmtPickupPoint::getScope, "PERMANENT")
                            .eq(CmtPickupPoint::getStatus, "ACTIVE")
                            .isNull(CmtPickupPoint::getArchivedAt))));
        }
        String owner = ownerStoreNo == null ? "" : ownerStoreNo;
        if (!owner.isBlank()) {
            rows.addAll(ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                    pickupMapper.selectList(Wrappers.<CmtPickupPoint>lambdaQuery()
                            .eq(CmtPickupPoint::getOwnerRef, owner)
                            .eq(CmtPickupPoint::getType, "STORE")
                            .isNull(CmtPickupPoint::getArchivedAt))));
        }
        java.util.LinkedHashMap<String, CmtPickupPoint> dedup = new java.util.LinkedHashMap<>();
        for (CmtPickupPoint p : rows) {
            dedup.putIfAbsent(p.getPickupNo(), p);
        }
        Map<String, String> communityName = new java.util.HashMap<>();
        List<String> cnos = dedup.values().stream().map(CmtPickupPoint::getCommunityNo).distinct().toList();
        if (!cnos.isEmpty()) {
            for (CmtCommunity c : ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                    communityMapper.selectList(Wrappers.<CmtCommunity>lambdaQuery()
                            .in(CmtCommunity::getCommunityNo, cnos)))) {
                communityName.put(c.getCommunityNo(), c.getName());
            }
        }
        return dedup.values().stream()
                .sorted(java.util.Comparator
                        .comparing((CmtPickupPoint p) -> !owner.equals(p.getOwnerRef()))
                        .thenComparing(p -> communityName.getOrDefault(p.getCommunityNo(), ""))
                        .thenComparing(CmtPickupPoint::getName))
                .map(p -> toCandidate(p, communityName.get(p.getCommunityNo())))
                .toList();
    }

    @Override
    public PickupCandidate selfBuildPickup(SelfBuildCmd cmd) {
        if (cmd == null || cmd.storeNo() == null || cmd.storeNo().isBlank()
                || cmd.name() == null || cmd.name().isBlank()
                || cmd.address() == null || cmd.address().isBlank()
                || cmd.latE6() == null || cmd.lngE6() == null) {
            throw ai.neargo.shop.common.BizException.of(ai.neargo.shop.common.ErrorCode.BAD_REQUEST);
        }
        String communityNo = cmd.communityNo();
        if (communityNo == null || communityNo.isBlank()) {
            // 就近归社区：nearby 已按围栏过滤并按距离排，第一条就是最近的
            communityNo = nearby(cmd.latE6(), cmd.lngE6()).stream()
                    .map(CommunityVO::communityNo).findFirst().orElse(null);
            if (communityNo == null) {
                // 存量社区大多没坐标，就近归不到 —— 退到商家自己范围里的社区，而不是直接拒
                communityNo = cmd.fallbackCommunityNo();
            }
        }
        if (communityNo == null) {
            throw ai.neargo.shop.common.BizException.of(ai.neargo.shop.common.ErrorCode.PICKUP_COMMUNITY_REQUIRED);
        }
        String cno = communityNo;
        CmtCommunity community = ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                communityMapper.selectOne(Wrappers.<CmtCommunity>lambdaQuery()
                        .eq(CmtCommunity::getCommunityNo, cno).last("limit 1")));
        if (community == null) {
            throw ai.neargo.shop.common.BizException.of(ai.neargo.shop.common.ErrorCode.NOT_FOUND);
        }
        // 同店同名去重：网络抖一下点两次，不该生出两条待审
        boolean dup = ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                pickupMapper.exists(Wrappers.<CmtPickupPoint>lambdaQuery()
                        .eq(CmtPickupPoint::getOwnerRef, cmd.storeNo())
                        .eq(CmtPickupPoint::getName, cmd.name().trim())
                        .isNull(CmtPickupPoint::getArchivedAt)));
        if (dup) {
            throw ai.neargo.shop.common.BizException.of(ai.neargo.shop.common.ErrorCode.CONFLICT);
        }
        CmtPickupPoint p = new CmtPickupPoint();
        p.setPickupNo(ai.neargo.shop.common.BizKey.next(ai.neargo.shop.common.BizKey.PICKUP_POINT));
        p.setCommunityNo(cno);
        p.setName(cmd.name().trim());
        p.setAddress(cmd.address().trim());
        p.setLatE6(cmd.latE6());
        p.setLngE6(cmd.lngE6());
        p.setType("STORE");
        p.setScope("PERMANENT");
        p.setOwnerRef(cmd.storeNo());
        p.setOpenHours(cmd.openHours() == null || cmd.openHours().isBlank() ? null : cmd.openHours().trim());
        p.setServiceFeeRate(0);
        p.setServiceFeePerItemMinor(0L);
        p.setFeeMode("NONE");
        p.setStatus("PENDING");
        ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() -> pickupMapper.insert(p));
        return toCandidate(p, community.getName());
    }

    private static PickupCandidate toCandidate(CmtPickupPoint p, String communityName) {
        return new PickupCandidate(p.getPickupNo(), p.getName(), p.getAddress(), p.getType(), p.getStatus(),
                p.getCommunityNo(), communityName,
                "STORE".equals(p.getType()) ? p.getOwnerRef() : null, p.getRejectReason());
    }

    private Map<String, MerchantBrief> loadOwners(List<CmtPickupPoint> pickups) {
        List<String> storeNos = pickups.stream()
                .map(CmtPickupPoint::getOwnerRef).filter(java.util.Objects::nonNull).distinct().toList();
        if (storeNos.isEmpty()) {
            return Map.of();
        }
        Map<String, String> entityOfStore = merchantQueryPort.entityOfStores(storeNos);
        Map<String, MerchantBrief> byEntity =
                merchantQueryPort.findAll(entityOfStore.values().stream().distinct().toList());
        Map<String, MerchantBrief> out = new java.util.HashMap<>();
        for (String storeNo : storeNos) {
            MerchantBrief brief = byEntity.get(entityOfStore.get(storeNo));
            if (brief != null) {
                out.put(storeNo, brief);
            }
        }
        return out;
    }

    private CommunityVO toVO(CmtCommunity c, List<CmtPickupPoint> pickups, Map<String, MerchantBrief> owners,
                             Integer latE6, Integer lngE6, String originName, boolean rural) {
        return new CommunityVO(c.getCommunityNo(), c.getName(), c.getAddress(), c.getCityCode(),
                c.getRegionCode(), c.getKind() == null ? "ESTATE" : c.getKind(), c.getParentNo(),
                distance(c.getLatE6(), c.getLngE6(), latE6, lngE6),
                pickups.stream().map(p -> {
                    MerchantBrief owner = owners.get(p.getOwnerRef());
                    return new CommunityVO.PickupVO(
                            p.getPickupNo(), p.getName(), p.getAddress(),
                            distance(p.getLatE6(), p.getLngE6(), latE6, lngE6),
                            p.getOwnerRef(),
                            owner == null ? p.getName() : owner.merchantName(),
                            owner == null ? "" : owner.logo(),
                            p.getOpenHours(), p.getArrivalDesc(),
                            p.getLatE6(), p.getLngE6());
                }).toList(),
                c.getOriginCode(), originName, rural, c.getLatE6(), c.getLngE6());
    }

    /**
     * 聚落粒度的**由内到外**排序。数字越小越内层。
     *
     * <p>ESTATE 与 VILLAGE 同档，BUILDING 更内层。
     * 这张表在 T2 就写好了（那时还没有 BUILDING、它是空转的），
     * 于是楼栋落地时**匹配主干一个字没改** —— 那正是当初先写它的理由。
     */
    private static int depthOf(String kind) {
        return CmtCommunity.KIND_BUILDING.equals(kind) ? 0 : 1;
    }

    @Override
    public LocationVO resolve(Integer latE6, Integer lngE6, boolean coarse) {
        /*
         * **模糊坐标不做聚落匹配。**
         *
         * 围栏是 1000 米（小区）到 150 米（楼栋）量级，而模糊定位（getFuzzyLocation）
         * 误差约 5 公里 —— 拿它匹配出来的聚落是噪音，不是结果。
         * 而噪音在界面上与真结果**长得一模一样**：顶栏一样显示一个小区名，
         * 商品一样列出来，只是全都不是他那一带的。
         *
         * 它够用的地方只有一个：把人落到所在的**区**。而那一条恰恰够用 ——
         * 商品池按区筛出来的货，至少都是这个区能买到的；
         * 此前这一支什么都不给，端上于是不带任何筛选条件去要商品，
         * 拿回来的是**全平台**的货：那不是一个决定，是过滤被跳过的副作用。
         */
        if (coarse || latE6 == null || lngE6 == null) {
            // 只解析一次：最近邻那一查会扫 500 行，调两次就是两次
            String district = districtOf(latE6, lngE6);
            return withNearest(coarse, district, latE6, lngE6);
        }

        List<CmtCommunity> hits = communityMapper.selectList(Wrappers.<CmtCommunity>lambdaQuery()
                        .eq(CmtCommunity::getStatus, "OPEN")
                        .isNull(CmtCommunity::getArchivedAt)).stream()
                .filter(c -> withinRadius(c, latE6, lngE6))
                .toList();
        if (hits.isEmpty()) {
            /*
             * 一个围栏都没落进不是异常：新城区就是这个状态。
             * **这一支也要给区**：坐标是精确的，区就更落得准，
             * 没理由因为「这儿还没建聚落」就退回去看全平台的货。
             */
            String district = districtOf(latE6, lngE6);
            return withNearest(false, district, latE6, lngE6);
        }

        /*
         * **层级优先于距离。** 站在楼门口时，隔壁小区的中心可能比本楼中心更近 ——
         * 按距离取会把「我在 3 幢」判成「我在隔壁小区」，而两者的商品池不同。
         * 同档之间才比距离。
         */
        CmtCommunity innermost = hits.stream()
                .min(java.util.Comparator
                        .comparingInt((CmtCommunity c) -> depthOf(c.getKind()))
                        .thenComparingInt(c -> distance(c.getLatE6(), c.getLngE6(), latE6, lngE6)))
                .orElseThrow();

        // 落到了聚落，区县直接从它挂的区划码上截 —— 不用再按坐标查一次
        String district = districtOf(innermost.getRegionCode());
        /*
         * **落进围栏时不给 nearest。** 那时 innermost 就是答案，再给一个「最近的」
         * 只会让端上有两个主语 —— 而两个主语的分叉迟早会在某一页上被选错。
         */
        /*
         * **落进围栏时 place 就是这个聚落，不再去问地名库。**
         * 聚落是我们自己维护的业务对象（围栏、商品池、开没开通），它比任何
         * 外部地名都权威；再去问一次既多花一次往返，又可能给出第二个名字。
         */
        return new LocationVO(innermost.getCommunityNo(), innermost.getName(),
                chainOf(innermost), false, district, districtName(district), null, null, -1,
                new CommunityService.PlaceVO(innermost.getName(), innermost.getAddress(),
                        "COMMUNITY", "COMMUNITY", false));
    }

    @Override
    public List<PlaceHitVO> searchPlaces(String keyword, Integer latE6, Integer lngE6, String city) {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) {
            return List.of();
        }
        List<PlaceHitVO> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // ① 已开通聚落：**唯一带 communityNo 的一档**，所以排在最前
        for (CmtCommunity c : communityMapper.selectList(Wrappers.<CmtCommunity>lambdaQuery()
                .eq(CmtCommunity::getStatus, "OPEN")
                .isNull(CmtCommunity::getArchivedAt)
                .isNotNull(CmtCommunity::getLatE6)
                .like(CmtCommunity::getName, kw)
                .last("limit " + PLACE_PER_SOURCE))) {
            if (seen.add(c.getName())) {
                out.add(new PlaceHitVO(c.getName(), c.getAddress(), c.getLatE6(), c.getLngE6(),
                        c.getCommunityNo(), "COMMUNITY"));
            }
        }

        // ② 固定地址库：问过一次就记着的那些地方
        for (ai.neargo.shop.community.entity.GeoPlace p : placeMapper.selectList(
                Wrappers.<ai.neargo.shop.community.entity.GeoPlace>lambdaQuery()
                        .like(ai.neargo.shop.community.entity.GeoPlace::getName, kw)
                        .last("limit " + PLACE_PER_SOURCE))) {
            if (seen.add(p.getName())) {
                out.add(new PlaceHitVO(p.getName(), p.getAddress(), p.getLatE6(), p.getLngE6(),
                        null, "PLACE_DB"));
            }
        }

        // ③ 地图。不可用就到此为止 —— **上面两档已经有东西了，搜索框不必消失**
        if (!geoPort.available() || mapBreaker.isOpen()) {
            return out;
        }
        List<ai.neargo.shop.spi.platform.GeoPort.Tip> tips;
        try {
            tips = latE6 != null && lngE6 != null
                    ? geoPort.around(kw, latE6, lngE6, PLACE_AROUND_M, null)
                    : geoPort.tips(kw, city);
            mapBreaker.recordSuccess();
        } catch (RuntimeException e) {
            mapBreaker.recordFailure();
            return out;
        }
        for (ai.neargo.shop.spi.platform.GeoPort.Tip t : tips) {
            if (t.latE6() == null || t.lngE6() == null) {
                // 没坐标的地点选了等于又得到一条没坐标的地址，这一页就白来了
                continue;
            }
            if (seen.add(t.name())) {
                out.add(new PlaceHitVO(t.name(), t.address(), t.latE6(), t.lngE6(), null, "MAP"));
            }
        }
        return out;
    }

    /**
     * 没落进围栏时问地名库。**模糊坐标也照问** —— 区级误差下拿回来的是
     * 那一带的街道名，比什么都不给强；具体到哪一档由 {@code kind} 说明。
     *
     * @return 取不到就 null，端上退回区县名，<b>不编地名</b>
     */
    private CommunityService.PlaceVO placeAt(Integer latE6, Integer lngE6) {
        if (latE6 == null || lngE6 == null) {
            return null;
        }
        return placeResolver.resolve(latE6, lngE6)
                .map(p -> new CommunityService.PlaceVO(p.name(), p.address(), p.kind(), p.source(), p.stale()))
                .orElse(null);
    }

    /**
     * 「没落进任何围栏」这一支：把**最近的已开通聚落**算出来（M6）。
     *
     * <p>为什么不复用 {@link #nearby}：那个带 5 公里半径，而这里要的恰恰是
     * 「半径之外还有没有」—— 用它会永远返回空，而空看起来完全正常。
     *
     * <p>没坐标就不算：{@code Geo.meters} 对空坐标没有意义，
     * 而返回一个算出来的 0 会被端上显示成「0 米」。
     */
    private LocationVO withNearest(boolean coarse, String district, Integer latE6, Integer lngE6) {
        String regionName = districtName(district);
        if (latE6 == null || lngE6 == null) {
            return new LocationVO(null, null, List.of(), coarse, district, regionName, null, null, -1,
                    placeAt(latE6, lngE6));
        }
        CmtCommunity nearest = communityMapper.selectList(Wrappers.<CmtCommunity>lambdaQuery()
                        .eq(CmtCommunity::getStatus, "OPEN")
                        .isNull(CmtCommunity::getArchivedAt)
                        .isNotNull(CmtCommunity::getLatE6)).stream()
                .min(java.util.Comparator.comparingInt(
                        c -> distance(c.getLatE6(), c.getLngE6(), latE6, lngE6)))
                .orElse(null);
        if (nearest == null) {
            return new LocationVO(null, null, List.of(), coarse, district, regionName, null, null, -1,
                    placeAt(latE6, lngE6));
        }
        int m = distance(nearest.getLatE6(), nearest.getLngE6(), latE6, lngE6);
        /*
         * **超上限只砍 no 与 name，距离照给。**
         * 端上要能说出「最近的也有 80 公里」——连距离都不给的话，
         * 那一屏只能写一句干巴巴的「这一带还没开通」，而用户无从判断差多远。
         */
        boolean within = m <= defaultBindRadiusM;
        return new LocationVO(null, null, List.of(), coarse, district, regionName,
                within ? nearest.getCommunityNo() : null,
                within ? nearest.getName() : null, m,
                placeAt(latE6, lngE6));
    }

    /**
     * 坐标 → **区县码**（6 位）。模糊定位这一级唯一站得住的结论。
     *
     * <p>走 {@code resolveRegion} 而不是自己再写一遍最近邻：那一份已经在运营裁决那一屏
     * 用了很久，两份实现迟早会对同一个坐标给出不同的区。
     *
     * <p>它给的是**街道**（9 位），这里截到区县：5 公里误差下街道那一级是猜的，
     * 而区县在这个误差里基本稳定。截前缀而不是再查一次库 —— 国标码本身就是层级前缀。
     *
     * @return 推不出来返回 null。<b>不兜底成任何一个码</b> ——
     *         兜底出来的区会让端上把一屏别处的货显示成「你这儿的」
     */
    private String districtOf(Integer latE6, Integer lngE6) {
        if (latE6 == null || lngE6 == null) {
            return null;
        }
        return masterDataPort.resolveRegion(null, latE6, lngE6).stream()
                .map(MasterDataPort.RegionSuggestion::regionCode)
                .map(CommunityServiceImpl::districtOf)
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
    }

    /** 区县名。查不到返回 null —— 顶栏宁可只说「当前定位」，也不要显示一串数字码 */
    private String districtName(String districtCode) {
        return districtCode == null ? null : masterDataPort.regionNames(List.of(districtCode)).get(districtCode);
    }

    /**
     * 从最内层沿归属链向上，由内到外。
     *
     * <p>⚠️ 这里**曾经是个桩**：注释写着「今天只有一层，二批把上溯填进来」，
     * 而二批（V321）加完 `parent_no`、楼栋也建出来之后，这一步一直没补 ——
     * 于是 `chainNos` 恒为一个元素，谁也没发现，因为当时没有任何真实调用方。
     * 第一个真实用它的是自提点匹配（站在 3 幢，要能取到小区门口那个点）。
     *
     * <p><b>只上溯一层</b>，与 V321 的模型一致（园区 › 楼，不再往下分单元）。
     * 仍然按「找到就停」写成循环并设上限：`parent_no` 是声明出来的，
     * 一条指向自己或成环的坏数据会让这里转不出来，而症状是整个下单链路挂住。
     */
    private List<String> chainOf(CmtCommunity innermost) {
        List<String> chain = new java.util.ArrayList<>();
        chain.add(innermost.getCommunityNo());
        String parent = innermost.getParentNo();
        int guard = 0;
        while (parent != null && !parent.isBlank() && guard++ < 4) {
            if (chain.contains(parent)) {
                // 成环：记一笔就停。不抛错 —— 这一条路上挂着下单，
                // 为一条坏数据让所有人下不了单是更糟的选择
                LOG.warn("[community] 归属链成环，已截断：{} → {}", innermost.getCommunityNo(), parent);
                break;
            }
            chain.add(parent);
            String current = parent;
            CmtCommunity up = ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(
                    () -> communityMapper.selectOne(
                    Wrappers.<CmtCommunity>lambdaQuery()
                            .eq(CmtCommunity::getCommunityNo, current).last("limit 1")));
            parent = up == null ? null : up.getParentNo();
        }
        return List.copyOf(chain);
    }

    /** 未传定位返回 0：端上按 0 隐藏距离展示，比编一个假距离诚实。 */
    /**
     * 这个社区算不算「附近」。
     *
     * <p><b>坐标缺失 = 不算附近，不是算 0 米。</b> {@link #distance} 对缺失兜底返回 0，
     * 若按 0 参与过滤，一个没配坐标的社区会出现在**每个人**的附近列表里、而且排第一。
     * 未知就是未知。这类社区会长期存在 —— 商家提报审过后建出来的只有名字与区划，
     * 坐标靠运营后补（ADR-013 阶段三）。
     *
     * <p>不过滤的后果实测过：用广州坐标请求，返回的是杭州的「阳光花园」，
     * 距离 1056 公里，却排在「附近社区」第一位。用户能绑上去，
     * 然后下单一件他永远取不到的货 —— <b>不是查不到，是查到了一个错的</b>，
     * 而系统全程不认为有任何异常。
     */
    private boolean withinRadius(CmtCommunity c, Integer myLatE6, Integer myLngE6) {
        if (c.getLatE6() == null || c.getLngE6() == null) {
            return false;
        }
        return distance(c.getLatE6(), c.getLngE6(), myLatE6, myLngE6) <= coverageRadiusOf(c);
    }

    /**
     * 这个聚落的覆盖半径。**用它自己的 `fence_radius`，不是全局那一个值。**
     *
     * <p>此前这里一律用 {@code shop.community.nearby-radius-m}（默认 5000）——
     * 而 `fence_radius` 这一列早就有了、运营端能设、ops 列表也显示它，
     * **只是匹配一行都没读**。于是「附近」实际是「中心点五公里内」：
     * 小区尺度上还凑合，楼栋尺度上直接不成立（五公里内可以有几十栋写字楼，
     * 排第一的大概率不是你所在的那栋）。
     *
     * <p>切换前按线上真实数据量过（2026-09-04，23 个聚落全设了 1000 米）：
     * 8 个探针的候选集收窄（如「弓村社区居委会」3 → 1），
     * <b>没有一个被收成 0</b>，两条有坐标的真实收货地址结果不变。
     *
     * <p><b>那个回落是纯防御，不对应任何现实状态</b>：这一列是
     * {@code NOT NULL DEFAULT 1000}，而唯一的写入口
     * {@link ai.neargo.shop.community.service.CommunityAdminService#setFence} 拒绝 ≤0。
     * 留着它是因为**万一某天取到 0，含义必须是「没设过」而不是「半径为零」** ——
     * 后者会让这个聚落谁也匹配不到，而它看起来一切正常：
     * 建档成功、列表里有、坐标也对，只是没有任何买家能选到它。
     */
    private int coverageRadiusOf(CmtCommunity c) {
        Integer own = c.getFenceRadius();
        return own == null || own <= 0 ? nearbyRadiusM : own;
    }

    /**
     * 围栏与排序都走它。**算法搬到了 {@link ai.neargo.shop.common.Geo}** ——
     * 围栏影响预览要拿同一个算法去数地址，各写一遍就会在边界那一圈上对不上，
     * 而两个数字看起来都是对的。行为逐字未变（同一个 111000、同一个 cos(b点纬度)）。
     */
    private int distance(Integer latE6, Integer lngE6, Integer myLatE6, Integer myLngE6) {
        return ai.neargo.shop.common.Geo.meters(latE6, lngE6, myLatE6, myLngE6);
    }
}
