package ai.neargo.shop.community.service.impl;

import ai.neargo.shop.community.service.CommunityService;

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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class CommunityServiceImpl implements CommunityService {

    /** 地球上 1 度纬度 ≈ 111km。选点只需要「谁更近」的排序，这个近似足够。 */
    private static final double METERS_PER_DEGREE = 111_000d;

    private final CommunityMapper communityMapper;
    private final PickupPointMapper pickupMapper;
    /*
     * 自提点要显示「归属商家的名字与 logo」，而商家表属于 merchant 域。
     * 原先这里直接注入 MchEntityMapper —— 社区域能读写整张 mch_entity，
     * 商家域改一个列，社区列表跟着炸，且没有任何编译期提示。
     * 改走 Port 之后，社区只拿到它真正需要的两个字段。
     */
    private final MerchantQueryPort merchantQueryPort;

    /**
     * 「附近」的半径（米）。默认 5 公里 —— <b>自提是走过去取的</b>，
     * 几公里之外的自提点在物理上就不成立。
     *
     * <p>做成配置而不是常量：这条闸管着 C 端第一屏，设小了已开通社区的用户也会看到空态。
     * 真出问题改一个配置项即可，不用发版。
     */
    private final int nearbyRadiusM;

    public CommunityServiceImpl(CommunityMapper communityMapper, PickupPointMapper pickupMapper,
                                MerchantQueryPort merchantQueryPort,
                                @Value("${shop.community.nearby-radius-m:5000}") int nearbyRadiusM) {
        this.communityMapper = communityMapper;
        this.pickupMapper = pickupMapper;
        this.merchantQueryPort = merchantQueryPort;
        this.nearbyRadiusM = nearbyRadiusM;
    }

    @Override
    public List<CommunityVO> all() {
        // 复用 nearby 的组装：不传坐标 → distance 恒 0，排序退化为库序。
        // 单独写一套查询只会让「社区带哪些自提点」在两处各实现一遍
        return nearby(null, null);
    }

    @Override
    public List<CommunityVO> nearby(Integer latE6, Integer lngE6) {
        List<CmtCommunity> communities = communityMapper.selectList(Wrappers.<CmtCommunity>lambdaQuery()
                .eq(CmtCommunity::getStatus, "OPEN"));
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
        boolean located = latE6 != null && lngE6 != null;
        return communities.stream()
                .filter(c -> !located || withinRadius(c, latE6, lngE6))
                .map(c -> toVO(c, byCommunity.getOrDefault(c.getCommunityNo(), List.of()), owners, latE6, lngE6))
                .sorted(Comparator.comparingInt(
                        v -> located && v.distance() == 0 ? Integer.MAX_VALUE : v.distance()))
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
        return toVO(c, pickups, loadOwners(pickups), null, null);
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
                owner == null ? "" : owner.logo(), p.getOpenHours(), p.getArrivalDesc());
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
                             Integer latE6, Integer lngE6) {
        return new CommunityVO(c.getCommunityNo(), c.getName(), c.getAddress(), c.getCityCode(),
                distance(c.getLatE6(), c.getLngE6(), latE6, lngE6),
                pickups.stream().map(p -> {
                    MerchantBrief owner = owners.get(p.getOwnerRef());
                    return new CommunityVO.PickupVO(
                            p.getPickupNo(), p.getName(), p.getAddress(),
                            distance(p.getLatE6(), p.getLngE6(), latE6, lngE6),
                            p.getOwnerRef(),
                            owner == null ? p.getName() : owner.merchantName(),
                            owner == null ? "" : owner.logo(),
                            p.getOpenHours(), p.getArrivalDesc());
                }).toList());
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
        return distance(c.getLatE6(), c.getLngE6(), myLatE6, myLngE6) <= nearbyRadiusM;
    }

    private int distance(Integer latE6, Integer lngE6, Integer myLatE6, Integer myLngE6) {
        if (latE6 == null || lngE6 == null || myLatE6 == null || myLngE6 == null) {
            return 0;
        }
        double dLat = (latE6 - myLatE6) / 1e6 * METERS_PER_DEGREE;
        // 经度间距随纬度收缩，不乘 cos 会让高纬度地区的排序明显失真
        double dLng = (lngE6 - myLngE6) / 1e6 * METERS_PER_DEGREE * Math.cos(Math.toRadians(myLatE6 / 1e6));
        return (int) Math.round(Math.hypot(dLat, dLng));
    }
}
