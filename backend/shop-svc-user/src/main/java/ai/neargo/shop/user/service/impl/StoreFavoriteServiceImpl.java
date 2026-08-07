package ai.neargo.shop.user.service.impl;

import ai.neargo.shop.user.service.StoreFavoriteService;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.marketing.AttributionPort;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.user.dto.MerchantVO;
import ai.neargo.shop.user.entity.UsrMerchant;
import ai.neargo.shop.user.entity.UsrStoreFavorite;
import ai.neargo.shop.user.mapper.UserMappers.MerchantMapper;
import ai.neargo.shop.user.mapper.UserMappers.StoreFavoriteMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class StoreFavoriteServiceImpl implements StoreFavoriteService {

    /** 店铺码字符集：**去掉 0/O/1/I/L**，印在纸上让人手输时不会认错。 */
    private static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final int CODE_LEN = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StoreFavoriteMapper favoriteMapper;
    private final MerchantMapper merchantMapper;
    private final AttributionPort attributionPort;

    public StoreFavoriteServiceImpl(StoreFavoriteMapper favoriteMapper, MerchantMapper merchantMapper,
                                    AttributionPort attributionPort) {
        this.favoriteMapper = favoriteMapper;
        this.merchantMapper = merchantMapper;
        this.attributionPort = attributionPort;
    }

    @Override
    public List<MerchantVO.Brief> myStores() {
        String userNo = SecurityUtils.currentUserNoOrNull();
        if (userNo == null) {
            return List.of();
        }
        // 顺序刻意：**归因命中的店排最前** —— 刚扫码进来的那家就是他现在要买的那家
        Set<String> merchantNos = new LinkedHashSet<>();
        String attributed = attributionPort.attributedMerchant(userNo);
        if (attributed != null) {
            merchantNos.add(attributed);
        }
        favoriteMapper.selectList(Wrappers.<UsrStoreFavorite>lambdaQuery()
                        .eq(UsrStoreFavorite::getUserNo, userNo)
                        .orderByDesc(UsrStoreFavorite::getId))
                .forEach(f -> merchantNos.add(f.getMerchantNo()));

        return briefsOf(merchantNos);
    }

    @Override
    @Transactional
    public List<MerchantVO.Brief> toggle(String merchantNo) {
        String userNo = SecurityUtils.currentUserNo();
        UsrStoreFavorite existing = findFavorite(userNo, merchantNo);
        if (existing != null) {
            favoriteMapper.deleteById(existing.getId());
        } else {
            UsrStoreFavorite row = new UsrStoreFavorite();
            row.setUserNo(userNo);
            row.setMerchantNo(merchantNo);
            favoriteMapper.insert(row);
        }
        // 只返回收藏，不混入归因店：这个接口的语义是「收藏结果」，
        // 混进归因店会让「点了取消收藏，列表里还在」变成日常客诉
        Set<String> nos = new LinkedHashSet<>();
        favoriteMapper.selectList(Wrappers.<UsrStoreFavorite>lambdaQuery()
                        .eq(UsrStoreFavorite::getUserNo, userNo)
                        .orderByDesc(UsrStoreFavorite::getId))
                .forEach(f -> nos.add(f.getMerchantNo()));
        return briefsOf(nos);
    }

    @Override
    public boolean isFavorited(String merchantNo) {
        String userNo = SecurityUtils.currentUserNoOrNull();
        return userNo != null && findFavorite(userNo, merchantNo) != null;
    }

    @Override
    public String resolveStoreCode(String storeCode) {
        UsrMerchant m = DataScopeContext.executeWithoutScope(() ->
                merchantMapper.selectOne(Wrappers.<UsrMerchant>lambdaQuery()
                        .eq(UsrMerchant::getStoreCode, storeCode).last("limit 1")));
        if (m == null) {
            // 码不存在就 404，**不静默回退到首页** —— 静默回退会让「码印错了」
            // 这种事永远没人发现，店主一直以为在带客
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return m.getMerchantNo();
    }

    @Override
    @Transactional
    public String ensureStoreCode(String merchantNo) {
        UsrMerchant m = DataScopeContext.executeWithoutScope(() ->
                merchantMapper.selectOne(Wrappers.<UsrMerchant>lambdaQuery()
                        .eq(UsrMerchant::getMerchantNo, merchantNo).last("limit 1")));
        if (m == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (m.getStoreCode() != null && !m.getStoreCode().isBlank()) {
            return m.getStoreCode();
        }
        m.setStoreCode(newCode());
        DataScopeContext.executeWithoutScope(() -> merchantMapper.updateById(m));
        return m.getStoreCode();
    }

    /** 撞码由 {@code uk_store_code} 兜底：插入失败重试一次比预先查重更简单也更可靠。 */
    private String newCode() {
        StringBuilder sb = new StringBuilder(CODE_LEN);
        for (int i = 0; i < CODE_LEN; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private UsrStoreFavorite findFavorite(String userNo, String merchantNo) {
        return favoriteMapper.selectOne(Wrappers.<UsrStoreFavorite>lambdaQuery()
                .eq(UsrStoreFavorite::getUserNo, userNo)
                .eq(UsrStoreFavorite::getMerchantNo, merchantNo)
                .last("limit 1"));
    }

    private List<MerchantVO.Brief> briefsOf(Set<String> merchantNos) {
        if (merchantNos.isEmpty()) {
            return List.of();
        }
        List<UsrMerchant> merchants = DataScopeContext.executeWithoutScope(() ->
                merchantMapper.selectList(Wrappers.<UsrMerchant>lambdaQuery()
                        .in(UsrMerchant::getMerchantNo, merchantNos)));
        // 按传入顺序输出，而不是 DB 顺序
        List<MerchantVO.Brief> out = new ArrayList<>();
        for (String no : merchantNos) {
            merchants.stream().filter(m -> m.getMerchantNo().equals(no)).findFirst()
                    .map(MerchantVO.Brief::of).ifPresent(out::add);
        }
        return out;
    }
}
