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

import java.util.List;

/** product 域的 Mapper 集合。 */
public final class ProductMappers {

    private ProductMappers() {
    }

    public interface GoodsMapper extends BaseMapper<PrdGoods> {
    }

    /** 门店线上可售规则（V346）。稀疏，只改不删 */
    public interface SellRuleMapper extends BaseMapper<ai.neargo.shop.product.entity.PrdSellRule> {
    }

    /** 门店库存同步开关与期初对齐（V346）。每店一行 */
    public interface StoreStockSyncMapper extends BaseMapper<ai.neargo.shop.product.entity.PrdStoreStockSync> {
    }

    /** 写回明细（V346）。唯一键即幂等键 */
    public interface StockSyncLogMapper extends BaseMapper<ai.neargo.shop.product.entity.PrdStockSyncLog> {
    }

    /** 主体按类目的「记不记库存」（V345）。稀疏，只改不删 */
    public interface EntityCategoryInvMapper
            extends BaseMapper<ai.neargo.shop.product.entity.PrdEntityCategoryInv> {
    }

    public interface SpecTemplateMapper extends BaseMapper<PrdSpecTemplate> {
    }

    /** 商品收藏（V343）。取消要真删，理由同 CategorySpecMapper：软删行占着唯一键，再收藏就撞 */
    public interface GoodsFavoriteMapper
            extends BaseMapper<ai.neargo.shop.product.entity.PrdGoodsFavorite> {

        @org.apache.ibatis.annotations.Delete(
                "DELETE FROM prd_goods_favorite WHERE user_no = #{userNo} AND goods_no = #{goodsNo}")
        int purge(@org.apache.ibatis.annotations.Param("userNo") String userNo,
                  @org.apache.ibatis.annotations.Param("goodsNo") String goodsNo);
    }

    // ---------------------------------------------------------------- 规格库（V195）
    //
    // 四层：规格项 / 规格值 / 类目绑定 / 类目取值子集，另加两张商家覆盖表。
    // 商家侧的「套用模板」读的是这几张表组装出来的结果，契约形状不变。

    public interface SpecDimMapper
            extends BaseMapper<ai.neargo.shop.product.entity.PrdSpecDim> {
    }

    public interface SpecValueMapper
            extends BaseMapper<ai.neargo.shop.product.entity.PrdSpecValue> {
    }

    /** 商家对平台规格的覆盖（V213）。整份替换时要真删，理由同 CategorySpecMapper */
    public interface MerchantSpecOverrideMapper
            extends BaseMapper<ai.neargo.shop.product.entity.PrdMerchantSpecOverride> {

        @org.apache.ibatis.annotations.Delete(
                "DELETE FROM prd_merchant_spec_override "
                        + "WHERE merchant_no = #{merchantNo} AND category_no = #{categoryNo}")
        int purge(@org.apache.ibatis.annotations.Param("merchantNo") String merchantNo,
                  @org.apache.ibatis.annotations.Param("categoryNo") String categoryNo);
    }

    public interface CategorySpecMapper
            extends BaseMapper<ai.neargo.shop.product.entity.PrdCategorySpec> {

        /**
         * <b>真删</b>这个类目的全部绑定。
         *
         * <p>不能用 {@code delete(...)}：{@link ai.neargo.shop.common.BaseEntity} 上挂着
         * {@code @TableLogic}，那条路是 {@code UPDATE deleted=1}，而唯一键
         * {@code uk_cat_spec(tenant_no, category_no, dim_no)} <b>不含 deleted</b> ——
         * 于是「整份替换」的第二步 INSERT 撞上第一步留下的软删行，
         * 报 {@code Duplicate entry 'MAIN-CAT110-SD_WEIGHT'}。
         *
         * <p>症状是运营在「类目 × 规格」里改任何一次绑定都 500，而**第一次配置不会**：
         * 种子是迁移直接 INSERT 的，从没走过这条路。
         *
         * <p>绑定是配置、不是凭证：没有「历史要靠它解释」的需求（那是
         * {@code prd_spec_value} 的事 —— SKU 快照记着它的编号）。所以真删是对的语义。
         */
        @org.apache.ibatis.annotations.Delete(
                "DELETE FROM prd_category_spec WHERE category_no = #{categoryNo}")
        int purgeByCategory(@org.apache.ibatis.annotations.Param("categoryNo") String categoryNo);
    }

    public interface CategorySpecValueMapper
            extends BaseMapper<ai.neargo.shop.product.entity.PrdCategorySpecValue> {

        /** 同上：{@code uk_cat_spec_value} 同样不含 deleted，软删会挡住重新插入 */
        @org.apache.ibatis.annotations.Delete(
                "DELETE FROM prd_category_spec_value WHERE category_no = #{categoryNo}")
        int purgeByCategory(@org.apache.ibatis.annotations.Param("categoryNo") String categoryNo);
    }

    public interface MerchantSpecMapper
            extends BaseMapper<ai.neargo.shop.product.entity.PrdMerchantSpec> {
    }

    public interface MerchantSpecValueMapper
            extends BaseMapper<ai.neargo.shop.product.entity.PrdMerchantSpecValue> {
    }

    /** 类目 × 支付方式（四层判定的第 ① 层）。**没有行即放行**。 */
    public interface CategoryPayModeMapper
            extends BaseMapper<ai.neargo.shop.product.entity.PrdCategoryPayMode> {
    }

    /** 类目积分规则。平台统一按类目管理，商家不配。 */
    public interface CategoryPointsMapper
            extends BaseMapper<ai.neargo.shop.product.entity.PrdCategoryPoints> {
    }

    public interface GoodsDraftMapper extends BaseMapper<ai.neargo.shop.product.entity.PrdGoodsDraft> {
        /**
         * 物理删（发布成功 / 内容与线上相同 / 商家丢弃草稿）。
         * 不用逻辑删：uk_goods_draft(goods_no) 不含 deleted，软删会挡住重建 —— V195 的坑。
         */
        @org.apache.ibatis.annotations.Delete("DELETE FROM prd_goods_draft WHERE goods_no = #{goodsNo}")
        int purge(@org.apache.ibatis.annotations.Param("goodsNo") String goodsNo);
    }

    public interface GoodsRevisionMapper
            extends BaseMapper<ai.neargo.shop.product.entity.PrdGoodsRevision> {

        /**
         * 下一个版本号。**用 MAX+1 而不是 count+1**：行不会删，但逻辑删过的行
         * count 不到，而 uk_goods_revision(goods_no, revision_no) 照样挡 ——
         * count 的写法会在第一次逻辑删之后开始撞唯一键。
         */
        @org.apache.ibatis.annotations.Select(
                "SELECT COALESCE(MAX(revision_no), 0) + 1 FROM prd_goods_revision WHERE goods_no = #{goodsNo}")
        int nextRevisionNo(@org.apache.ibatis.annotations.Param("goodsNo") String goodsNo);
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
         * 退货入库：**只加 stock，不碰 locked_stock**。
         *
         * <p>这一笔的锁早在支付时就转成实扣了（confirm 把两个数一起减掉），
         * 现在货回来只是实存变多 —— 去动 locked_stock 会让它变成负数，
         * 而那个数一旦为负，之后每一次下单的「够不够」都算错。
         */
        @Update("""
                UPDATE prd_sku SET stock = stock + #{qty}, version = version + 1
                 WHERE sku_no = #{skuNo} AND deleted = 0
                """)
        int restoreStock(@Param("skuNo") String skuNo, @Param("qty") int qty);

        /**
         * 手改库存：**设成这个数**。
         *
         * <p>`locked_stock` 不动 —— 已经被下单占住的量不属于「我数出来有多少」，
         * 把它一起清掉的话，那些单在支付时会扣到负数。
         */
        @Update("""
                UPDATE prd_sku SET stock = #{stock}, version = version + 1
                WHERE sku_no = #{skuNo} AND deleted = 0
                """)
        int setStock(@Param("skuNo") String skuNo, @Param("stock") int stock);

        /** 写回，主体级库存那一档（没有按店库存行、主体只有一家店时）。口径同 StoreStockMapper#syncSellable */
        @Update("""
                UPDATE prd_sku SET stock = #{target} + locked_stock, version = version + 1
                WHERE sku_no = #{skuNo} AND deleted = 0
                """)
        int syncSellable(@Param("skuNo") String skuNo, @Param("target") int target);

        /** 手动规则的写回，主体级那一档：只降不升 */
        @Update("""
                UPDATE prd_sku SET stock = #{cap} + locked_stock, version = version + 1
                WHERE sku_no = #{skuNo} AND deleted = 0 AND stock - locked_stock > #{cap}
                """)
        int capSellable(@Param("skuNo") String skuNo, @Param("cap") int cap);

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

        /** 店级退货入库。理由与 {@code SkuMapper.restoreStock} 相同：只加 stock。 */
        @Update("""
                UPDATE prd_store_stock SET stock = stock + #{qty}, version = version + 1
                 WHERE store_no = #{storeNo} AND sku_no = #{skuNo} AND deleted = 0
                """)
        int restoreStock(@Param("storeNo") String storeNo, @Param("skuNo") String skuNo,
                         @Param("qty") int qty);

        /** 手改门店库存：设成这个数，`locked_stock` 不动（同主体级那条） */
        @Update("""
                UPDATE prd_store_stock SET stock = #{stock}
                WHERE store_no = #{storeNo} AND sku_no = #{skuNo} AND deleted = 0
                """)
        int setStock(@Param("storeNo") String storeNo, @Param("skuNo") String skuNo,
                     @Param("stock") int stock);

        /**
         * 写回（TDD §18.1）：让线上可卖 = {@code target}。**一条 SQL 里用当下的 locked_stock 算**，
         * 不先读后写 —— 读与写之间有订单锁定的话，先读后写会把那笔锁定算丢。
         * 不走 {@link #setStock}：那条路被 {@code StockPort.setOnHand} 用，会发镜像回进销存。
         */
        @Update("""
                UPDATE prd_store_stock SET stock = #{target} + locked_stock, version = version + 1
                WHERE store_no = #{storeNo} AND sku_no = #{skuNo} AND deleted = 0
                """)
        int syncSellable(@Param("storeNo") String storeNo, @Param("skuNo") String skuNo,
                         @Param("target") int target);

        /** 手动规则的写回：线上可卖超过 {@code cap} 才压到 cap，否则不动（只降不升） */
        @Update("""
                UPDATE prd_store_stock SET stock = #{cap} + locked_stock, version = version + 1
                WHERE store_no = #{storeNo} AND sku_no = #{skuNo} AND deleted = 0
                  AND stock - locked_stock > #{cap}
                """)
        int capSellable(@Param("storeNo") String storeNo, @Param("skuNo") String skuNo,
                        @Param("cap") int cap);
    }

    /**
     * 门店级售价。**没有原子扣减那套** —— 价格不是被并发争抢的资源，
     * 它只被商家改，读的人只读。
     */
    public interface StorePriceMapper extends BaseMapper<ai.neargo.shop.product.entity.PrdStorePrice> {
    }

    public interface TopicMapper extends BaseMapper<ai.neargo.shop.product.entity.PrdTopic> {
    }

    public interface TopicGoodsMapper extends BaseMapper<ai.neargo.shop.product.entity.PrdTopicGoods> {
    }

    /** 门店级上架关系。只有增删改查，没有原子扣减那套 —— 它不是并发争抢的资源 */
    public interface StoreGoodsMapper extends BaseMapper<ai.neargo.shop.product.entity.PrdStoreGoods> {
    }

    public interface CommunityPoolMapper extends BaseMapper<PrdCommunityPool> {

        /**
         * 整批复活被逻辑删的池行：一家门店的一串社区，一条语句。
         *
         * <p><b>为什么非复活不可</b>：下架是逻辑删，而 {@code uk_community_goods_store}
         * 不含 deleted 列 —— 「下架再上架」时直接 insert 必然撞唯一键，表现为上架接口 500，
         * 商家看到的是「系统开小差」。这个坑在商家社区表上踩过一次，池表这里换了个入口
         * 又踩了一次：差集增删只解决了「不要先全删再全插」，没解决「删过的行还占着键」。
         *
         * <p><b>为什么是整批</b>：上架原先走的是「逐行先试复活，复活不到再 insert」。
         * 线上一个社区两万三，于是「下架再上架」= 23656 次往返。2026-10-07 实测 <b>29.1 秒</b>
         * （那一次全是复活、零新建，所以这 29 秒基本都在这儿）。
         *
         * <p>只用单列 {@code IN}，不用 {@code (a,b) IN ((..),(..))} 行构造器 ——
         * 后者在 H2 与 MariaDB/MySQL 之间的支持不一致，而 SQL 方言闸门盯着这类写法。
         * 按门店分组之后每组只剩社区一列，普通 {@code IN} 就够。
         *
         * @return 影响行数。<b>调用方不能拿它当「哪几条复活了」</b> —— 它只是个总数，
         *         要知道哪几条，用 {@link #deletedPairs} 先把被删的读出来。
         */
        @Update("""
                <script>
                UPDATE prd_community_pool SET deleted = 0, version = version + 1
                WHERE goods_no = #{goodsNo} AND store_no = #{storeNo} AND deleted = 1
                  AND community_no IN
                  <foreach item="c" collection="communityNos" open="(" separator="," close=")">#{c}</foreach>
                </script>
                """)
        int reviveMany(@Param("goodsNo") String goodsNo, @Param("storeNo") String storeNo,
                       @Param("communityNos") java.util.Collection<String> communityNos);

        /**
         * 这件货<b>被逻辑删掉</b>的池行是哪些 (社区, 门店)。
         *
         * <p>手写 SQL 是必须的：{@code @TableLogic} 会给 BaseMapper 的查询自动加上
         * {@code deleted = 0}，而这里要的恰恰是被删的那一批。
         *
         * <p>读它是为了把「先试复活一下」这个逐行动作换成一次查询 + 分组批量更新：
         * 知道哪些是删过的，就知道哪些该复活、哪些该新建，不必逐行去试。
         */
        @org.apache.ibatis.annotations.Select("""
                SELECT community_no AS communityNo, store_no AS storeNo
                FROM prd_community_pool
                WHERE goods_no = #{goodsNo} AND deleted = 1
                """)
        List<CommunityStore> deletedPairs(@Param("goodsNo") String goodsNo);

        /** {@link #deletedPairs} 的行。{@code storeNo} 可空 —— 存量行的门店没回填上 */
        record CommunityStore(String communityNo, String storeNo) {
        }
    }

    public interface StockLockMapper extends BaseMapper<PrdStockLock> {
    }

    public interface CategoryMapper extends BaseMapper<PrdCategory> {
    }

    /**
     * 平台标准品。<b>跨商家共享的主数据，不注册数据域</b> —— 与 prd_category 同类：
     * 它不属于任何一家商家，按商家维度过滤它只会让所有人都搜不到。
     */
    public interface SpuStdMapper extends BaseMapper<ai.neargo.shop.product.entity.PrdSpuStd> {
    }
}
