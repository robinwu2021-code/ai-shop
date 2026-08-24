package ai.neargo.shop.product.service.impl;

import ai.neargo.shop.product.service.GoodsService;
import ai.neargo.shop.product.service.StoreService;

import ai.neargo.shop.spi.trade.CartWritePort;
import ai.neargo.shop.spi.trade.StoreHistoryPort;
import ai.neargo.shop.spi.product.GoodsQueryPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.product.dto.FrequentItemVO;
import ai.neargo.shop.product.dto.RebuyResultVO;
import ai.neargo.shop.product.dto.ReorderResultVO;
import ai.neargo.shop.product.dto.StoreHomeVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 门店主页（C-10 / ADR-004 **一期主获客路径**）。
 *
 * <p>路径必须压到三步：**打开 → 常买 → 下单**。粮油副食不是「逛」出来的，
 * 第一屏是「我买过的」而不是店招 Banner —— 这决定了 {@link #frequentItems} 的排序
 * 与 {@link #rebuy} 的存在。
 */
@Service
public class StoreServiceImpl implements StoreService {

    private final MerchantQueryPort merchantPort;
    private final GoodsQueryPort goodsPort;
    private final GoodsService goodsService;
    private final StoreHistoryPort historyPort;
    private final CartWritePort cartPort;
    /** 门店货架：店主排的顺序与改的名字，买家侧的类目行按它来 */
    private final ai.neargo.shop.spi.user.StoreCategoryPort storeCategoryPort;
    /** 类目名兜底：店主没改显示名时用平台类目名 */
    private final ai.neargo.shop.product.service.CategoryService categoryService;

    public StoreServiceImpl(MerchantQueryPort merchantPort, GoodsQueryPort goodsPort,
                            GoodsService goodsService, StoreHistoryPort historyPort,
                            CartWritePort cartPort,
                            ai.neargo.shop.spi.user.StoreCategoryPort storeCategoryPort,
                            ai.neargo.shop.product.service.CategoryService categoryService) {
        this.storeCategoryPort = storeCategoryPort;
        this.categoryService = categoryService;
        this.merchantPort = merchantPort;
        this.goodsPort = goodsPort;
        this.goodsService = goodsService;
        this.historyPort = historyPort;
        this.cartPort = cartPort;
    }

    @Override
    public StoreHomeVO home(String merchantNo, String userNo, boolean favorited) {
        var merchant = merchantPort.find(merchantNo)
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));

        /*
         * **不再只给 6 个。**页面上这一段叫「全部商品」，而店内搜索与新加的类目筛选
         * 都只在这份列表里做 —— 只给 6 个的话，那句「全部」是假的，
         * 搜索也只搜得到前 6 件。
         *
         * <p>一次拿全的依据是真实量级：线上在售商品最多的店也只有个位数
         * （2026-08-23 实测 M0001/M0002 各 2 件。⚠️ 数的时候要带 `deleted=0` ——
         * 不带会数出 24 件，其中 22 件是软删的，照那个数做判断会得出错误的结论）。
         * 上限 200 是防呆：真有店铺到那个量级时，这一页要改成分页，而不是继续放大这个数。
         */
        var hot = goodsService.list(new GoodsService.GoodsQuery(
                null, merchantNo, null, null, null, 1, 200));

        // 门面文案取店主自己填的那份 —— 没有门店时给空文案，页面按空串不渲染那两块
        var frontOpt = merchantPort.storeFront(merchantNo);
        var front = frontOpt
                .map(f -> new StoreHomeVO.StoreFront(f.announcement(), f.announcementAt(),
                        f.openHours(), f.address(), f.latE6(), f.lngE6()))
                .orElseGet(() -> new StoreHomeVO.StoreFront("", null, "", "", null, null));
        /*
         * 已停业 = 门店非 ACTIVE（商家自助停用 READONLY / 平台强制下线 SUSPENDED，V96）。
         * 给标志而不是 404：扫码进来的老客要知道是店关了，不是链接坏了。
         * 这是需求 B-11.12.4「停用的门店 C 端不可见」一直没兑现的那半边。
         */
        boolean closed = frontOpt
                .map(f -> f.status() != null && !f.status().isBlank() && !"ACTIVE".equals(f.status()))
                .orElse(false);

        return new StoreHomeVO(
                new StoreHomeVO.Merchant(merchant.merchantNo(), merchant.merchantName(),
                        merchant.logo(), merchant.rating(), merchant.ratingCount(),
                        merchant.verified(), merchant.breachCount()),
                front, favorited, hot.records(), shelvesOf(merchantNo, hot.records()), closed);
    }

    /**
     * 本店货架 → 买家看到的类目行。
     *
     * <p>三条规则，每一条都对应一种「摆出来反而更糟」的情形：
     * <ul>
     *   <li><b>只列真的有货的类目</b>：货架上摆着却一件在售都没有的，点进去空手而归</li>
     *   <li><b>店主没排过的排在后面</b>：建品时自动加进货架的那些 sort=999，
     *       让它们跟在店主亲手排过的后面，而不是打乱他排的顺序</li>
     *   <li><b>没改名就用平台类目名</b>：显示名是店主的自由（「本地时鲜」），
     *       但没改时不能显示空串 —— 那会是一个点得动却没有字的 chip</li>
     * </ul>
     */
    private List<StoreHomeVO.ShelfVO> shelvesOf(String merchantNo, List<ai.neargo.shop.product.dto.GoodsVO> goods) {
        if (goods == null || goods.isEmpty()) {
            return List.of();
        }
        Map<String, Long> countByCat = goods.stream()
                .filter(g -> g.categoryNo() != null && !g.categoryNo().isBlank())
                .collect(java.util.stream.Collectors.groupingBy(
                        ai.neargo.shop.product.dto.GoodsVO::categoryNo,
                        java.util.stream.Collectors.counting()));
        if (countByCat.isEmpty()) {
            return List.of();
        }
        String storeNo = merchantPort.defaultStoreNo(merchantNo).orElse(null);
        List<ai.neargo.shop.spi.user.StoreCategoryPort.Shelf> shelves =
                storeNo == null ? List.of() : storeCategoryPort.shelvesOf(storeNo);

        Map<String, String> platformNames = new java.util.LinkedHashMap<>();
        for (var lv1 : categoryService.tree()) {
            for (var lv2 : lv1.children()) {
                platformNames.put(lv2.categoryNo(), lv2.name());
            }
        }

        List<StoreHomeVO.ShelfVO> out = new java.util.ArrayList<>();
        java.util.Set<String> listed = new java.util.LinkedHashSet<>();
        for (var sh : shelves) {
            Long n = countByCat.get(sh.categoryNo());
            if (n == null) {
                continue;
            }
            String name = sh.displayName() != null && !sh.displayName().isBlank()
                    ? sh.displayName()
                    : platformNames.getOrDefault(sh.categoryNo(), "");
            if (!name.isBlank()) {
                out.add(new StoreHomeVO.ShelfVO(sh.categoryNo(), name, n.intValue()));
                listed.add(sh.categoryNo());
            }
        }
        /*
         * 货架上没有、但确实有货的类目也要列出来（老店的存量商品早于货架这套东西）。
         * 不列的话买家会看到「类目行加起来 12 件，实际列表 24 件」，而没有任何解释。
         */
        for (var e : countByCat.entrySet()) {
            if (listed.contains(e.getKey())) {
                continue;
            }
            String name = platformNames.getOrDefault(e.getKey(), "");
            if (!name.isBlank()) {
                out.add(new StoreHomeVO.ShelfVO(e.getKey(), name, e.getValue().intValue()));
            }
        }
        return out;
    }

    @Override
    public List<FrequentItemVO> frequentItems(String merchantNo) {
        List<StoreHistoryPort.PurchasedSku> history =
                historyPort.purchasedSkus(SecurityUtils.currentUserNo(), merchantNo);
        if (history.isEmpty()) {
            return List.of();
        }

        Map<String, GoodsQueryPort.SkuSnapshot> now =
                goodsPort.snapshot(history.stream().map(StoreHistoryPort.PurchasedSku::skuNo).toList());

        return history.stream().map(h -> {
            var snap = now.get(h.skuNo());
            // 下架的商品仍然列出来但标 invalid：用户记得自己买过，直接消失会让他以为是系统丢了
            boolean invalid = snap == null || !snap.onSale();
            return new FrequentItemVO(h.goodsNo(), h.skuNo(), h.title(),
                    snap == null ? "" : snap.cover(), h.spec(),
                    snap == null ? h.lastPrice() : snap.price(),
                    h.lastPrice(), h.buyCount(), h.lastBoughtAt(),
                    snap == null ? 0 : snap.available(), invalid);
        }).toList();
    }

    @Override
    @Transactional
    public ReorderResultVO reorderFrom(String orderNo) {
        String userNo = SecurityUtils.currentUserNo();
        // Port 内部已做归属校验：别人的订单号查不出东西（订单号是可枚举的）
        var bought = historyPort.skusOfOrder(userNo, orderNo);
        if (bought.isEmpty()) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }

        var snapshots = goodsPort.snapshot(bought.stream()
                .map(StoreHistoryPort.PurchasedSku::skuNo).toList());

        int added = 0;
        List<String> dropped = new ArrayList<>();
        List<String> priceUp = new ArrayList<>();

        for (var b : bought) {
            var snap = snapshots.get(b.skuNo());
            /*
             * 失效的**显式回报**，不静默丢。
             * 悄悄少加是最糟的处理：用户以为整单都买到了，到货才发现少东西。
             */
            if (snap == null || !snap.onSale() || snap.available() <= 0) {
                dropped.add(b.title());
                continue;
            }
            // 涨价了仍然加入，但要说 —— 老客对价格敏感，悄悄涨价比涨价本身更伤复购
            if (snap.price() > b.lastPrice()) {
                priceUp.add(b.title());
            }
            cartPort.add(userNo, b.goodsNo(), b.skuNo(), Math.max(b.buyCount(), 1));
            added++;
        }
        return new ReorderResultVO(added, dropped, priceUp);
    }

    @Override
    @Transactional
    public RebuyResultVO rebuy(String merchantNo) {
        List<FrequentItemVO> items = frequentItems(merchantNo);
        int added = 0;
        List<RebuyResultVO.Skipped> skipped = new ArrayList<>();

        for (FrequentItemVO item : items) {
            if (item.invalid()) {
                // **显式告知**，不能悄悄少加 —— 悄悄少加最糟：用户以为买到了，到货才发现少东西
                skipped.add(new RebuyResultVO.Skipped(item.title(), "已下架"));
                continue;
            }
            if (item.available() <= 0) {
                skipped.add(new RebuyResultVO.Skipped(item.title(), "暂时缺货"));
                continue;
            }
            cartPort.add(SecurityUtils.currentUserNo(), item.goodsNo(), item.skuNo(), 1);
            added++;
        }
        return new RebuyResultVO(added, skipped);
    }
}
