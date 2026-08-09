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

    public CommunityServiceImpl(CommunityMapper communityMapper, PickupPointMapper pickupMapper,
                                MerchantQueryPort merchantQueryPort) {
        this.communityMapper = communityMapper;
        this.pickupMapper = pickupMapper;
        this.merchantQueryPort = merchantQueryPort;
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

        return communities.stream()
                .map(c -> toVO(c, byCommunity.getOrDefault(c.getCommunityNo(), List.of()), owners, latE6, lngE6))
                .sorted(Comparator.comparingInt(CommunityVO::distance))
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

    private Map<String, MerchantBrief> loadOwners(List<CmtPickupPoint> pickups) {
        List<String> merchantNos = pickups.stream()
                .map(CmtPickupPoint::getOwnerRef).filter(java.util.Objects::nonNull).distinct().toList();
        return merchantQueryPort.findAll(merchantNos);
    }

    private CommunityVO toVO(CmtCommunity c, List<CmtPickupPoint> pickups, Map<String, MerchantBrief> owners,
                             Integer latE6, Integer lngE6) {
        return new CommunityVO(c.getCommunityNo(), c.getName(), c.getAddress(),
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
