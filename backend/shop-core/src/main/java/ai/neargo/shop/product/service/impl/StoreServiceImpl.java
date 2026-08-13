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

    public StoreServiceImpl(MerchantQueryPort merchantPort, GoodsQueryPort goodsPort,
                            GoodsService goodsService, StoreHistoryPort historyPort,
                            CartWritePort cartPort) {
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

        // 热销前 6 个：门店主页不做分页，店主的货本来就不多
        var hot = goodsService.list(new GoodsService.GoodsQuery(
                null, merchantNo, null, null, null, 1, 6));

        // 门面文案取店主自己填的那份 —— 没有门店时给空文案，页面按空串不渲染那两块
        var front = merchantPort.storeFront(merchantNo)
                .map(f -> new StoreHomeVO.StoreFront(f.announcement(), f.openHours(), f.address()))
                .orElseGet(() -> new StoreHomeVO.StoreFront("", "", ""));

        return new StoreHomeVO(
                new StoreHomeVO.Merchant(merchant.merchantNo(), merchant.merchantName(),
                        merchant.logo(), merchant.rating(), merchant.ratingCount(),
                        merchant.verified(), merchant.breachCount()),
                front, favorited, hot.records());
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
