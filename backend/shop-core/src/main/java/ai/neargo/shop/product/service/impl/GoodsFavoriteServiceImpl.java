package ai.neargo.shop.product.service.impl;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.config.ModuleDataSources;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.product.dto.GoodsVO;
import ai.neargo.shop.product.entity.PrdGoodsFavorite;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsFavoriteMapper;
import ai.neargo.shop.product.service.GoodsFavoriteService;
import ai.neargo.shop.product.service.GoodsService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class GoodsFavoriteServiceImpl implements GoodsFavoriteService {

    private final GoodsFavoriteMapper favoriteMapper;
    private final GoodsService goodsService;

    public GoodsFavoriteServiceImpl(GoodsFavoriteMapper favoriteMapper, GoodsService goodsService) {
        this.favoriteMapper = favoriteMapper;
        this.goodsService = goodsService;
    }

    @Override
    @Transactional(transactionManager = ModuleDataSources.MEMBER_MARKETING_TX_MANAGER)
    public boolean toggle(String goodsNo) {
        String userNo = SecurityUtils.currentUserNo();
        // 收藏的商品要真实存在 —— 不在就抛 NOT_FOUND，别往表里写一条指向虚空的边
        GoodsVO goods = goodsService.detail(goodsNo);
        if (find(userNo, goodsNo) != null) {
            // 真删：软删行会占着唯一键，下一次收藏就撞（见 PrdGoodsFavorite）
            favoriteMapper.purge(userNo, goodsNo);
            return false;
        }
        PrdGoodsFavorite row = new PrdGoodsFavorite();
        row.setUserNo(userNo);
        row.setGoodsNo(goodsNo);
        row.setEntityNo(goods.merchant() == null ? null : goods.merchant().merchantNo());
        try {
            favoriteMapper.insert(row);
        } catch (org.springframework.dao.DuplicateKeyException concurrent) {
            // 同一人对同一件商品连点两次：另一下已经收藏上了，结果都是「已收藏」
        }
        return true;
    }

    @Override
    public boolean isFavorited(String goodsNo) {
        String userNo = SecurityUtils.currentUserNoOrNull();
        return userNo != null && find(userNo, goodsNo) != null;
    }

    @Override
    public PageData<GoodsVO> page(long page, long size) {
        String userNo = SecurityUtils.currentUserNo();
        Page<PrdGoodsFavorite> p = favoriteMapper.selectPage(new Page<>(page, size),
                GoodsFavoriteMapper.goodsOf(userNo)
                        // 同一秒收藏的两件按 id 兜底 —— 只按 created_at 排，顺序会在两次请求之间跳
                        .orderByDesc(PrdGoodsFavorite::getCreatedAt)
                        .orderByDesc(PrdGoodsFavorite::getId));
        List<String> nos = p.getRecords().stream().map(PrdGoodsFavorite::getGoodsNo).toList();
        Map<String, GoodsVO> byNo = goodsService.detailAll(nos);
        // 顺序按收藏时间，不按 detailAll 查出来的顺序（IN 不保证返回序）
        List<GoodsVO> records = nos.stream().map(byNo::get).filter(Objects::nonNull)
                .map(v -> v.withViewer(true, null)).toList();
        return PageData.of(records, p.getTotal(), p.getCurrent(), p.getSize());
    }

    private PrdGoodsFavorite find(String userNo, String goodsNo) {
        return favoriteMapper.selectOne(GoodsFavoriteMapper.goodsOf(userNo)
                .eq(PrdGoodsFavorite::getGoodsNo, goodsNo)
                .last("limit 1"));
    }
}
