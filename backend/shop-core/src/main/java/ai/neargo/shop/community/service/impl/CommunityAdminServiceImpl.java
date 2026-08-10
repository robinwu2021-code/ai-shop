package ai.neargo.shop.community.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.community.entity.CmtCommunity;
import ai.neargo.shop.community.entity.CmtPickupPoint;
import ai.neargo.shop.community.mapper.CommunityMappers.CommunityMapper;
import ai.neargo.shop.community.mapper.CommunityMappers.PickupPointMapper;
import ai.neargo.shop.community.service.CommunityAdminService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CommunityAdminServiceImpl implements CommunityAdminService {

    private static final String OPEN = "OPEN";
    private static final String CLOSED = "CLOSED";

    private static final String NEIGHBOR = "NEIGHBOR";
    private static final String ACTIVE = "ACTIVE";
    private static final String SUSPENDED = "SUSPENDED";
    private static final String MIGRATING = "MIGRATING";

    /** 自提点状态迁移。迁移完成后只能停用 —— 旧点不再启用，新点是另一条记录。 */
    private static final Map<String, Set<String>> PICKUP_TRANSITIONS = Map.of(
            ACTIVE, Set.of(SUSPENDED, MIGRATING),
            SUSPENDED, Set.of(ACTIVE),
            MIGRATING, Set.of(SUSPENDED));

    private final CommunityMapper communityMapper;
    private final PickupPointMapper pickupMapper;

    public CommunityAdminServiceImpl(CommunityMapper communityMapper, PickupPointMapper pickupMapper) {
        this.communityMapper = communityMapper;
        this.pickupMapper = pickupMapper;
    }

    @Override
    public List<CommunityVO> communities(String keyword, boolean showClosed) {
        var w = Wrappers.<CmtCommunity>lambdaQuery();
        if (!showClosed) {
            w.eq(CmtCommunity::getStatus, OPEN);
        }
        if (keyword != null && !keyword.isBlank()) {
            w.and(x -> x.like(CmtCommunity::getName, keyword)
                    .or().like(CmtCommunity::getCommunityNo, keyword));
        }
        w.orderByDesc(CmtCommunity::getId);
        List<CmtCommunity> rows = DataScopeContext.executeWithoutScope(() -> communityMapper.selectList(w));

        // 自提点数一次算完，不逐行 count —— 社区列表是运营每天开的第一个页面
        Map<String, Long> counts = DataScopeContext.executeWithoutScope(() ->
                        pickupMapper.selectList(Wrappers.<CmtPickupPoint>lambdaQuery()
                                .select(CmtPickupPoint::getCommunityNo))).stream()
                .filter(p -> p.getCommunityNo() != null)
                .collect(Collectors.groupingBy(CmtPickupPoint::getCommunityNo, Collectors.counting()));

        return rows.stream().map(c -> toVO(c, counts.getOrDefault(c.getCommunityNo(), 0L).intValue()))
                .toList();
    }

    @Override
    @Transactional
    public CommunityVO setOpened(String communityNo, boolean opened, String operatorNo) {
        CmtCommunity c = requireCommunity(communityNo);
        /*
         * 关城只停获客，不动在途订单 —— C 端不再展示这个社区，
         * 但已经付过钱的买家仍要能收到货、能核销。把在途一起停掉，受损的是买家。
         */
        c.setStatus(opened ? OPEN : CLOSED);
        DataScopeContext.executeWithoutScope(() -> communityMapper.updateById(c));
        return toVO(c, pickupCountOf(communityNo));
    }

    @Override
    @Transactional
    public CommunityVO setFence(String communityNo, int fenceRadius, String operatorNo) {
        if (fenceRadius <= 0) {
            // 0 意味着这个社区覆盖不到任何地址，而界面上看起来只是「还没配」
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        CmtCommunity c = requireCommunity(communityNo);
        c.setFenceRadius(fenceRadius);
        DataScopeContext.executeWithoutScope(() -> communityMapper.updateById(c));
        return toVO(c, pickupCountOf(communityNo));
    }

    @Override
    public List<PickupVO> pickups(String communityNo, String type, String status) {
        var w = Wrappers.<CmtPickupPoint>lambdaQuery();
        if (communityNo != null && !communityNo.isBlank()) {
            w.eq(CmtPickupPoint::getCommunityNo, communityNo);
        }
        if (type != null && !type.isBlank()) {
            w.eq(CmtPickupPoint::getType, type);
        }
        if (status != null && !status.isBlank()) {
            w.eq(CmtPickupPoint::getStatus, status);
        }
        w.orderByDesc(CmtPickupPoint::getId);
        return DataScopeContext.executeWithoutScope(() -> pickupMapper.selectList(w))
                .stream().map(this::toVO).toList();
    }

    @Override
    @Transactional
    public PickupVO setPickupStatus(String pickupNo, String status, String operatorNo) {
        CmtPickupPoint p = requirePickup(pickupNo);
        if (!PICKUP_TRANSITIONS.getOrDefault(p.getStatus(), Set.of()).contains(status)) {
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        }
        p.setStatus(status);
        DataScopeContext.executeWithoutScope(() -> pickupMapper.updateById(p));
        return toVO(p);
    }

    @Override
    @Transactional
    public PickupVO setPickupServiceFee(String pickupNo, int serviceFeeRate, String operatorNo) {
        if (serviceFeeRate < 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        CmtPickupPoint p = requirePickup(pickupNo);
        /*
         * 邻里自提零报酬（ADR-005）：给了报酬，承接的邻居就变成团长 ——
         * 那是另一套责任与税务关系，不是「多给点钱」那么简单。
         * 库上有 CHECK 兜底，这里先拦是为了给一句人话的报错。
         */
        if (NEIGHBOR.equals(p.getType()) && serviceFeeRate != 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        p.setServiceFeeRate(serviceFeeRate);
        DataScopeContext.executeWithoutScope(() -> pickupMapper.updateById(p));
        return toVO(p);
    }

    @Override
    public List<PickupVO> riskyNeighborPickups(int minAcceptCount) {
        /*
         * 承接次数还没有统计口径（要按核销日志聚合，那是 P-2.2.5 的后半段）。
         * 这里先按「邻里点且在营业」给出候选，acceptCount30d 恒为 0 并在契约上写明 ——
         * **不编一个看起来像真的数字**：运营会照着它去处置，而它是假的。
         */
        return DataScopeContext.executeWithoutScope(() ->
                        pickupMapper.selectList(Wrappers.<CmtPickupPoint>lambdaQuery()
                                .eq(CmtPickupPoint::getType, NEIGHBOR)
                                .eq(CmtPickupPoint::getStatus, ACTIVE)))
                .stream().map(this::toVO).toList();
    }

    // ───────────────────────────────────────────────────────────────────

    private CmtCommunity requireCommunity(String communityNo) {
        CmtCommunity c = DataScopeContext.executeWithoutScope(() ->
                communityMapper.selectOne(Wrappers.<CmtCommunity>lambdaQuery()
                        .eq(CmtCommunity::getCommunityNo, communityNo).last("limit 1")));
        if (c == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return c;
    }

    private CmtPickupPoint requirePickup(String pickupNo) {
        CmtPickupPoint p = DataScopeContext.executeWithoutScope(() ->
                pickupMapper.selectOne(Wrappers.<CmtPickupPoint>lambdaQuery()
                        .eq(CmtPickupPoint::getPickupNo, pickupNo).last("limit 1")));
        if (p == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return p;
    }

    private int pickupCountOf(String communityNo) {
        Long n = DataScopeContext.executeWithoutScope(() ->
                pickupMapper.selectCount(Wrappers.<CmtPickupPoint>lambdaQuery()
                        .eq(CmtPickupPoint::getCommunityNo, communityNo)));
        return n == null ? 0 : n.intValue();
    }

    private CommunityVO toVO(CmtCommunity c, int pickupCount) {
        return new CommunityVO(c.getCommunityNo(), c.getName(), c.getCityCode(), c.getGrid(),
                OPEN.equals(c.getStatus()),
                c.getFenceRadius() == null ? 0 : c.getFenceRadius(), pickupCount,
                c.getCreatedAt() == null ? 0L
                        : c.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
    }

    private PickupVO toVO(CmtPickupPoint p) {
        return new PickupVO(p.getPickupNo(), p.getName(), p.getType(), p.getStatus(),
                p.getCommunityNo(), null,
                // STORE 点的承接方是商家，NEIGHBOR 是 C 端用户 —— 同一列两种含义，所以只在 STORE 时下发
                "STORE".equals(p.getType()) ? p.getOwnerRef() : null,
                p.getAddress(), p.getOpenHours(), p.getArrivalDesc(),
                p.getServiceFeeRate() == null ? 0 : p.getServiceFeeRate(),
                p.getServiceFeePerItemMinor() == null ? 0L : p.getServiceFeePerItemMinor(),
                p.getFeeMode(), 0,
                p.getCreatedAt() == null ? 0L
                        : p.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
    }
}
