package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.mapper.ProductMappers;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.trade.service.CartService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 加购与改数量**要校验库存**。
 *
 * <p>此前加购只判「仅活动」与「限购」，从不看库存 —— 只剩 4 件的货加 100 件照样「加入成功」，
 * 直到结账锁库存那一刻才报不足。
 *
 * <p>这里守两件事，**两件同样重要**：
 * <ul>
 *   <li>超过可售就拒 —— 否则这道校验等于没做；</li>
 *   <li><b>不许误拒</b> —— 预售商品现货为 0 仍下得了单，加购这里要是按现货拦，
 *       顾客会以为没货走掉。所以规则必须与下单锁库存逐条一致（{@link StockPort#sellable}）。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class CartStockGuardFlowTest {

    @Autowired
    private CartService cartService;
    @Autowired
    private ProductMappers.SkuMapper skuMapper;
    @Autowired
    private StockPort stockPort;
    @Autowired
    private ProductMappers.GoodsMapper goodsMapper;

    private static int seq = 0;

    /** 本条用例建的商品与 SKU —— 跑完要删，见 {@link #cleanUp} */
    private final List<ai.neargo.shop.product.entity.PrdGoods> createdGoods = new java.util.ArrayList<>();
    private final List<PrdSku> createdSkus = new java.util.ArrayList<>();

    /**
     * **建的在售商品必须删掉。**
     *
     * <p>这些商品是在售、已审核的，不删就留在全局商品池里。运营端商品池是**分页**的，
     * 多出来的货会把别的测试依赖的老种子挤出第一页 ——
     * 2026-09-21 实测：{@code OpsDataScopeFlowTest.goodsPoolIsScopedToItsMerchant}
     * 在本类加入前全量 2140 条全绿，加入后找不到 {@code G0001}/{@code G0003} 而变红。
     * 单独跑、两个类一起跑都是绿的，只有全量才积累到那个量 —— 报错也完全不指向这里。
     *
     * <p>商品号以 {@code G-CS-} 开头，字典序排在 {@code G0001} 之前，压得最准。
     */
    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        createdGoods.forEach(g -> DataScopeContext.executeWithoutScope(() -> goodsMapper.deleteById(g.getId())));
        createdSkus.forEach(k -> DataScopeContext.executeWithoutScope(() -> skuMapper.deleteById(k.getId())));
        createdGoods.clear();
        createdSkus.clear();
    }

    private void asBuyer() {
        var u = new ai.neargo.shop.auth.LoginUser(
                ai.neargo.shop.auth.Realm.CONSUMER, ai.neargo.auth.store.SubjectKind.USR,
                "U-CARTSTOCK-" + (++seq) + "-" + System.nanoTime() % 100_000L, "加购库存测试",
                List.of(), List.of(), null, null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(u, null, List.of()));
    }

    /** 造一个 SKU。{@code presaleQuota} 为 0 表示没开预售 */
    private String sku(int stock, int locked, int presaleQuota, int soldCount) {
        String skuNo = "SKU-CS-" + (++seq) + "-" + System.nanoTime() % 1_000_000L;
        PrdSku s = new PrdSku();
        s.setSkuNo(skuNo);
        s.setGoodsNo("G-CS-" + seq);
        s.setEntityNo("E-CS-" + seq);
        s.setMarket("CN");
        s.setPrice(100L);
        s.setStock(stock);
        s.setLockedStock(locked);
        s.setPresaleQuota(presaleQuota);
        s.setSoldCount(soldCount);
        s.setDeleted(0);
        DataScopeContext.executeWithoutScope(() -> skuMapper.insert(s));
        createdSkus.add(s);
        /*
         * 商品行也要建：列表要查商品快照，快照在商品不存在时跳过这一行，
         * 购物车就会走「该商品已下架」分支、available 固定为 0 —— 那样断言红了
         * 也证明不了什么，是 fixture 不全而不是逻辑错。
         */
        ai.neargo.shop.product.entity.PrdGoods g = new ai.neargo.shop.product.entity.PrdGoods();
        g.setGoodsNo(s.getGoodsNo());
        g.setEntityNo(s.getEntityNo());
        g.setTitle("加购库存测试货 " + seq);
        g.setType("STANDARD");
        g.setOnSale(true);
        g.setAuditStatus("APPROVED");
        DataScopeContext.executeWithoutScope(() -> goodsMapper.insert(g));
        createdGoods.add(g);
        return skuNo;
    }

    @Test
    @DisplayName("★★★ 加购超过可售就拒 —— 此前只剩 4 件也能加 100 件")
    void addBeyondSellableIsRejected() {
        String skuNo = sku(4, 0, 0, 0);
        asBuyer();

        assertThatThrownBy(() -> cartService.add("G-CS", skuNo, 5))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode())
                        .isEqualTo(ErrorCode.STOCK_NOT_ENOUGH));
    }

    @Test
    @DisplayName("★★ 刚好等于可售：放行 —— 边界别写成 >= 把最后一件卡掉")
    void addExactlySellableIsAllowed() {
        String skuNo = sku(4, 0, 0, 0);
        asBuyer();

        assertThat(cartService.add("G-CS", skuNo, 4)).isNotNull();
    }

    @Test
    @DisplayName("★★★ 判的是车内总量：车里已有 3、再加 2，看的是 5 而不是 2")
    void checksTotalQuantityInCartNotJustThisAdd() {
        String skuNo = sku(4, 0, 0, 0);
        asBuyer();

        cartService.add("G-CS", skuNo, 3);
        assertThatThrownBy(() -> cartService.add("G-CS", skuNo, 2))
                .as("只看这一次加的 2 件的话，4 件的货能被加到 5 件")
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("★★★ 被别人锁掉的不算可售：总量 10、锁了 6，只能加 4")
    void lockedStockIsNotSellable() {
        String skuNo = sku(10, 6, 0, 0);
        asBuyer();

        assertThat(cartService.add("G-CS", skuNo, 4)).isNotNull();
        assertThatThrownBy(() -> cartService.add("G-CS", skuNo, 1))
                .as("按总量 10 判的话，别人下单锁住的那 6 件会被重复卖")
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("★★★ 预售不误拒：现货 0、预售额度 10，照样加得进 —— 下单那边就是这么放行的")
    void presaleIsNotFalselyRejected() {
        String skuNo = sku(0, 0, 10, 3);   // 额度 10、已售 3 → 余 7
        asBuyer();

        assertThat(cartService.add("G-CS", skuNo, 7))
                .as("按现货拦的话预售商品永远加不进购物车，而它其实下得了单")
                .isNotNull();
        assertThatThrownBy(() -> cartService.add("G-CS", skuNo, 1))
                .as("预售也有硬顶：余 7 就是 7")
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("★★ 改数量：加量要校验，减量永远放行 —— 否则库存掉了之后车里的货减都减不下来")
    void updateChecksIncreaseButAllowsDecrease() {
        String skuNo = sku(5, 0, 0, 0);
        asBuyer();
        cartService.add("G-CS", skuNo, 5);

        // 库存被别人锁掉 3 件，可售掉到 2
        DataScopeContext.executeWithoutScope(() -> skuMapper.lockStock(skuNo, 3));

        assertThatThrownBy(() -> cartService.update(skuNo, 6))
                .as("加量超可售要拒")
                .isInstanceOf(BizException.class);
        assertThat(cartService.update(skuNo, 2))
                .as("车里躺着 5 件而可售只剩 2：必须允许他减到 2")
                .isNotNull();
    }

    @Test
    @DisplayName("★★★ 购物车列表下发的可售数与加购校验同一个 —— 预售商品不许显示成「售罄」")
    void cartListShowsTheSameSellableAsTheGuard() {
        String skuNo = sku(0, 0, 10, 3);   // 现货 0，预售余 7
        asBuyer();
        var items = cartService.add("G-CS", skuNo, 2);

        var line = items.stream().filter(i -> skuNo.equals(i.skuNo())).findFirst().orElseThrow();
        assertThat(line.available())
                .as("下发的是只看现货的 0 的话，端上会把这件显示成「售罄」、步进器卡在 0 ——"
                        + "而加购校验刚放它进来，同一件货两处说法相反")
                .isEqualTo(7);
    }

    @Test
    @DisplayName("sellable 查不到的 SKU 是 0，不是「不限」")
    void unknownSkuIsZeroNotUnlimited() {
        assertThat(stockPort.sellable("SKU-DOES-NOT-EXIST")).isZero();
    }
}
