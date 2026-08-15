package ai.neargo.shop.product.mapper;

import ai.neargo.shop.product.entity.PrdCategory;
import ai.neargo.shop.product.entity.PrdCommunityPool;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.entity.PrdSpecTemplate;
import ai.neargo.shop.product.entity.PrdStockLock;
import ai.neargo.shop.product.entity.PrdStoreStock;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** product 域的 Mapper 集合。 */
public final class ProductMappers {

    private ProductMappers() {
    }

    public interface GoodsMapper extends BaseMapper<PrdGoods> {
    }

    public interface SpecTemplateMapper extends BaseMapper<PrdSpecTemplate> {
    }

    public interface SkuMapper extends BaseMapper<PrdSku> {

        /**
         * 原子锁定：**条件写在 WHERE 里**，靠影响行数判断成功与否。
         * 先查后改在并发下必然超卖（两个请求都查到「还有 1 件」），这是唯一正确的写法。
         *
         * @return 1=锁定成功，0=可售不足
         */
        @Update("""
                UPDATE prd_sku SET locked_stock = locked_stock + #{qty}, version = version + 1
                WHERE sku_no = #{skuNo} AND deleted = 0 AND stock - locked_stock >= #{qty}
                """)
        int lockStock(@Param("skuNo") String skuNo, @Param("qty") int qty);

        /** 释放：锁定量减回去，总量不动。{@code >= qty} 防止并发重复释放把 locked 减成负数。 */
        @Update("""
                UPDATE prd_sku SET locked_stock = locked_stock - #{qty}, version = version + 1
                WHERE sku_no = #{skuNo} AND deleted = 0 AND locked_stock >= #{qty}
                """)
        int releaseStock(@Param("skuNo") String skuNo, @Param("qty") int qty);

        /** 确认扣减：总量与锁定量同时减 —— 支付成功后这批货真正卖掉了。 */
        @Update("""
                UPDATE prd_sku SET stock = stock - #{qty}, locked_stock = locked_stock - #{qty},
                                   version = version + 1
                WHERE sku_no = #{skuNo} AND deleted = 0 AND locked_stock >= #{qty} AND stock >= #{qty}
                """)
        int confirmStock(@Param("skuNo") String skuNo, @Param("qty") int qty);

        /**
         * 预售成交（P-3.3.1）：现货不足时的**第二级闸门**，与 {@link #lockStock} 同一套手法 ——
         * 三个条件全写在 WHERE 里，靠影响行数判断，绝不先查后改。
         *
         * <p>三个条件缺一不可，各自防住一件事：
         * <ul>
         *   <li>{@code presale_quota > 0} —— 没开预售的 SKU 缺货就是缺货，行为一个字节不变</li>
         *   <li>{@code cutoff_at IS NULL OR cutoff_at > NOW()} —— 截单后不再收单（P-3.3.2）。
         *       少了它，次日现采的采购单已经下了，还在继续进新订单</li>
         *   <li>{@code sold_count + qty <= presale_quota} —— 额度是硬顶。
         *       少了它，「额度」只是个建议值</li>
         * </ul>
         *
         * @return 1=预售成交，0=没开预售 / 已截单 / 额度用尽
         */
        @Update("""
                UPDATE prd_sku SET sold_count = sold_count + #{qty}, version = version + 1
                WHERE sku_no = #{skuNo} AND deleted = 0 AND presale_quota > 0
                  AND (cutoff_at IS NULL OR cutoff_at > NOW())
                  AND sold_count + #{qty} <= presale_quota
                """)
        int lockPresale(@Param("skuNo") String skuNo, @Param("qty") int qty);

        /**
         * 预售释放：已售减回去。
         *
         * <p><b>刻意不校验截单时间</b> —— 释放是「这一单不算数了」，
         * 截单之后取消的订单同样要把额度还回去，否则额度会随着取消数一路缩水，
         * 而那批货其实还没卖出去。
         */
        @Update("""
                UPDATE prd_sku SET sold_count = sold_count - #{qty}, version = version + 1
                WHERE sku_no = #{skuNo} AND deleted = 0 AND sold_count >= #{qty}
                """)
        int releasePresale(@Param("skuNo") String skuNo, @Param("qty") int qty);
    }

    /**
     * 门店级库存。三条 SQL 与 {@link SkuMapper} 的那三条**逐字同构** ——
     * 同一套「条件写在 WHERE 里、靠影响行数判断」的原子扣减手法，
     * 只是换了张表加了个 store_no。
     *
     * <p>刻意写成两套而不是抽象成一套：库存扣减是这个系统里最不该「聪明」的地方，
     * 一个泛化的 updateStock(table, key...) 读起来永远要先想「这次走的是哪张表」。
     */
    public interface StoreStockMapper extends BaseMapper<PrdStoreStock> {

        @Update("""
                UPDATE prd_store_stock SET locked_stock = locked_stock + #{qty}, version = version + 1
                WHERE store_no = #{storeNo} AND sku_no = #{skuNo} AND deleted = 0
                  AND stock - locked_stock >= #{qty}
                """)
        int lockStock(@Param("storeNo") String storeNo, @Param("skuNo") String skuNo,
                      @Param("qty") int qty);

        @Update("""
                UPDATE prd_store_stock SET locked_stock = locked_stock - #{qty}, version = version + 1
                WHERE store_no = #{storeNo} AND sku_no = #{skuNo} AND deleted = 0
                  AND locked_stock >= #{qty}
                """)
        int releaseStock(@Param("storeNo") String storeNo, @Param("skuNo") String skuNo,
                         @Param("qty") int qty);

        @Update("""
                UPDATE prd_store_stock SET stock = stock - #{qty}, locked_stock = locked_stock - #{qty},
                                           version = version + 1
                WHERE store_no = #{storeNo} AND sku_no = #{skuNo} AND deleted = 0
                  AND locked_stock >= #{qty} AND stock >= #{qty}
                """)
        int confirmStock(@Param("storeNo") String storeNo, @Param("skuNo") String skuNo,
                         @Param("qty") int qty);
    }

    /** 门店级上架关系。只有增删改查，没有原子扣减那套 —— 它不是并发争抢的资源 */
    public interface StoreGoodsMapper extends BaseMapper<ai.neargo.shop.product.entity.PrdStoreGoods> {
    }

    public interface CommunityPoolMapper extends BaseMapper<PrdCommunityPool> {

        /**
         * 复活一条被逻辑删的池行。
         *
         * <p><b>下架是逻辑删，而 {@code uk_community_goods} 不含 deleted 列</b> ——
         * 所以「下架再上架」时直接 insert 必然撞唯一键，表现为上架接口 500。
         * 这个坑在商家社区表上踩过一次，池表这里换了个入口又踩了一次：
         * 差集增删只解决了「不要先全删再全插」，没解决「删过的行还占着键」。
         *
         * @return 影响行数；0 表示压根没有这一对（该走 insert）
         */
        @Update("""
                UPDATE prd_community_pool SET deleted = 0, version = version + 1
                WHERE community_no = #{communityNo} AND goods_no = #{goodsNo} AND deleted = 1
                """)
        int revive(@Param("communityNo") String communityNo, @Param("goodsNo") String goodsNo);
    }

    public interface StockLockMapper extends BaseMapper<PrdStockLock> {
    }

    public interface CategoryMapper extends BaseMapper<PrdCategory> {
    }
}
