package ai.neargo.shop.reportbridge;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderItemMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import ai.neargo.shop.trade.service.MerchantOrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商品聚合里的赠品规则（P2）。
 *
 * <p><b>这条判断住在 {@code MerchantOrderServiceImpl}，不在 DAO 里</b> ——
 * {@code ReportDailyGoodsDaoTest} 测的是「DAO 把两个数分开存取」，
 * 而「哪些行算 qty、哪些行算 giftQty」是聚合那一步决定的，那三条覆盖不到。
 * 本测试补的就是这段缝。
 *
 * <p>代价很具体：赠品行价格为 0（买赠活动送的），混进 {@code qty} 的话
 * 「送出去 100 件」会被读成「卖了 100 件」—— 数字看着很好、决策全错，而且不报错。
 */
@SpringBootTest
@ActiveProfiles("test")
class GoodsAggregateGiftTest {

    private static final String ENTITY = "M0001";
    private static final String STORE = "S-GIFT-PROBE";
    private static final String GOODS = "G-GIFT-PROBE";

    @Autowired
    private MerchantOrderService orders;

    @Autowired
    private SubOrderMapper subOrders;

    @Autowired
    private OrderItemMapper items;

    private final List<Long> seededSubs = new ArrayList<>();
    private final List<Long> seededItems = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        // 造了必须还原：留下共享种子的下场是「单独跑绿、全量红」，而报错永远不指向真因
        DataScopeContext.executeWithoutScope(() -> {
            seededItems.forEach(items::deleteById);
            seededSubs.forEach(subOrders::deleteById);
            return null;
        });
        seededItems.clear();
        seededSubs.clear();
    }

    @Test
    @DisplayName("★★★ 赠品行不进 qty、不抬销售额，单独计入 giftQty")
    void giftLinesDoNotCountAsSales() {
        String subNo = seedSubOrder();
        seedItem(subNo, 2, 2_000L, false);   // 买 2 件，2000 分
        seedItem(subNo, 5, 0L, true);        // 送 5 件，0 分

        LocalDate today = LocalDate.now();
        List<MerchantOrderService.GoodsAgg> aggs = orders.dailyGoodsAggregates(today, today).stream()
                .filter(a -> GOODS.equals(a.goodsNo()) && STORE.equals(a.storeNo()))
                .toList();

        // 对照量非零：探针没进去的话下面的断言会在空集上通过而什么都没验
        assertThat(aggs).as("探针商品没被聚合到 —— 这个测试正在空转").hasSize(1);

        MerchantOrderService.GoodsAgg a = aggs.get(0);
        assertThat(a.qty()).as("赠品混进 qty 的话这里会是 7").isEqualTo(2);
        assertThat(a.giftQty()).isEqualTo(5);
        assertThat(a.amountMinor()).as("赠品价格为 0，不该抬高销售额").isEqualTo(2_000L);
    }

    private String seedSubOrder() {
        OrdSubOrder o = new OrdSubOrder();
        long n = System.nanoTime();
        o.setSubOrderNo("SO-GIFT-PROBE-" + n);
        o.setOrderNo("O-GIFT-PROBE-" + n);
        o.setUserNo("U-GIFT-PROBE");
        o.setEntityNo(ENTITY);
        o.setStoreNo(STORE);
        // 必须落在 TRANSACTED 里 —— 聚合按它过滤，用别的值这个测试就比了个寂寞
        o.setStatus(OrdSubOrder.COMPLETED);
        o.setPayAmount(2_000L);
        o.setDeleted(0);
        DataScopeContext.executeWithoutScope(() -> subOrders.insert(o));
        seededSubs.add(o.getId());
        return o.getSubOrderNo();
    }

    private void seedItem(String subOrderNo, int qty, long amount, boolean gift) {
        OrdItem it = new OrdItem();
        it.setSubOrderNo(subOrderNo);
        it.setOrderNo("O-GIFT-PROBE");
        it.setGoodsNo(GOODS);
        it.setSkuNo(GOODS + "-SKU");
        it.setTitle("赠品探针商品");
        it.setSpec("500g");
        it.setQty(qty);
        it.setAmount(amount);
        it.setPrice(gift ? 0L : amount / qty);
        it.setIsGift(gift);
        it.setDeleted(0);
        DataScopeContext.executeWithoutScope(() -> items.insert(it));
        seededItems.add(it.getId());
    }
}
