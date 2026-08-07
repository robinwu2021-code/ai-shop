package ai.neargo.shop.user.service.impl;

import ai.neargo.shop.user.service.MerchantService;

import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.user.dto.MerchantVO;
import ai.neargo.shop.user.entity.UsrMerchant;
import ai.neargo.shop.user.mapper.UserMappers.MerchantMapper;
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

    private final MerchantMapper merchantMapper;
    private final ai.neargo.shop.spi.trade.PurchaseHistoryPort purchaseHistoryPort;
    private final ObjectMapper json;

    public MerchantServiceImpl(MerchantMapper merchantMapper,
                               ai.neargo.shop.spi.trade.PurchaseHistoryPort purchaseHistoryPort,
                               ObjectMapper json) {
        this.merchantMapper = merchantMapper;
        this.purchaseHistoryPort = purchaseHistoryPort;
        this.json = json;
    }

    @Override
    public PageData<MerchantVO> search(String keyword, long page, long size) {
        var q = Wrappers.<UsrMerchant>lambdaQuery().eq(UsrMerchant::getStatus, ACTIVE);
        if (keyword != null && !keyword.isBlank()) {
            q.like(UsrMerchant::getName, keyword);
        }
        // 认证商家优先、再按销量：新入驻的小店不会因为没销量就永远排在最后一页
        q.orderByDesc(UsrMerchant::getVerified).orderByDesc(UsrMerchant::getSalesCount);

        Page<UsrMerchant> result = merchantMapper.selectPage(Page.of(page, size), q);
        return PageData.of(result.convert(m -> MerchantVO.of(m, tags(m))));
    }

    @Override
    public MerchantVO detail(String merchantNo) {
        UsrMerchant m = one(merchantNo);
        if (m == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return MerchantVO.of(m, tags(m));
    }

    @Override
    public ai.neargo.shop.user.dto.MerchantScoreVO score(String merchantNo) {
        UsrMerchant m = one(merchantNo);
        if (m == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return ai.neargo.shop.user.dto.MerchantScoreVO.of(m);
    }

    @Override
    public List<ai.neargo.shop.user.dto.VisitedMerchantVO> visited() {
        var purchases = purchaseHistoryPort.purchasedMerchants(
                ai.neargo.shop.auth.SecurityUtils.currentUserNo());
        if (purchases.isEmpty()) {
            return List.of();
        }
        Map<String, UsrMerchant> merchants = merchantMapper.selectList(Wrappers.<UsrMerchant>lambdaQuery()
                        .in(UsrMerchant::getMerchantNo,
                                purchases.stream().map(p -> p.merchantNo()).toList())).stream()
                .collect(java.util.stream.Collectors.toMap(UsrMerchant::getMerchantNo, m -> m, (a, b) -> a));

        // 商家可能已被封禁/删除，但用户确实买过 —— 跳过而不是返回空壳，
        // 空壳会在「我买过的」列表里渲染成一张没有名字的卡
        return purchases.stream()
                .filter(p -> merchants.containsKey(p.merchantNo()))
                .map(p -> ai.neargo.shop.user.dto.VisitedMerchantVO.of(
                        merchants.get(p.merchantNo()), p.orderCount(), p.lastOrderAt()))
                .toList();
    }




    private UsrMerchant one(String merchantNo) {
        return merchantMapper.selectOne(Wrappers.<UsrMerchant>lambdaQuery()
                .eq(UsrMerchant::getMerchantNo, merchantNo).last("limit 1"));
    }

    @SuppressWarnings("unchecked")
    private List<String> tags(UsrMerchant m) {
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
