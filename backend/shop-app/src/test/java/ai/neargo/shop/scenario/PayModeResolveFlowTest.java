package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.PayModes;
import ai.neargo.shop.merchant.entity.MchQualification;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.mapper.MerchantMappers;
import ai.neargo.shop.product.entity.PrdCategoryPayMode;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.mapper.ProductMappers;
import ai.neargo.shop.product.service.PayModeService;
import ai.neargo.shop.spi.user.QualificationPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 支付方式的<b>四层取交集</b>：类目 → 主体资质 → 门店 → 商品。
 *
 * <p>四条用例各撤掉一层，其余三层保持放行 —— 这样每一条红了都能直接指向是哪一层坏了。
 * 只测「全放行时通过」是不够的：那种测试在任何一层被写死成 true 时都照样绿。
 */
@SpringBootTest
@ActiveProfiles("test")
class PayModeResolveFlowTest {

    private static final String CATEGORY = "CAT110";

    /*
     * **每条用例各自一套主体/门店/商品编号，彼此不共享。**
     *
     * 一开始写成共享 + 每次先 delete 再 insert，结果撞了唯一键：这些表都是**逻辑删除**
     * （@TableLogic），delete 只是把 deleted 置 1，而 uk_store_no 不含 deleted ——
     * 第二条用例的 insert 立刻撞上第一条软删掉的那行。
     * 换成各用各的编号，既避开这个坑，也让用例之间真正独立。
     */

    @Autowired
    private PayModeService payModeService;
    @Autowired
    private ProductMappers.GoodsMapper goodsMapper;
    @Autowired
    private ProductMappers.CategoryPayModeMapper catPayModeMapper;
    @Autowired
    private MerchantMappers.MchStoreMapper storeMapper;
    @Autowired
    private MerchantMappers.QualificationMapper qualMapper;

    @Test
    @DisplayName("★★ 四层全放行 → 线上线下都支持")
    void allFourLayersOpen() {
        Seed s = seed("ALL", true, true, true, true);
        assertThat(payModeService.availablePayModes(s.goodsNo, s.storeNo))
                .as("四层都放行时线下应当可用")
                .containsExactlyInAnyOrder(PayModes.ONLINE, PayModes.OFFLINE);
    }

    @Test
    @DisplayName("★★ 证过期 → 只剩线上（用 expire_at 造过期，**不动 status**）")
    void expiredQualificationBlocksOffline() {
        /*
         * 刻意用 expire_at 造过期而不是把 status 改成 EXPIRED —— **要测的正是「不依赖 status」**。
         *
         * 把 status 置成 EXPIRED 的是定时任务，而生产只跑 api,ops 两个 profile、没有 worker，
         * 那个任务根本不跑。所以判定必须按时间现算；改 status 来测等于测了一件生产不会发生的事。
         */
        Seed s = seed("EXPIRED", true, true, true, true);
        expireQualification(s.entityNo);
        assertThat(payModeService.availablePayModes(s.goodsNo, s.storeNo))
                .as("证过期了还允许线下收款，等于资质这道闸没接上")
                .containsExactly(PayModes.ONLINE);
    }

    @Test
    @DisplayName("★ 门店没开线下收款 → 只剩线上")
    void storeSwitchOffBlocksOffline() {
        Seed s = seed("STOREOFF", true, true, false, true);
        assertThat(payModeService.availablePayModes(s.goodsNo, s.storeNo))
                .containsExactly(PayModes.ONLINE);
    }

    @Test
    @DisplayName("★ 类目禁了线下 → 只剩线上，即便其余三层都放行")
    void categoryDenyBlocksOffline() {
        Seed s = seed("CATDENY", false, true, true, true);
        assertThat(payModeService.availablePayModes(s.goodsNo, s.storeNo))
                .as("类目这一层是取交集的一环，它说不行就该是不行")
                .containsExactly(PayModes.ONLINE);
    }

    @Test
    @DisplayName("★ 商品自己不支持 → 只剩线上")
    void goodsNotOptedInBlocksOffline() {
        Seed s = seed("GOODSOFF", true, true, true, false);
        assertThat(payModeService.availablePayModes(s.goodsNo, s.storeNo))
                .containsExactly(PayModes.ONLINE);
    }

    // ── 造数据 ──────────────────────────────────────────────────────────

    private record Seed(String entityNo, String storeNo, String goodsNo) { }

    /** 四个开关分别对应四层，默认全放行；要测哪一层就把哪一个传 false。 */
    private Seed seed(String tag, boolean categoryAllows, boolean qualified,
                      boolean storeOn, boolean goodsOptIn) {
        String entityNo = "M_PM_" + tag;
        String storeNo = "ST_PM_" + tag;
        String goodsNo = "G_PM_" + tag;
        DataScopeContext.executeWithoutScope(() -> {
            MchStore store = new MchStore();
            store.setStoreNo(storeNo);
            store.setEntityNo(entityNo);
            store.setName("四层判定测试店 " + tag);
            store.setOfflinePayEnabled(storeOn ? 1 : 0);
            store.setCodEnabled(0);
            storeMapper.insert(store);

            // 资质：默认给一张 10 年后才过期的营业执照
            if (qualified) {
                MchQualification q = new MchQualification();
                q.setQualNo("QUAL_PM_" + tag);
                q.setEntityNo(entityNo);
                q.setQualType(QualificationPort.BUSINESS_LICENSE);
                q.setQualName("营业执照");
                q.setExpireAt(System.currentTimeMillis() + 10L * 365 * 24 * 3600 * 1000);
                q.setStatus(MchQualification.VALID);
                qualMapper.insert(q);
            }

            /*
             * 类目这一层是**全局**的（prd_category_pay_mode 按 category_no，不按主体），
             * 所以先把这个类目下的行清干净再按需插 —— 否则「类目禁了」那条用例会污染其余四条。
             * 这里用物理删除语义上更准：它不是「作废一条配置」，是「把测试造的脏数据擦掉」。
             */
            catPayModeMapper.delete(Wrappers.<PrdCategoryPayMode>lambdaQuery()
                    .eq(PrdCategoryPayMode::getCategoryNo, CATEGORY));
            if (!categoryAllows) {
                PrdCategoryPayMode row = new PrdCategoryPayMode();
                row.setCategoryNo(CATEGORY);
                row.setPayMode(PayModes.OFFLINE);
                row.setAllowed(0);
                catPayModeMapper.insert(row);
            }

            PrdGoods g = new PrdGoods();
            g.setGoodsNo(goodsNo);
            g.setEntityNo(entityNo);
            g.setCategoryNo(CATEGORY);
            g.setTitle("四层判定测试货 " + tag);
            g.setType("STANDARD");
            g.setPayModes(goodsOptIn ? "[\"ONLINE\",\"OFFLINE\"]" : "[\"ONLINE\"]");
            goodsMapper.insert(g);
            return null;
        });
        return new Seed(entityNo, storeNo, goodsNo);
    }

    /** 把证改成昨天过期。**只动 expire_at**，status 保持 VALID —— 见用例注释。 */
    private void expireQualification(String entityNo) {
        DataScopeContext.executeWithoutScope(() -> {
            MchQualification q = qualMapper.selectOne(Wrappers.<MchQualification>lambdaQuery()
                    .eq(MchQualification::getEntityNo, entityNo).last("LIMIT 1"));
            q.setExpireAt(System.currentTimeMillis() - 86_400_000L);
            qualMapper.updateById(q);
            return null;
        });
    }
}
