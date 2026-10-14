package ai.neargo.shop.user.service.impl;

import ai.neargo.shop.common.Geo;
import ai.neargo.shop.common.OpenHours;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.spi.trade.PurchaseHistoryPort;
import ai.neargo.shop.spi.trade.PurchaseHistoryPort.StorePurchase;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort.StoreCard;
import ai.neargo.shop.user.dto.StoreCardVO;
import ai.neargo.shop.user.entity.UsrStoreView;
import ai.neargo.shop.user.mapper.UserMappers.StoreViewMapper;
import ai.neargo.shop.user.service.MyStoreService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 「我的店」与「附近」（TDD-C端门店化与门店门户 §2.3）。
 *
 * <p><b>分享的回报就落在这里</b>：别人点开商家分享的门店，进门户那一刻 {@link #recordView}
 * 写下「首次来源 = 分享」，这家店从此出现在对方「我的店」里。这一层不需要防刷 ——
 * 回报只落在点开的那个人自己的列表里，商家自己点自己的链接，店只会出现在他自己的列表里。
 */
@Service
public class MyStoreServiceImpl implements MyStoreService {

    private static final long DAY_MS = 86_400_000L;

    private final StoreViewMapper viewMapper;
    private final StoreDirectoryPort storeDirectory;
    private final PurchaseHistoryPort purchaseHistory;
    /** 只逛过、没买过的门店在「我的店」里留几天（2026-09-29 拍板 30 天） */
    private final int viewKeepDays;

    public MyStoreServiceImpl(StoreViewMapper viewMapper, StoreDirectoryPort storeDirectory,
                              PurchaseHistoryPort purchaseHistory,
                              @Value("${shop.mp.my-store.view-keep-days:30}") int viewKeepDays) {
        this.viewMapper = viewMapper;
        this.storeDirectory = storeDirectory;
        this.purchaseHistory = purchaseHistory;
        this.viewKeepDays = viewKeepDays;
    }

    // ------------------------------------------------------------------ 记一次进店

    @Override
    public void recordView(String userNo, String storeNo, String entityNo, String source, String inviterNo) {
        if (userNo == null || userNo.isBlank() || storeNo == null || storeNo.isBlank()) {
            return;
        }
        // 先判空：Set.of(...).contains(null) 抛 NPE —— 不带 source 的老调用方会整条 500
        String src = source != null && UsrStoreView.SOURCES.contains(source) ? source : UsrStoreView.SOURCE_LIST;
        long now = System.currentTimeMillis();
        UsrStoreView existing = find(userNo, storeNo);
        if (existing != null) {
            touch(existing, now);
            return;
        }
        UsrStoreView row = new UsrStoreView();
        row.setUserNo(userNo);
        row.setStoreNo(storeNo);
        row.setEntityNo(entityNo == null ? "" : entityNo);
        row.setFirstSource(src);
        // 只有分享来的才记分享人 —— 别的来源带过来的 inviter 是上一次会话的残留
        row.setFirstInviterNo(UsrStoreView.SOURCE_SHARE.equals(src) ? blankToNull(inviterNo) : null);
        row.setFirstAt(now);
        row.setLastAt(now);
        row.setViewCount(1);
        row.setTenantNo("MAIN");
        try {
            viewMapper.insert(row);
        } catch (DuplicateKeyException race) {
            // 两次进店同时到达：另一条已经插进去了，首次来源以它为准，这里只刷新
            UsrStoreView winner = find(userNo, storeNo);
            if (winner != null) {
                touch(winner, now);
            }
        }
    }

    private UsrStoreView find(String userNo, String storeNo) {
        return viewMapper.selectOne(Wrappers.<UsrStoreView>lambdaQuery()
                .eq(UsrStoreView::getUserNo, userNo)
                .eq(UsrStoreView::getStoreNo, storeNo)
                .last("limit 1"));
    }

    private void touch(UsrStoreView v, long now) {
        v.setLastAt(now);
        v.setViewCount((v.getViewCount() == null ? 0 : v.getViewCount()) + 1);
        viewMapper.updateById(v);
    }

    // ------------------------------------------------------------------ 我的店

    @Override
    public List<StoreCardVO> mine(String userNo, Integer latE6, Integer lngE6) {
        if (userNo == null || userNo.isBlank()) {
            return List.of();
        }
        long since = System.currentTimeMillis() - viewKeepDays * DAY_MS;
        Map<String, UsrStoreView> views = viewMapper.selectList(Wrappers.<UsrStoreView>lambdaQuery()
                        .eq(UsrStoreView::getUserNo, userNo)).stream()
                .collect(Collectors.toMap(UsrStoreView::getStoreNo, Function.identity(), (a, b) -> a));
        Map<String, StorePurchase> bought = purchaseHistory.purchasedStores(userNo).stream()
                .collect(Collectors.toMap(StorePurchase::storeNo, Function.identity(), (a, b) -> a));

        // 买过的一直在；只逛过的要在保留期内
        Set<String> storeNos = new LinkedHashSet<>(bought.keySet());
        views.values().stream()
                .filter(v -> v.getLastAt() != null && v.getLastAt() >= since)
                .forEach(v -> storeNos.add(v.getStoreNo()));
        if (storeNos.isEmpty()) {
            return List.of();
        }

        Map<String, StoreCard> cards = storeDirectory.cards(storeNos);
        List<StoreCardVO> out = new ArrayList<>();
        for (String no : storeNos) {
            StoreCard c = cards.get(no);
            if (c == null) {
                continue;   // 主体被封、门店删除：不列
            }
            UsrStoreView v = views.get(no);
            StorePurchase p = bought.get(no);
            var rel = new StoreCardVO.Relation(
                    p == null ? 0 : p.orderCount(),
                    p == null ? null : p.lastOrderAt(),
                    v == null ? null : v.getLastAt(),
                    v == null ? null : v.getFirstSource());
            out.add(toVO(c, latE6, lngE6, rel));
        }
        out.sort(Comparator.comparingLong(MyStoreServiceImpl::lastTouch).reversed()
                .thenComparing(Comparator.comparingInt(
                        (StoreCardVO x) -> x.relation().orderCount()).reversed()));
        return out;
    }

    private static long lastTouch(StoreCardVO vo) {
        var r = vo.relation();
        long a = r.lastOrderAt() == null ? 0L : r.lastOrderAt();
        long b = r.lastViewAt() == null ? 0L : r.lastViewAt();
        return Math.max(a, b);
    }

    // ------------------------------------------------------------------ 附近

    @Override
    public PageData<StoreCardVO> nearby(String userNo, Integer latE6, Integer lngE6, String communityNo,
                                        String keyword, long page, long size) {
        Set<String> mine = mine(userNo, null, null).stream()
                .map(StoreCardVO::storeNo).collect(Collectors.toSet());
        List<StoreCardVO> all = storeDirectory.reachableActive(communityNo, keyword).stream()
                .filter(c -> !mine.contains(c.storeNo()))
                .map(c -> toVO(c, latE6, lngE6, null))
                .sorted(nearbyOrder(latE6 != null && lngE6 != null))
                .toList();
        return PageData.ofAll(all, page, size);
    }

    /**
     * 有位置：有距离的按距离升序，没坐标的排在后面、之间按评分。
     * 没位置：按评分，再按店名（稳定）。
     */
    private static Comparator<StoreCardVO> nearbyOrder(boolean located) {
        Comparator<StoreCardVO> byRating = Comparator.comparingDouble(StoreCardVO::rating).reversed()
                .thenComparing(StoreCardVO::storeName, Comparator.nullsLast(Comparator.naturalOrder()));
        if (!located) {
            return byRating;
        }
        return Comparator.<StoreCardVO, Boolean>comparing(v -> v.distanceM() == null)
                .thenComparing(v -> v.distanceM() == null ? 0 : v.distanceM())
                .thenComparing(byRating);
    }

    // ------------------------------------------------------------------ 组装

    private static StoreCardVO toVO(StoreCard c, Integer latE6, Integer lngE6, StoreCardVO.Relation rel) {
        // Geo.meters 在任一坐标为空时返回 0（围栏语义：算在圈内）—— 这里必须先判空，
        // 否则没坐标的门店会以「0 米」排到最前面
        Integer distance = latE6 == null || lngE6 == null || c.latE6() == null || c.lngE6() == null
                ? null
                : Geo.meters(latE6, lngE6, c.latE6(), c.lngE6());
        return new StoreCardVO(c.storeNo(), c.storeName(), c.entityNo(), c.logo(), c.status(),
                OpenHours.openNow(c.openHours()), c.openHours(), c.address(), distance,
                c.rating(), c.ratingCount(), rel);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
