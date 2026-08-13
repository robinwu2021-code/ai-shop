package ai.neargo.shop.merchant.service.impl;

import ai.neargo.shop.spi.trade.PurchaseHistoryPort;
import ai.neargo.shop.merchant.service.MerchantService;

import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.merchant.dto.MerchantVO;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.merchant.entity.MchEntityCommunity;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityCommunityMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 商家查询实现，同时是 {@link MerchantQueryPort} 的实现方 ——
 * trade/settle 需要商家信息时经 Port 调这里，不直接依赖 user 模块的实体。
 */
@Service
public class MerchantServiceImpl implements MerchantService {

    private static final String ACTIVE = "ACTIVE";

    private final MchEntityMapper merchantMapper;
    private final MchEntityCommunityMapper merchantCommunityMapper;
    private final MchStoreMapper merchantStoreMapper;
    private final PurchaseHistoryPort purchaseHistoryPort;
    private final ObjectMapper json;

    public MerchantServiceImpl(MchEntityMapper merchantMapper,
                               PurchaseHistoryPort purchaseHistoryPort,
                               ObjectMapper json,
                               MchEntityCommunityMapper merchantCommunityMapper,
                               MchStoreMapper merchantStoreMapper) {
        this.merchantStoreMapper = merchantStoreMapper;
        this.merchantCommunityMapper = merchantCommunityMapper;
        this.merchantMapper = merchantMapper;
        this.purchaseHistoryPort = purchaseHistoryPort;
        this.json = json;
    }

    @Override
    public PageData<MerchantVO> search(String keyword, String communityNo, long page, long size) {
        var q = Wrappers.<MchEntity>lambdaQuery().eq(MchEntity::getStatus, ACTIVE);
        if (keyword != null && !keyword.isBlank()) {
            q.like(MchEntity::getName, keyword);
        }
        /*
         * 可达性过滤（ADR-009）。**此前这里没有** —— 于是 C 端「附近的店」会列出
         * 送不到本社区的商家，用户点进去下单才发现提不了货。
         * 与 promoted() 共用同一个判定，避免两处口径走偏。
         */
        applyReachable(q, communityNo);
        // 认证商家优先、再按销量：新入驻的小店不会因为没销量就永远排在最后一页
        q.orderByDesc(MchEntity::getVerified).orderByDesc(MchEntity::getSalesCount);

        Page<MchEntity> result = merchantMapper.selectPage(Page.of(page, size), q);
        Map<String, MchStore> fronts = frontsOf(result.getRecords());
        return PageData.of(result.convert(m -> toVO(m, fronts)));
    }

    @Override
    public List<MerchantVO> promoted(String communityNo, Integer size) {
        int limit = size == null || size <= 0 ? 4 : size;

        var w = Wrappers.<MchEntity>lambdaQuery().eq(MchEntity::getStatus, ACTIVE);
        if (communityNo != null && !communityNo.isBlank()) {
            /*
             * 可达性过滤（ADR-009）。三档各判各的：
             *   PLATFORM  无履约半径，恒可达
             *   CITY      同城即可达（一期只有一个城市，先不按 city_code 收紧）
             *   COMMUNITY 必须在 mch_entity_community 里登记过这个社区
             * 不做兜底放行 —— 放行等于把配置错误变成「货卖到送不到的地方」。
             */
            applyReachable(w, communityNo);
        }
        /*
         * 一期无运营后台，用**入驻时间倒序**兜底 —— 正好对上这个位子的用途：
         * 新店没订单没评分，在任何按成绩排的列表里都垫底，需要一个不看历史成绩的位置。
         */
        w.orderByDesc(MchEntity::getId).last("limit " + limit);
        List<MchEntity> rows = DataScopeContext.executeWithoutScope(() -> merchantMapper.selectList(w));
        Map<String, MchStore> fronts = frontsOf(rows);
        return rows.stream().map(m -> toVO(m, fronts)).toList();
    }

    @Override
    public MerchantVO detail(String merchantNo) {
        MchEntity m = one(merchantNo);
        if (m == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return toVO(m, frontsOf(List.of(m)));
    }

    @Override
    public ai.neargo.shop.merchant.dto.MerchantScoreVO score(String merchantNo) {
        MchEntity m = one(merchantNo);
        if (m == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        // 归集 = 平台是销售主体：服务与时效是平台在做，标注出来而不是把分藏掉
        boolean platformIsSeller = ai.neargo.shop.spi.user.MerchantQueryPort.FUNDS_AGGREGATED
                .equals(m.getFundsMode());
        return ai.neargo.shop.merchant.dto.MerchantScoreVO.of(m, platformIsSeller);
    }

    @Override
    public List<ai.neargo.shop.merchant.dto.VisitedMerchantVO> visited() {
        var purchases = purchaseHistoryPort.purchasedMerchants(
                ai.neargo.shop.auth.SecurityUtils.currentUserNo());
        if (purchases.isEmpty()) {
            return List.of();
        }
        Map<String, MchEntity> merchants = merchantMapper.selectList(Wrappers.<MchEntity>lambdaQuery()
                        .in(MchEntity::getEntityNo,
                                purchases.stream().map(p -> p.merchantNo()).toList())).stream()
                .collect(java.util.stream.Collectors.toMap(MchEntity::getEntityNo, m -> m, (a, b) -> a));

        // 商家可能已被封禁/删除，但用户确实买过 —— 跳过而不是返回空壳，
        // 空壳会在「我买过的」列表里渲染成一张没有名字的卡
        return purchases.stream()
                .filter(p -> merchants.containsKey(p.merchantNo()))
                .map(p -> ai.neargo.shop.merchant.dto.VisitedMerchantVO.of(
                        merchants.get(p.merchantNo()), p.orderCount(), p.lastOrderAt()))
                .toList();
    }




    @Override
    public ai.neargo.shop.merchant.dto.MerchantAccountVO account(String merchantNo) {
        if (merchantNo == null || merchantNo.isBlank()) {
            return null;
        }
        // 绕过数据域：店主查自己天经地义，而 B 端的数据域是按 entity_no 授权的，
        // 在「刚通过审核、授权还没生效」的那一瞬会把人自己挡在外面
        MchEntity m = DataScopeContext.executeWithoutScope(() -> one(merchantNo));
        return m == null ? null : ai.neargo.shop.merchant.dto.MerchantAccountVO.of(m);
    }

    /**
     * 批量取门面（地址与营业时间的权威，V42 起主体表上没有这两列）。
     *
     * <p><b>批量而不是逐个查</b>：商家列表一页 10~50 条，逐个查就是 50 次往返，
     * 而这是首页与「店铺」tab 的主查询。
     */
    private Map<String, MchStore> frontsOf(List<MchEntity> merchants) {
        if (merchants == null || merchants.isEmpty()) {
            return Map.of();
        }
        List<String> nos = merchants.stream().map(MchEntity::getEntityNo).toList();
        return DataScopeContext.executeWithoutScope(() ->
                merchantStoreMapper.selectList(Wrappers.<MchStore>lambdaQuery()
                        .in(MchStore::getEntityNo, nos))).stream()
                .collect(java.util.stream.Collectors.toMap(
                        MchStore::getEntityNo, x -> x, (a, b) -> a));
    }

    /** 没有门面行时地址与营业时间为空 —— 不是错误，是这家店还没进过店铺设置页。 */
    private MerchantVO toVO(MchEntity m, Map<String, MchStore> fronts) {
        MchStore front = fronts.get(m.getEntityNo());
        return MerchantVO.of(m, tags(m),
                front == null ? "" : nzs(front.getAddress()),
                front == null ? "" : nzs(front.getOpenHours()));
    }

    private static String nzs(String s) {
        return s == null ? "" : s;
    }

    private MchEntity one(String merchantNo) {
        return merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                .eq(MchEntity::getEntityNo, merchantNo).last("limit 1"));
    }

    @SuppressWarnings("unchecked")
    /**
     * 可达性条件（ADR-009）。三档各判各的，<b>不做兜底放行</b> ——
     * 放行等于把配置错误变成「货卖到送不到的地方」。
     */
    private void applyReachable(
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MchEntity> w,
            String communityNo) {
        if (communityNo == null || communityNo.isBlank()) {
            return;
        }
        List<String> reach = DataScopeContext.executeWithoutScope(() ->
                        merchantCommunityMapper.selectList(Wrappers.<MchEntityCommunity>lambdaQuery()
                                .eq(MchEntityCommunity::getCommunityNo, communityNo)))
                .stream().map(MchEntityCommunity::getEntityNo).toList();
        w.and(q -> {
            // PLATFORM 无履约半径恒可达；CITY 一期只有一个城市，先不按 city_code 收紧
            q.in(MchEntity::getServiceScope, List.of("PLATFORM", "CITY"));
            if (!reach.isEmpty()) {
                q.or(x -> x.eq(MchEntity::getServiceScope, "COMMUNITY")
                        .in(MchEntity::getEntityNo, reach));
            }
        });
    }

    private List<String> tags(MchEntity m) {
        if (m.getTags() == null || m.getTags().isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(m.getTags(), List.class);
        } catch (Exception e) {
            return List.of();   // 标签是展示信息，脏数据不该让商家页打不开
        }
    }
}
