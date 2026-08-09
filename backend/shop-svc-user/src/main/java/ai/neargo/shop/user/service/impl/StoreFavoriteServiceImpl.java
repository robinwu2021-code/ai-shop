package ai.neargo.shop.user.service.impl;

import ai.neargo.shop.user.service.StoreFavoriteService;

import ai.neargo.shop.spi.marketing.AttributionPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.user.MerchantQueryPort.MerchantBrief;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.user.dto.StoreBriefVO;
import ai.neargo.shop.user.entity.UsrStoreFavorite;
import ai.neargo.shop.user.mapper.UserMappers.StoreFavoriteMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class StoreFavoriteServiceImpl implements StoreFavoriteService {

    private final StoreFavoriteMapper favoriteMapper;
    private final MerchantQueryPort merchantQueryPort;
    private final AttributionPort attributionPort;

    public StoreFavoriteServiceImpl(StoreFavoriteMapper favoriteMapper, MerchantQueryPort merchantQueryPort,
                                    AttributionPort attributionPort) {
        this.favoriteMapper = favoriteMapper;
        this.merchantQueryPort = merchantQueryPort;
        this.attributionPort = attributionPort;
    }

    @Override
    public List<StoreBriefVO> myStores() {
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
                .forEach(f -> merchantNos.add(f.getEntityNo()));

        return briefsOf(merchantNos);
    }

    @Override
    @Transactional
    public List<StoreBriefVO> toggle(String merchantNo) {
        String userNo = SecurityUtils.currentUserNo();
        UsrStoreFavorite existing = findFavorite(userNo, merchantNo);
        if (existing != null) {
            favoriteMapper.deleteById(existing.getId());
        } else {
            UsrStoreFavorite row = new UsrStoreFavorite();
            row.setUserNo(userNo);
            row.setEntityNo(merchantNo);
            favoriteMapper.insert(row);
        }
        // 只返回收藏，不混入归因店：这个接口的语义是「收藏结果」，
        // 混进归因店会让「点了取消收藏，列表里还在」变成日常客诉
        Set<String> nos = new LinkedHashSet<>();
        favoriteMapper.selectList(Wrappers.<UsrStoreFavorite>lambdaQuery()
                        .eq(UsrStoreFavorite::getUserNo, userNo)
                        .orderByDesc(UsrStoreFavorite::getId))
                .forEach(f -> nos.add(f.getEntityNo()));
        return briefsOf(nos);
    }

    @Override
    public boolean isFavorited(String merchantNo) {
        String userNo = SecurityUtils.currentUserNoOrNull();
        return userNo != null && findFavorite(userNo, merchantNo) != null;
    }


    private UsrStoreFavorite findFavorite(String userNo, String merchantNo) {
        return favoriteMapper.selectOne(Wrappers.<UsrStoreFavorite>lambdaQuery()
                .eq(UsrStoreFavorite::getUserNo, userNo)
                .eq(UsrStoreFavorite::getEntityNo, merchantNo)
                .last("limit 1"));
    }

    private List<StoreBriefVO> briefsOf(Set<String> merchantNos) {
        Map<String, MerchantBrief> briefs = merchantQueryPort.findAll(merchantNos);
        // 按传入顺序输出，而不是 DB 顺序 —— 归因命中的那家要排最前
        List<StoreBriefVO> out = new ArrayList<>();
        for (String no : merchantNos) {
            MerchantBrief b = briefs.get(no);
            if (b != null) {
                out.add(StoreBriefVO.of(b));
            }
        }
        return out;
    }
}
