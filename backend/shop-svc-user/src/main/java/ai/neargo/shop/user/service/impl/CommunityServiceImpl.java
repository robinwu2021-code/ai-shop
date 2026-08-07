package ai.neargo.shop.user.service.impl;

import ai.neargo.shop.user.service.CommunityService;

import ai.neargo.shop.user.dto.CommunityVO;
import ai.neargo.shop.user.entity.CmtCommunity;
import ai.neargo.shop.user.entity.CmtPickupPoint;
import ai.neargo.shop.user.entity.UsrMerchant;
import ai.neargo.shop.user.mapper.UserMappers.CommunityMapper;
import ai.neargo.shop.user.mapper.UserMappers.MerchantMapper;
import ai.neargo.shop.user.mapper.UserMappers.PickupPointMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CommunityServiceImpl implements CommunityService {

    /** 地球上 1 度纬度 ≈ 111km。选点只需要「谁更近」的排序，这个近似足够。 */
    private static final double METERS_PER_DEGREE = 111_000d;

    private final CommunityMapper communityMapper;
    private final PickupPointMapper pickupMapper;
    private final MerchantMapper merchantMapper;

    public CommunityServiceImpl(CommunityMapper communityMapper, PickupPointMapper pickupMapper,
                                MerchantMapper merchantMapper) {
        this.communityMapper = communityMapper;
        this.pickupMapper = pickupMapper;
        this.merchantMapper = merchantMapper;
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

        Map<String, UsrMerchant> owners = loadOwners(pickups);
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
        UsrMerchant owner = loadOwners(List.of(p)).get(p.getOwnerRef());
        return new CommunityVO.PickupVO(p.getPickupNo(), p.getName(), p.getAddress(), 0,
                p.getOwnerRef(), owner == null ? p.getName() : owner.getName(),
                owner == null ? "" : owner.getLogo(), p.getOpenHours(), p.getArrivalDesc());
    }

    private Map<String, UsrMerchant> loadOwners(List<CmtPickupPoint> pickups) {
        List<String> merchantNos = pickups.stream()
                .map(CmtPickupPoint::getOwnerRef).filter(java.util.Objects::nonNull).distinct().toList();
        if (merchantNos.isEmpty()) {
            return Map.of();
        }
        return merchantMapper.selectList(Wrappers.<UsrMerchant>lambdaQuery()
                        .in(UsrMerchant::getMerchantNo, merchantNos)).stream()
                .collect(Collectors.toMap(UsrMerchant::getMerchantNo, Function.identity(), (a, b) -> a));
    }

    private CommunityVO toVO(CmtCommunity c, List<CmtPickupPoint> pickups, Map<String, UsrMerchant> owners,
                             Integer latE6, Integer lngE6) {
        return new CommunityVO(c.getCommunityNo(), c.getName(), c.getAddress(),
                distance(c.getLatE6(), c.getLngE6(), latE6, lngE6),
                pickups.stream().map(p -> {
                    UsrMerchant owner = owners.get(p.getOwnerRef());
                    return new CommunityVO.PickupVO(
                            p.getPickupNo(), p.getName(), p.getAddress(),
                            distance(p.getLatE6(), p.getLngE6(), latE6, lngE6),
                            p.getOwnerRef(),
                            owner == null ? p.getName() : owner.getName(),
                            owner == null ? "" : owner.getLogo(),
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
