package ai.neargo.shop.user.service.impl;

import ai.neargo.shop.user.service.StoreFavoriteService;

import ai.neargo.shop.spi.marketing.AttributionPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
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
import java.util.Set;

@Service
public class StoreFavoriteServiceImpl implements StoreFavoriteService {

    private final StoreFavoriteMapper favoriteMapper;
    private final MerchantQueryPort merchantQueryPort;
    private final AttributionPort attributionPort;
    private final StoreDirectoryPort storeDirectory;

    public StoreFavoriteServiceImpl(StoreFavoriteMapper favoriteMapper, MerchantQueryPort merchantQueryPort,
                                    AttributionPort attributionPort, StoreDirectoryPort storeDirectory) {
        this.favoriteMapper = favoriteMapper;
        this.merchantQueryPort = merchantQueryPort;
        this.attributionPort = attributionPort;
        this.storeDirectory = storeDirectory;
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
                .forEach(f -> merchantNos.add(f.getStoreNo() == null ? f.getEntityNo() : f.getStoreNo()));

        return briefsOf(merchantNos);
    }

    @Override
    @Transactional
    public List<StoreBriefVO> toggle(String merchantNo) {
        String userNo = SecurityUtils.currentUserNo();
        var store = storeDirectory.resolve(merchantNo)
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
        UsrStoreFavorite existing = findFavorite(userNo, store.storeNo());
        if (existing != null) {
            // 真删：deleteById 走全局逻辑删除，那一行还占着唯一键，再收藏就撞
            favoriteMapper.purgeById(existing.getId());
        } else {
            UsrStoreFavorite row = new UsrStoreFavorite();
            row.setUserNo(userNo);
            row.setEntityNo(store.entityNo());
            row.setStoreNo(store.storeNo());
            favoriteMapper.insert(row);
        }
        // 只返回收藏，不混入归因店：这个接口的语义是「收藏结果」，
        // 混进归因店会让「点了取消收藏，列表里还在」变成日常客诉
        Set<String> nos = new LinkedHashSet<>();
        favoriteMapper.selectList(Wrappers.<UsrStoreFavorite>lambdaQuery()
                        .eq(UsrStoreFavorite::getUserNo, userNo)
                        .orderByDesc(UsrStoreFavorite::getId))
                .forEach(f -> nos.add(f.getStoreNo() == null ? f.getEntityNo() : f.getStoreNo()));
        return briefsOf(nos);
    }

    @Override
    public List<StoreBriefVO> favorites() {
        String userNo = SecurityUtils.currentUserNo();
        Set<String> nos = new LinkedHashSet<>();
        favoriteMapper.selectList(Wrappers.<UsrStoreFavorite>lambdaQuery()
                        .eq(UsrStoreFavorite::getUserNo, userNo)
                        .orderByDesc(UsrStoreFavorite::getId))
                .forEach(f -> nos.add(f.getStoreNo() == null ? f.getEntityNo() : f.getStoreNo()));
        return briefsOf(nos);
    }

    @Override
    public boolean isFavorited(String merchantNo) {
        String userNo = SecurityUtils.currentUserNoOrNull();
        return userNo != null && findFavorite(userNo, merchantNo) != null;
    }


    private UsrStoreFavorite findFavorite(String userNo, String merchantNo) {
        var store = storeDirectory.resolve(merchantNo);
        if (store.isEmpty()) return null;
        var c = store.get();
        return favoriteMapper.selectList(Wrappers.<UsrStoreFavorite>lambdaQuery()
                .eq(UsrStoreFavorite::getUserNo, userNo)
                .eq(UsrStoreFavorite::getEntityNo, c.entityNo()))
                .stream().filter(f -> c.storeNo().equals(f.getStoreNo())
                        || (f.getStoreNo() == null && storeDirectory.resolve(f.getEntityNo())
                            .map(old -> old.storeNo().equals(c.storeNo())).orElse(false)))
                .findFirst().orElse(null);
    }

    private List<StoreBriefVO> briefsOf(Set<String> nos) {
        List<StoreBriefVO> out = new ArrayList<>();
        Set<String> emitted = new LinkedHashSet<>();
        for (String no : nos) {
            storeDirectory.resolve(no).ifPresent(store -> {
                if (!emitted.add(store.storeNo())) return;
                var b = merchantQueryPort.findAll(Set.of(store.entityNo())).get(store.entityNo());
                out.add(new StoreBriefVO(store.entityNo(), store.storeName(), store.logo(),
                        store.rating(), b != null && b.verified(), b == null ? 0 : b.breachCount(),
                        store.storeNo()));
            });
        }
        return out;
    }

    /**
     * 按店数收藏人数。
     *
     * <p><b>不需要 executeWithoutScope</b>，这一点是实测出来的：第一版写了它，
     * 理由是「usr_store_favorite 是带域表、B 端 SELF 维度会 fail-closed 拼成 1=0」——
     * 而消融（撤掉它跑 {@code M9aOpsFlowTest#bizProfileShowsFavoriteCount}）照样绿。
     * 查 {@code DataScopeRegistration} 才知道：<b>登记的 114 张表里没有任何 usr_ 表</b>，
     * 数据域是商家/门店维度的，用户域的表本来就不受它管。
     *
     * <p>留这段话是因为那个多余的 bypass 会制造一种错觉 ——
     * 「这里有个坑已经被防住了」，而真相是这里没有那个坑。
     * 真要防的是下面这件：<b>这是本类唯一跨所有用户的查询</b>，
     * 其余方法都按 {@code SecurityUtils.currentUserNo()} 过滤。
     * 谁顺手给它加一个 userNo 条件，商家页就会永远显示 0 或 1。
     */
    @Override
    public int countByMerchant(String merchantNo) {
        if (merchantNo == null || merchantNo.isBlank()) {
            return 0;
        }
        return favoriteMapper.countUsers(merchantNo);
    }
}
