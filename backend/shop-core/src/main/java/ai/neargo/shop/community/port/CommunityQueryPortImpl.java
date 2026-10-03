package ai.neargo.shop.community.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.user.CommunityQueryPort;
import ai.neargo.shop.community.entity.CmtCommunity;
import ai.neargo.shop.community.mapper.CommunityMappers.CommunityMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * {@link CommunityQueryPort} 实现。
 *
 * <p>两个方法都解除数据域：调用方是商家域在判断自己的经营范围与积分开关，
 * 而社区表本身不按商家隔离。不解除的话，返回的永远是空——且不报错。
 */
@Component
public class CommunityQueryPortImpl implements CommunityQueryPort {

    private static final String OPEN = "OPEN";

    private final CommunityMapper communityMapper;
    private final ai.neargo.shop.community.mapper.CommunityMappers.PickupPointMapper pickupPointMapper;

    /** 买家数要按围栏归属，走 C 端那条唯一判定。延迟取：与 CommunityServiceImpl 互相引用 */
    private final org.springframework.beans.factory.ObjectProvider<
            ai.neargo.shop.community.service.CommunityService> communityService;
    /** 收货地址的坐标。跨域，走 port —— 只拿点，不拿任何能识别到人的字段 */
    private final ai.neargo.shop.spi.user.UserQueryPort userQueryPort;

    public CommunityQueryPortImpl(CommunityMapper communityMapper,
                                  ai.neargo.shop.community.mapper.CommunityMappers.PickupPointMapper
                                          pickupPointMapper,
                                  org.springframework.beans.factory.ObjectProvider<
                                          ai.neargo.shop.community.service.CommunityService> communityService,
                                  ai.neargo.shop.spi.user.UserQueryPort userQueryPort) {
        this.communityMapper = communityMapper;
        this.pickupPointMapper = pickupPointMapper;
        this.communityService = communityService;
        this.userQueryPort = userQueryPort;
    }

    @Override
    public int buyerCountIn(java.util.Collection<String> communityNos) {
        if (communityNos == null || communityNos.isEmpty()) {
            return 0;
        }
        var want = new java.util.HashSet<>(communityNos);
        /*
         * 归属**复用 resolve**，不在这儿再写一遍围栏判定。
         *
         * 商家在预览里看到「这片有 12 个买家」，那 12 个必须与真正搜得到他的人是同一批 ——
         * 另算一份的话两个数字都「算对了」，只是算的不是同一件事，而他会照着这个数
         * 决定要不要做这一片。
         */
        int n = 0;
        for (var p : userQueryPort.addressPoints()) {
            String no = communityService.getObject().resolve(p.latE6(), p.lngE6(), false).innermostNo();
            if (no != null && want.contains(no)) {
                n++;
            }
        }
        return n;
    }

    @Override
    public List<String> openCommunityNos() {
        return DataScopeContext.executeWithoutScope(() ->
                        communityMapper.selectList(Wrappers.<CmtCommunity>lambdaQuery()
                                .eq(CmtCommunity::getStatus, OPEN)))
                .stream().map(CmtCommunity::getCommunityNo).toList();
    }

    @Override
    public boolean anyPointsEnabled(Collection<String> communityNos) {
        if (communityNos == null || communityNos.isEmpty()) {
            return false;
        }
        return DataScopeContext.executeWithoutScope(() ->
                        communityMapper.selectList(Wrappers.<CmtCommunity>lambdaQuery()
                                .in(CmtCommunity::getCommunityNo, communityNos)))
                .stream().anyMatch(c -> !Boolean.FALSE.equals(c.getPointsEnabled()));
    }

    @Override
    public java.util.List<String> openCommunityNosUnderRegion(String regionPrefix) {
        if (regionPrefix == null || regionPrefix.isBlank()) {
            // 空前缀会匹配一切 —— 那意味着「框了个空区划」的商家突然覆盖全平台
            return java.util.List.of();
        }
        return DataScopeContext.executeWithoutScope(() ->
                        communityMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                                .<ai.neargo.shop.community.entity.CmtCommunity>lambdaQuery()
                                .eq(ai.neargo.shop.community.entity.CmtCommunity::getStatus, "OPEN")
                                .likeRight(ai.neargo.shop.community.entity.CmtCommunity::getRegionCode,
                                        regionPrefix)))
                .stream().map(ai.neargo.shop.community.entity.CmtCommunity::getCommunityNo).toList();
    }

    @Override
    public java.util.List<String> openChildCommunityNos(java.util.Collection<String> parentNos) {
        if (parentNos == null || parentNos.isEmpty()) {
            return java.util.List.of();
        }
        return DataScopeContext.executeWithoutScope(() ->
                        communityMapper.selectList(Wrappers.<CmtCommunity>lambdaQuery()
                                .eq(CmtCommunity::getStatus, "OPEN")
                                .in(CmtCommunity::getParentNo, parentNos)))
                .stream().map(CmtCommunity::getCommunityNo).toList();
    }

    @Override
    public java.util.Map<String, int[]> coordsOfCommunities(
            java.util.Collection<String> communityNos) {
        if (communityNos == null || communityNos.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<String, int[]> out = new java.util.HashMap<>();
        DataScopeContext.executeWithoutScope(() -> communityMapper.selectList(
                        com.baomidou.mybatisplus.core.toolkit.Wrappers
                                .<ai.neargo.shop.community.entity.CmtCommunity>lambdaQuery()
                                .in(ai.neargo.shop.community.entity.CmtCommunity::getCommunityNo, communityNos)))
                .forEach(c -> {
                    // 没标过点的不放进结果 —— 调用方据此走「算不出距离」那一支。
                    // 放个 (0,0) 进去会算出一条到几内亚湾的距离，而那是个看着正常的数
                    if (c.getLatE6() != null && c.getLngE6() != null) {
                        out.put(c.getCommunityNo(), new int[]{c.getLatE6(), c.getLngE6()});
                    }
                });
        return out;
    }

    @Override
    public java.util.Map<String, String> pickupNames(java.util.Collection<String> pickupNos) {
        if (pickupNos == null || pickupNos.isEmpty()) {
            return java.util.Map.of();
        }
        // 与 communityName 同一条理由：装饰性取名，把号换成人看得懂的字
        var rows = DataScopeContext.executeWithoutScope(() ->
                pickupPointMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<ai.neargo.shop.community.entity.CmtPickupPoint>lambdaQuery()
                        .in(ai.neargo.shop.community.entity.CmtPickupPoint::getPickupNo, pickupNos)));
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        for (var r : rows) {
            if (r.getName() != null && !r.getName().isBlank()) {
                out.put(r.getPickupNo(), r.getName());
            }
        }
        return out;
    }

    @Override
    public java.util.List<PickupOption> pickupOptions(Integer latE6, Integer lngE6,
                                                      java.util.Collection<String> allowed) {
        /*
         * **归属链复用 resolve**，不在这儿再写一遍围栏判定（同本文件上面那一处）。
         * chainNos 是「从内到外」的整条链：站在 3 幢，链上有 3 幢与它所在的小区 ——
         * 两级的点都该是候选，否则楼里的人取不到小区门口那个点。
         */
        var ctx = latE6 == null || lngE6 == null
                ? null : communityService.getObject().resolve(latE6, lngE6, false);
        java.util.List<String> chain = ctx == null || ctx.chainNos() == null
                ? java.util.List.of() : ctx.chainNos();
        if (chain.isEmpty()) {
            // 一个围栏都没落进（新城区 / 没标点的地址）：没有归属链就没有候选，
            // **返回空而不是「全部」** —— 给一个跑不到的点比不给更糟
            return java.util.List.of();
        }
        var rows = DataScopeContext.executeWithoutScope(() -> pickupPointMapper.selectList(
                Wrappers.<ai.neargo.shop.community.entity.CmtPickupPoint>lambdaQuery()
                        .in(ai.neargo.shop.community.entity.CmtPickupPoint::getCommunityNo, chain)
                        .eq(ai.neargo.shop.community.entity.CmtPickupPoint::getStatus, "ACTIVE")));
        return rows.stream()
                // 空集 = 这家店没配过取货点，按兼容期放行（与 requirePickupServed 同一条约定）
                .filter(p -> allowed == null || allowed.isEmpty() || allowed.contains(p.getPickupNo()))
                .map(p -> new PickupOption(p.getPickupNo(), p.getName(), p.getAddress(),
                        p.getCommunityNo(),
                        /*
                         * 坐标为空给 -1 而不是 0：0 会被端上显示成「0 米」，那是一句假话。
                         * 存量点是手填地址建的，没有坐标不代表不能用，只是排不出远近。
                         */
                        p.getLatE6() == null || p.getLngE6() == null ? -1
                                : ai.neargo.shop.common.Geo.meters(latE6, lngE6,
                                        p.getLatE6(), p.getLngE6())))
                .sorted(java.util.Comparator
                        // 没坐标的排最后（-1 会排在最前，所以先按「有没有距离」分档）
                        .comparingInt((PickupOption o) -> o.distanceM() < 0 ? 1 : 0)
                        .thenComparingInt(PickupOption::distanceM)
                        // 平手按点号稳定排序 —— 不稳定的话同一个人两次看到的顺序会不一样
                        .thenComparing(PickupOption::pickupNo))
                .toList();
    }

    @Override
    public String communityName(String communityNo) {
        if (communityNo == null || communityNo.isBlank()) {
            return communityNo;
        }
        var row = DataScopeContext.executeWithoutScope(() ->
                communityMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<ai.neargo.shop.community.entity.CmtCommunity>lambdaQuery()
                        .eq(ai.neargo.shop.community.entity.CmtCommunity::getCommunityNo, communityNo)
                        .last("LIMIT 1")));
        return row == null || row.getName() == null ? communityNo : row.getName();
    }
}
