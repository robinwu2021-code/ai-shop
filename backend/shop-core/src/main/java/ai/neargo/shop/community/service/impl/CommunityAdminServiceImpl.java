package ai.neargo.shop.community.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.BizKey;
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
    /**
     * 新建社区的默认围栏（米）。与建表默认值一致 —— 两处不一致的话，
     * 提报建出来的社区和运营建出来的会有不同的覆盖半径，而没人会想到去比。
     */
    private static final int DEFAULT_FENCE_RADIUS = 1000;
    private static final String CLOSED = "CLOSED";

    private static final String STORE = "STORE";
    private static final String NEIGHBOR = "NEIGHBOR";
    private static final String PLATFORM = "PLATFORM";
    /** 三类自提点。**PLATFORM 不能漏** —— 它的费率规则与另外两类完全不同 */
    private static final Set<String> PICKUP_TYPES = Set.of(STORE, NEIGHBOR, PLATFORM);
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
    /**
     * 只用来把 region_code 拼成人能读的路径。
     *
     * <p>走 {@code spi} 的 Port 而不是直接注 {@code platform.RegionService} ——
     * 后者是跨业务域直连，ArchUnit 第 1 条就会拦下来。规则拦的正是这种
     * 「为了一个显示名捅穿一层边界」：捅一次之后，下一个人会顺手用上 RegionService
     * 的别的方法，两个域就再也拆不开了。
     */
    private final ai.neargo.shop.spi.platform.MasterDataPort masterDataPort;

    /** 提报单（ADR-013 阶段三） */
    private final ai.neargo.shop.community.mapper.CommunityMappers.CommunityApplyMapper applyMapper;
    /** 只为把提报队列里的商家号显示成店名 —— 运营看着一串 M20260811… 判断不了任何事 */
    private final ai.neargo.shop.spi.user.MerchantQueryPort merchantQueryPort;

    public CommunityAdminServiceImpl(CommunityMapper communityMapper, PickupPointMapper pickupMapper,
                                     ai.neargo.shop.spi.platform.MasterDataPort masterDataPort,
                                     ai.neargo.shop.community.mapper.CommunityMappers
                                             .CommunityApplyMapper applyMapper,
                                     ai.neargo.shop.spi.user.MerchantQueryPort merchantQueryPort) {
        this.masterDataPort = masterDataPort;
        this.communityMapper = communityMapper;
        this.pickupMapper = pickupMapper;
        this.applyMapper = applyMapper;
        this.merchantQueryPort = merchantQueryPort;
    }

    // ------------------------------------------------------------ 商家提报新社区（阶段三）

    @Override
    @org.springframework.transaction.annotation.Transactional
    public ApplyVO submitApply(String merchantNo, String name, String address,
                               String regionCode, String note) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        /*
         * 同一家店对同一个名字只能有一条待审。
         *
         * 重复提报不会让它更快通过，只会让运营的队列里出现两条一模一样的 ——
         * 而两个人各裁一条的结果是**建出两个同名社区**，商家勾选时分不清该勾哪个。
         */
        boolean dup = DataScopeContext.executeWithoutScope(() -> applyMapper.exists(
                com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<ai.neargo.shop.community.entity.CmtCommunityApply>lambdaQuery()
                        .eq(ai.neargo.shop.community.entity.CmtCommunityApply::getEntityNo, merchantNo)
                        .eq(ai.neargo.shop.community.entity.CmtCommunityApply::getName, n)
                        .eq(ai.neargo.shop.community.entity.CmtCommunityApply::getStatus,
                                ai.neargo.shop.community.entity.CmtCommunityApply.PENDING)));
        if (dup) {
            throw new BizException(ErrorCode.CONFLICT, "这个小区你已经提报过，正在等运营处理");
        }
        var a = new ai.neargo.shop.community.entity.CmtCommunityApply();
        a.setApplyNo(ai.neargo.shop.common.BizKey.next(ai.neargo.shop.common.BizKey.COMMUNITY_APPLY));
        a.setEntityNo(merchantNo);
        a.setName(n);
        a.setAddress(address == null ? null : address.trim());
        a.setRegionCode(regionCode == null || regionCode.isBlank() ? null : regionCode.trim());
        a.setNote(note);
        a.setStatus(ai.neargo.shop.community.entity.CmtCommunityApply.PENDING);
        a.setSubmittedAt(System.currentTimeMillis());
        DataScopeContext.executeWithoutScope(() -> applyMapper.insert(a));
        return toApplyVO(a);
    }

    @Override
    public List<ApplyVO> appliesOf(String merchantNo) {
        return DataScopeContext.executeWithoutScope(() -> applyMapper.selectList(
                        com.baomidou.mybatisplus.core.toolkit.Wrappers
                                .<ai.neargo.shop.community.entity.CmtCommunityApply>lambdaQuery()
                                .eq(ai.neargo.shop.community.entity.CmtCommunityApply::getEntityNo, merchantNo)
                                .orderByDesc(ai.neargo.shop.community.entity.CmtCommunityApply::getId)))
                .stream().map(this::toApplyVO).toList();
    }

    @Override
    public List<ApplyVO> applies(String status) {
        var w = com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<ai.neargo.shop.community.entity.CmtCommunityApply>lambdaQuery();
        if (status != null && !status.isBlank()) {
            w.eq(ai.neargo.shop.community.entity.CmtCommunityApply::getStatus, status);
        }
        w.orderByDesc(ai.neargo.shop.community.entity.CmtCommunityApply::getId);
        return DataScopeContext.executeWithoutScope(() -> applyMapper.selectList(w))
                .stream().map(this::toApplyVO).toList();
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public ApplyVO decideApply(String applyNo, boolean pass, String regionCode,
                               String reason, String operatorNo) {
        if (!pass && (reason == null || reason.isBlank())) {
            // 驳回理由原样出现在商家 B 端 —— 不写的话他不知道该改什么，只会原样再提一次
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var a = DataScopeContext.executeWithoutScope(() -> applyMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<ai.neargo.shop.community.entity.CmtCommunityApply>lambdaQuery()
                        .eq(ai.neargo.shop.community.entity.CmtCommunityApply::getApplyNo, applyNo)
                        .last("limit 1")));
        if (a == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        // 裁完就是终态：再裁一次意味着同一条提报有两个结论，而通过那次已经建了社区
        if (!ai.neargo.shop.community.entity.CmtCommunityApply.PENDING.equals(a.getStatus())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }

        if (pass) {
            String code = regionCode == null || regionCode.isBlank()
                    ? a.getRegionCode() : regionCode.trim();
            /*
             * 区划挂错比不挂更糟，所以这里与 setRegion 用同一道校验：
             * 挂到一个不存在的码上不报错，只会让这个新社区在任何「按区覆盖」里都出不来 ——
             * 而运营看着界面上明明填着值。
             */
            if (code != null && code.equals(masterDataPort.regionPathName(code))) {
                throw new BizException(ErrorCode.NOT_FOUND, "区划不存在：" + code);
            }
            var c = new CmtCommunity();
            c.setCommunityNo(ai.neargo.shop.common.BizKey.next(ai.neargo.shop.common.BizKey.COMMUNITY));
            c.setName(a.getName());
            c.setAddress(a.getAddress());
            c.setRegionCode(code);
            // 审过即开城：运营随时能关，而默认关掉的话商家提报通过了却依然看不到它
            c.setStatus(OPEN);
            // 0 意味着这个社区覆盖不到任何地址，而界面上看起来只是「还没配」
            c.setFenceRadius(DEFAULT_FENCE_RADIUS);
            c.setCreatedBy(operatorNo);
            DataScopeContext.executeWithoutScope(() -> communityMapper.insert(c));
            a.setCommunityNo(c.getCommunityNo());
            a.setRegionCode(code);
        }
        a.setStatus(pass ? ai.neargo.shop.community.entity.CmtCommunityApply.APPROVED
                : ai.neargo.shop.community.entity.CmtCommunityApply.REJECTED);
        a.setReason(pass ? null : reason.trim());
        a.setDecidedAt(System.currentTimeMillis());
        a.setDecidedBy(operatorNo);
        DataScopeContext.executeWithoutScope(() -> applyMapper.updateById(a));
        return toApplyVO(a);
    }

    private ApplyVO toApplyVO(ai.neargo.shop.community.entity.CmtCommunityApply a) {
        return new ApplyVO(a.getApplyNo(), a.getEntityNo(),
                merchantQueryPort.find(a.getEntityNo())
                        .map(ai.neargo.shop.spi.user.MerchantQueryPort.MerchantBrief::merchantName)
                        .orElse(a.getEntityNo()),
                a.getName(), a.getAddress(), a.getRegionCode(),
                a.getRegionCode() == null ? null : masterDataPort.regionPathName(a.getRegionCode()),
                a.getNote(), a.getStatus(), a.getCommunityNo(), a.getReason(),
                a.getSubmittedAt() == null ? 0L : a.getSubmittedAt());
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
    @Transactional
    public CommunityVO setRegion(String communityNo, String regionCode, String operatorNo) {
        CmtCommunity c = requireCommunity(communityNo);
        String code = regionCode == null || regionCode.isBlank() ? null : regionCode.trim();
        /*
         * **挂之前先确认这个码存在。**
         *
         * 挂到一个不存在的码上不会报错，只会让这个社区在任何「按区覆盖」里都出不来 ——
         * 而运营看着界面上明明填着值，商家看着自己的货就是没人搜得到。
         * 这正是本仓库反复记录的那类无报错故障，只能在写入口拦。
         */
        // 码不存在时 regionPathName 原样返回码本身 —— 拿它与入参比对即可判断存在性，
        // 不必为此在 Port 上再开一个方法
        if (code != null && code.equals(masterDataPort.regionPathName(code))) {
            throw new ai.neargo.shop.common.BizException(
                    ai.neargo.shop.common.ErrorCode.NOT_FOUND, "区划不存在：" + code);
        }
        c.setRegionCode(code);
        c.setUpdatedBy(operatorNo);
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
    public PickupVO createPickup(CreatePickupCommand cmd, String operatorNo) {
        if (cmd == null || blank(cmd.communityNo()) || blank(cmd.name()) || blank(cmd.address())
                || cmd.type() == null || !PICKUP_TYPES.contains(cmd.type())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        // 社区必须真的存在：挂在一个不存在的社区上，这个点对谁都不可见，
        // 而运营看列表时它是「正常的」
        boolean communityOk = DataScopeContext.executeWithoutScope(() ->
                communityMapper.exists(Wrappers.<CmtCommunity>lambdaQuery()
                        .eq(CmtCommunity::getCommunityNo, cmd.communityNo())));
        if (!communityOk) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }

        /*
         * owner_ref 是**多态列**，三类的必填项完全不同：
         *   STORE    → 门店号（V16 起）。没有它，「这个点属于哪家店」表达不了，
         *              核销权限与出货门店都无从判断
         *   NEIGHBOR → 用户号，且**报酬必须为 0** —— 给了报酬他就变成团长了
         *   PLATFORM → 空。平台自己的点没有承接方
         * 传错的后果是永久错位，且不会报错 —— 所以在入口处就分开判。
         */
        String owner = blank(cmd.ownerRef()) ? null : cmd.ownerRef().trim();
        int feeRate = cmd.serviceFeeRate() == null ? 0 : cmd.serviceFeeRate();
        long feePerItem = cmd.serviceFeePerItemMinor() == null ? 0L : cmd.serviceFeePerItemMinor();
        if (feeRate < 0 || feePerItem < 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        switch (cmd.type()) {
            case STORE, NEIGHBOR -> {
                if (owner == null) {
                    throw BizException.of(ErrorCode.BAD_REQUEST);
                }
            }
            default -> owner = null;
        }
        if (NEIGHBOR.equals(cmd.type()) && (feeRate != 0 || feePerItem != 0)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        CmtPickupPoint p = new CmtPickupPoint();
        p.setPickupNo(BizKey.next(BizKey.PICKUP_POINT));
        p.setCommunityNo(cmd.communityNo());
        p.setName(cmd.name().trim());
        p.setType(cmd.type());
        // 常驻点。GROUP_INSTANCE（一团一销）后端还没实现，不在这里放开
        p.setScope("PERMANENT");
        p.setOwnerRef(owner);
        p.setAddress(cmd.address().trim());
        p.setOpenHours(blank(cmd.openHours()) ? null : cmd.openHours().trim());
        p.setArrivalDesc(blank(cmd.arrivalDesc()) ? null : cmd.arrivalDesc().trim());
        p.setServiceFeeRate(feeRate);
        p.setServiceFeePerItemMinor(feePerItem);
        p.setStatus(ACTIVE);
        DataScopeContext.executeWithoutScope(() -> pickupMapper.insert(p));
        return toVO(p);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
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
                        : c.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                c.getRegionCode(), regionPathOf(c.getRegionCode()));
    }

    /**
     * 「浙江省 / 杭州市 / 西湖区 / 北山街道」。
     *
     * <p>拼在后端而不是丢给端上：端上只拿到 330106001 的话，要么显示一串数字，
     * 要么自己按码长切片再逐级查 —— 而国标的编码规则不是端该知道的事。
     *
     * <p>区划码查不到时返回码本身：那多半是已撤并的旧码（区划每年调整，
     * 而这份数据停在 2023）。显示成空白会让人以为「没归属」，而它其实归属过。
     */
    private String regionPathOf(String regionCode) {
        if (regionCode == null || regionCode.isBlank()) {
            return null;
        }
        return masterDataPort.regionPathName(regionCode);
    }

    private PickupVO toVO(CmtPickupPoint p) {
        return new PickupVO(p.getPickupNo(), p.getName(), p.getType(), p.getStatus(),
                p.getCommunityNo(), null,
                // STORE 点的承接方是**门店**（V16 起），NEIGHBOR 是 C 端用户 ——
                // 同一列两种含义，所以只在 STORE 时下发
                "STORE".equals(p.getType()) ? p.getOwnerRef() : null,
                p.getAddress(), p.getOpenHours(), p.getArrivalDesc(),
                p.getServiceFeeRate() == null ? 0 : p.getServiceFeeRate(),
                p.getServiceFeePerItemMinor() == null ? 0L : p.getServiceFeePerItemMinor(),
                p.getFeeMode(), 0,
                p.getCreatedAt() == null ? 0L
                        : p.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
    }
}
