package ai.neargo.shop.product.service;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.entity.PrdStoreStock;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StoreStockMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「这家店这个 SKU 还能卖几件」——<b>覆盖层规则的唯一一份实现</b>。
 *
 * <p>规则（与 {@code PrdStoreStock} / {@code PrdStoreGoods} 的类注释逐字相同）：
 * <ul>
 *   <li>这个 SKU 一条店级行都没有 → 走主体总量，行为与单店时代相同
 *   <li>一旦有了任意一条 → 该 SKU 整体转为按店管理，<b>没有行的店视为 0</b>
 * </ul>
 *
 * <p><b>判据是「有没有行」而不是「这家店有没有行」</b>：后者会让商家给 A 店设了库存之后，
 * B 店因为没有行而回退到主体总量，变成事实上的无限供应。前者是「你开始分店管了，
 * 那每家店都得设」—— 少卖可恢复，超卖不可恢复。
 *
 * <p><b>为什么要抽出来</b>：这条规则此前在 {@code StockPortImpl}（下单扣减）与
 * {@code AiGoodsServiceImpl}（进销存问答）各有一份，而买家侧详情压根没有，
 * 于是「页面显示有货 → 下单提示库存不足」这类分歧只能靠人发现。
 * {@code StockPortImpl} 自己的注释就写着「两处判据不一致会出现半边账，
 * 而两个数都还是正的，没有任何地方会报错」。
 *
 * <p><b>三份已经合成一份</b>（2026-09-30）：{@code StockPortImpl#sellable}、
 * {@code AiGoodsServiceImpl#stock}、买家侧详情都走这里。**新代码一律用这里，不要再写第四份。**
 */
@Component
public class StoreStockReader {

    private final SkuMapper skuMapper;
    private final StoreStockMapper storeStockMapper;

    public StoreStockReader(SkuMapper skuMapper, StoreStockMapper storeStockMapper) {
        this.skuMapper = skuMapper;
        this.storeStockMapper = storeStockMapper;
    }

    /** 这个 SKU 是不是已经按店管理（有任意一条店级行）。 */
    public boolean managedByStore(String skuNo) {
        return DataScopeContext.executeWithoutScope(() ->
                storeStockMapper.selectCount(Wrappers.<PrdStoreStock>lambdaQuery()
                        .eq(PrdStoreStock::getSkuNo, skuNo))) > 0;
    }

    /**
     * 一批 SKU 在这家店的可售量。
     *
     * @param storeNo 空 = 没有门店上下文，全部按主体总量（单店商家走的就是这一支）
     */
    public Map<String, Integer> available(List<String> skuNos, String storeNo) {
        return read(skuNos, storeNo, true);
    }

    /**
     * `IN (...)` 的分块大小。进销存问答会一次问一个商家的**全部** SKU，
     * 不分块的话 MySQL 的 `max_allowed_packet` 与优化器都会在几千个占位符上翻脸，
     * 而症状是一条看不懂的 SQL 异常。500 是从 `AiGoodsServiceImpl` 合过来的既有取值。
     */
    private static final int CHUNK = 500;

    private Map<String, Integer> read(List<String> skuNos, String storeNo, boolean minusLocked) {
        Map<String, Integer> out = new HashMap<>();
        if (skuNos == null || skuNos.isEmpty()) {
            return out;
        }
        List<PrdSku> skus = new java.util.ArrayList<>();
        for (List<String> chunk : chunks(skuNos)) {
            skus.addAll(DataScopeContext.executeWithoutScope(() ->
                    skuMapper.selectList(Wrappers.<PrdSku>lambdaQuery().in(PrdSku::getSkuNo, chunk))));
        }
        for (PrdSku s : skus) {
            out.put(s.getSkuNo(), Math.max(nz(s.getStock()) - (minusLocked ? nz(s.getLockedStock()) : 0), 0));
        }
        if (storeNo == null || storeNo.isBlank()) {
            return out;
        }
        /*
         * 一次取回这批 SKU 的**全部**店级行（不只是本店的）—— 因为判据是
         * 「这个 SKU 有没有被按店管理」，只查本店的话分不出
         * 「没按店管理」与「按店管理但本店没设」，而这两者的答案相反（主体总量 vs 0）。
         */
        List<PrdStoreStock> rows = new java.util.ArrayList<>();
        for (List<String> chunk : chunks(skuNos)) {
            rows.addAll(DataScopeContext.executeWithoutScope(() ->
                    storeStockMapper.selectList(Wrappers.<PrdStoreStock>lambdaQuery()
                            .in(PrdStoreStock::getSkuNo, chunk))));
        }
        Map<String, Integer> here = new HashMap<>();
        java.util.Set<String> managed = new java.util.HashSet<>();
        for (PrdStoreStock r : rows) {
            managed.add(r.getSkuNo());
            if (storeNo.equals(r.getStoreNo())) {
                here.put(r.getSkuNo(), Math.max(nz(r.getStock()) - (minusLocked ? nz(r.getLockedStock()) : 0), 0));
            }
        }
        for (String skuNo : managed) {
            out.put(skuNo, here.getOrDefault(skuNo, 0));
        }
        return out;
    }

    /**
     * 一批 SKU 在这家店的**总库存**（不减已锁定），语义同 {@link #available(List, String)}。
     *
     * <p><b>落店用它，不用可售量。</b> 一次下单里落店解析会跑好几趟
     * （算价、锁库存、写子单三处都要用它，方法注释里写着），
     * 而可售量在中间那一趟之后已经被**本单自己**锁走了 ——
     * 拿它当判据的话，第一趟选中的店在第二趟变成「发不出」，
     * 于是一个库存充足的单被自己的闸拒掉（实测：3 件的货买 2 件，第二趟看到 1）。
     *
     * <p>{@code stock} 要到支付确认才减，所以在一次请求内它是稳定的。
     * 真实可用量的把关留在 {@code StockPort.lock} —— 它本来就只执行一次，
     * 而且并发下靠条件更新防超卖，比在这里先查一遍可靠。
     */
    public Map<String, Integer> total(List<String> skuNos, String storeNo) {
        return read(skuNos, storeNo, false);
    }

    /** 单个 SKU 的可售量，语义同 {@link #available(List, String)}。 */
    public int available(String skuNo, String storeNo) {
        return available(List.of(skuNo), storeNo).getOrDefault(skuNo, 0);
    }

    private static List<List<String>> chunks(List<String> all) {
        List<List<String>> out = new java.util.ArrayList<>();
        for (int i = 0; i < all.size(); i += CHUNK) {
            out.add(all.subList(i, Math.min(all.size(), i + CHUNK)));
        }
        return out;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
