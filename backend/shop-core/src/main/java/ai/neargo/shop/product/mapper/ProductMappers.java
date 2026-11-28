package ai.neargo.shop.product.mapper;

import ai.neargo.shop.common.config.ProductTableMapper;
import ai.neargo.shop.product.entity.PrdCategory;
import ai.neargo.shop.product.entity.PrdExecutionRetention;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.entity.PrdStockLock;
import ai.neargo.shop.product.entity.PrdStoreCatalog;
import ai.neargo.shop.product.entity.PrdStoreCatalogVersion;
import ai.neargo.shop.product.entity.PrdStoreSku;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** product 域的 Mapper 集合。 */
public final class ProductMappers {

    private ProductMappers() {
    }

    public interface StoreCatalogMapper extends ProductTableMapper<PrdStoreCatalog> {
        /** Serialize checkout with publication on the same platform catalog head. */
        @Select("SELECT * FROM prd_store_catalog WHERE tenant_no='MAIN' "
                + "AND entity_no=#{entity} AND store_no=#{store} AND deleted=0 FOR UPDATE")
        PrdStoreCatalog lockHead(@Param("entity") String entity, @Param("store") String store);

        @Update("UPDATE prd_store_catalog SET current_version_no=#{next}, version=version+1, "
                + "updated_at=#{at}, updated_by=#{actor} WHERE tenant_no='MAIN' AND entity_no=#{entity} "
                + "AND store_no=#{store} AND catalog_no=#{catalog} AND version=#{expected} AND deleted=0")
        int advance(@Param("entity") String entity, @Param("store") String store,
                @Param("catalog") String catalog, @Param("expected") long expected,
                @Param("next") String next, @Param("actor") String actor,
                @Param("at") LocalDateTime at);
    }

    public interface StoreCatalogVersionMapper extends ProductTableMapper<PrdStoreCatalogVersion> { }

    public interface GoodsMapper extends ProductTableMapper<PrdGoods> {
    }

    /** 门店线上可售规则（V346）。稀疏，只改不删 */
    public interface SellRuleMapper extends ProductTableMapper<ai.neargo.shop.product.entity.PrdSellRule> {
    }

    /** 门店库存同步开关与期初对齐（V346）。每店一行 */
    public interface StoreStockSyncMapper extends BaseMapper<ai.neargo.shop.product.entity.PrdStoreStockSync> {
    }

    /** 写回明细（V346）。唯一键即幂等键 */
    public interface StockSyncLogMapper extends BaseMapper<ai.neargo.shop.product.entity.PrdStockSyncLog> {
    }

    /** 主体按类目的「记不记库存」（V345）。稀疏，只改不删 */
    public interface EntityCategoryInvMapper
            extends ProductTableMapper<ai.neargo.shop.product.entity.PrdEntityCategoryInv> {
    }

    /** 商品收藏（V343）。取消要真删，理由同 SpecUsageMapper#purgeScope：软删行占着唯一键，再收藏就撞 */
    public interface GoodsFavoriteMapper
            extends BaseMapper<ai.neargo.shop.product.entity.PrdGoodsFavorite> {

        @org.apache.ibatis.annotations.Delete(
                "DELETE FROM prd_goods_favorite WHERE user_no = #{userNo} AND goods_no = #{goodsNo}")
        int purge(@org.apache.ibatis.annotations.Param("userNo") String userNo,
                  @org.apache.ibatis.annotations.Param("goodsNo") String goodsNo);
    }

    // ---------------------------------------------------------------- 规格库（V195）
    //
    // 规格项 / 规格值 / 使用范围（类目绑定、类目取值子集、商家覆盖并在 prd_spec_usage）。
    // 商家侧的「套用模板」读的是这几张表组装出来的结果，契约形状不变。

    public interface SpecDimMapper
            extends ProductTableMapper<ai.neargo.shop.product.entity.PrdSpecDim> {
    }

    public interface SpecValueMapper
            extends ProductTableMapper<ai.neargo.shop.product.entity.PrdSpecValue> {
    }

    /**
     * 规格的使用范围（{@code prd_spec_usage}）：类目绑定、类目取值子集、商家覆盖并在一张表，
     * 按 {@code scope_type} 区分（见 {@link ai.neargo.shop.product.entity.PrdSpecUsage}）。
     *
     * <p>读写一律从下面几个工厂方法起手：漏了 {@code scope_type} 或维度级/取值级的条件，
     * 读到的就是别的范围的行 —— 而那不报错。
     */
    public interface SpecUsageMapper
            extends ProductTableMapper<ai.neargo.shop.product.entity.PrdSpecUsage> {

        /** 类目绑定的维度（原 prd_category_spec） */
        static com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
                ai.neargo.shop.product.entity.PrdSpecUsage> categoryDims() {
            return com.baomidou.mybatisplus.core.toolkit.Wrappers
                    .<ai.neargo.shop.product.entity.PrdSpecUsage>lambdaQuery()
                    .eq(ai.neargo.shop.product.entity.PrdSpecUsage::getScopeType,
                            ai.neargo.shop.product.entity.PrdSpecUsage.CATEGORY)
                    .eq(ai.neargo.shop.product.entity.PrdSpecUsage::getValueNo,
                            ai.neargo.shop.product.entity.PrdSpecUsage.DIM_LEVEL);
        }

        /** 类目裁出的取值子集（原 prd_category_spec_value） */
        static com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
                ai.neargo.shop.product.entity.PrdSpecUsage> categoryValues() {
            return com.baomidou.mybatisplus.core.toolkit.Wrappers
                    .<ai.neargo.shop.product.entity.PrdSpecUsage>lambdaQuery()
                    .eq(ai.neargo.shop.product.entity.PrdSpecUsage::getScopeType,
                            ai.neargo.shop.product.entity.PrdSpecUsage.CATEGORY)
                    .ne(ai.neargo.shop.product.entity.PrdSpecUsage::getValueNo,
                            ai.neargo.shop.product.entity.PrdSpecUsage.DIM_LEVEL);
        }

        /** 一个商家在一个类目下的全部覆盖（原 prd_merchant_spec_override），维度级与取值级都在 */
        static com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
                ai.neargo.shop.product.entity.PrdSpecUsage> overrides(String merchantNo, String categoryNo) {
            return com.baomidou.mybatisplus.core.toolkit.Wrappers
                    .<ai.neargo.shop.product.entity.PrdSpecUsage>lambdaQuery()
                    .eq(ai.neargo.shop.product.entity.PrdSpecUsage::getScopeType,
                            ai.neargo.shop.product.entity.PrdSpecUsage.MERCHANT_CATEGORY)
                    .eq(ai.neargo.shop.product.entity.PrdSpecUsage::getScopeRef,
                            ai.neargo.shop.product.entity.PrdSpecUsage.merchantCategoryRef(merchantNo, categoryNo));
        }

        /**
         * <b>真删</b>一个范围的全部行（整份替换的第一步）。
         *
         * <p>不能用 {@code delete(...)}：{@link ai.neargo.shop.common.BaseEntity} 上挂着
         * {@code @TableLogic}，那条路是 {@code UPDATE deleted=1}，而唯一键
         * {@code (scope_type, scope_ref, dim_no, value_no)} <b>不含 deleted</b> ——
         * 于是第二步 INSERT 撞上第一步留下的软删行，报 Duplicate entry。
         * 症状是运营改任何一次类目绑定都 500，而第一次配置不会（种子从没走过这条路）。
         *
         * <p>绑定与覆盖是配置、不是凭证：没有「历史要靠它解释」的需求（那是
         * {@code prd_spec_value} 的事 —— SKU 快照记着它的编号）。所以真删是对的语义。
         */
        @org.apache.ibatis.annotations.Delete(
                "DELETE FROM prd_spec_usage WHERE scope_type = #{scopeType} AND scope_ref = #{scopeRef}")
        int purgeScope(@org.apache.ibatis.annotations.Param("scopeType") String scopeType,
                       @org.apache.ibatis.annotations.Param("scopeRef") String scopeRef);

        /** 真删一个范围里某个维度的全部行（维度级与取值级），理由同 {@link #purgeScope} */
        @org.apache.ibatis.annotations.Delete(
                "DELETE FROM prd_spec_usage WHERE scope_type = #{scopeType} AND scope_ref = #{scopeRef}"
                        + " AND dim_no = #{dimNo}")
        int purgeDim(@org.apache.ibatis.annotations.Param("scopeType") String scopeType,
                     @org.apache.ibatis.annotations.Param("scopeRef") String scopeRef,
                     @org.apache.ibatis.annotations.Param("dimNo") String dimNo);

        /** 真删一个范围里某个维度的取值级行，维度级行留着（保住它的创建时间与状态） */
        @org.apache.ibatis.annotations.Delete(
                "DELETE FROM prd_spec_usage WHERE scope_type = #{scopeType} AND scope_ref = #{scopeRef}"
                        + " AND dim_no = #{dimNo} AND value_no <> ''")
        int purgeDimValues(@org.apache.ibatis.annotations.Param("scopeType") String scopeType,
                           @org.apache.ibatis.annotations.Param("scopeRef") String scopeRef,
                           @org.apache.ibatis.annotations.Param("dimNo") String dimNo);
    }

    public interface GoodsDraftMapper extends ProductTableMapper<ai.neargo.shop.product.entity.PrdGoodsDraft> {
        /**
         * 物理删（发布成功 / 内容与线上相同 / 商家丢弃草稿）。
         * 不用逻辑删：uk_goods_draft(goods_no) 不含 deleted，软删会挡住重建 —— V195 的坑。
         */
        @org.apache.ibatis.annotations.Delete("DELETE FROM prd_goods_draft WHERE goods_no = #{goodsNo}")
        int purge(@org.apache.ibatis.annotations.Param("goodsNo") String goodsNo);
    }

    public interface GoodsRevisionMapper
            extends ProductTableMapper<ai.neargo.shop.product.entity.PrdGoodsRevision> {

        /**
         * 下一个版本号。**用 MAX+1 而不是 count+1**：行不会删，但逻辑删过的行
         * count 不到，而 uk_goods_revision(goods_no, revision_no) 照样挡 ——
         * count 的写法会在第一次逻辑删之后开始撞唯一键。
         */
        @org.apache.ibatis.annotations.Select(
                "SELECT COALESCE(MAX(revision_no), 0) + 1 FROM prd_goods_revision WHERE goods_no = #{goodsNo}")
        int nextRevisionNo(@org.apache.ibatis.annotations.Param("goodsNo") String goodsNo);
    }

    public interface SkuMapper extends ProductTableMapper<PrdSku> {

        /** Current complete accounting identities, including every market and retired identity. */
        @Select("SELECT * FROM prd_sku WHERE tenant_no='MAIN' AND sku_no=#{skuNo} ORDER BY id FOR UPDATE")
        List<PrdSku> lockSkuCurrent(@Param("skuNo") String skuNo);

        @Select("SELECT * FROM prd_sku WHERE tenant_no='MAIN' AND goods_no=#{goodsNo} ORDER BY id FOR UPDATE")
        List<PrdSku> lockGoodsSkusCurrent(@Param("goodsNo") String goodsNo);

        /** Explicit metadata whitelist preserves live counters and intentional nullable clearing. */
        @Update("""
                UPDATE prd_sku SET option_values=#{row.optionValues}, option_value_nos=#{row.optionValueNos},
                    spec=#{row.spec}, price=#{row.price}, origin_price=#{row.originPrice}, cost_price=#{row.costPrice},
                    nominal_gram=#{row.nominalGram}, barcode=#{row.barcode}, merchant_sku_code=#{row.merchantSkuCode},
                    sale_unit=#{row.saleUnit}, version=version+1
                WHERE tenant_no='MAIN' AND id=#{row.id} AND entity_no=#{row.entityNo} AND sku_no=#{row.skuNo}
                    AND goods_no=#{row.goodsNo} AND market=#{row.market} AND deleted=0 AND version=#{row.version}
                    AND version < 9223372036854775807
                """)
        int updateCatalogMetadata(@Param("row") PrdSku row);

        /** Accounting retirement cannot race a reserve into deleting its release/restore pool. */
        @Update("""
                UPDATE prd_sku SET deleted=1, version=version+1 WHERE tenant_no='MAIN' AND id=#{id}
                    AND entity_no=#{entity} AND goods_no=#{goods} AND deleted=0 AND version=#{version}
                    AND COALESCE(locked_stock,0)=0 AND COALESCE(sold_count,0)=0
                    AND version < 9223372036854775807
                """)
        int retireUnencumbered(@Param("id") long id, @Param("entity") String entity,
                @Param("goods") String goods, @Param("version") long version);

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
        /*
         * {@code locked_stock <= #{stock}}：改到锁定量以下 = 已经锁给买家的货在账上没了，
         * 那几单付款确认时 confirmStock 扣不动。0 行由调用方区分「不存在」与「低于锁定量」。
         */
        @Update("""
                UPDATE prd_sku SET stock = #{stock}, version = version + 1
                WHERE sku_no = #{skuNo} AND deleted = 0 AND #{stock} >= 0
                  AND #{stock} >= COALESCE(locked_stock, 0)
                """)
        int setStock(@Param("skuNo") String skuNo, @Param("stock") int stock);

        /** 写回，主体级库存那一档（没有按店库存行、主体只有一家店时）。口径同 StoreSkuMapper#syncSellable */
        @Update("""
                UPDATE prd_sku SET stock = #{target} + locked_stock, version = version + 1
                WHERE sku_no = #{skuNo} AND deleted = 0 AND #{target} >= 0
                """)
        int syncSellable(@Param("skuNo") String skuNo, @Param("target") int target);

        /** 手动规则的写回，主体级那一档：只降不升 */
        @Update("""
                UPDATE prd_sku SET stock = #{cap} + locked_stock, version = version + 1
                WHERE sku_no = #{skuNo} AND deleted = 0 AND #{cap} >= 0 AND stock - locked_stock > #{cap}
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
     * 门店 SKU（{@code prd_store_sku}，门店价与店级库存并成的一张表）。
     *
     * <p>库存那几条 SQL 与 {@link SkuMapper} 的<b>逐字同构</b> —— 同一套「条件写在 WHERE 里、靠影响行数判断」的
     * 原子扣减手法，只是换了张表加了个 store_no。每条都带 {@code sellable_qty IS NOT NULL}：
     * 只设过门店价的店也有一行，但它没有店级库存，不能让它「影响 1 行」冒充扣减成功。
     *
     * <p>刻意写成两套而不是抽象成一套：库存扣减是这个系统里最不该「聪明」的地方，
     * 一个泛化的 updateStock(table, key...) 读起来永远要先想「这次走的是哪张表」。
     * 门店价没有原子扣减那套 —— 价格不是被并发争抢的资源，它只被商家改，读的人只读。
     */
    public interface StoreSkuMapper extends ProductTableMapper<PrdStoreSku> {
        @Select("SELECT * FROM prd_store_sku WHERE tenant_no='MAIN' AND sku_no=#{skuNo} AND sellable_qty IS NOT NULL"
                + " AND deleted = 0 ORDER BY id FOR UPDATE")
        List<PrdStoreSku> lockSkuStoresCurrent(@Param("skuNo") String skuNo);

        @Update("""
                UPDATE prd_store_sku SET locked_qty = locked_qty + #{qty}, version = version + 1
                WHERE store_no = #{storeNo} AND sku_no = #{skuNo} AND deleted = 0 AND sellable_qty IS NOT NULL
                  AND sellable_qty - locked_qty >= #{qty}
                """)
        int lockStock(@Param("storeNo") String storeNo, @Param("skuNo") String skuNo,
                      @Param("qty") int qty);

        @Update("""
                UPDATE prd_store_sku SET locked_qty = locked_qty - #{qty}, version = version + 1
                WHERE store_no = #{storeNo} AND sku_no = #{skuNo} AND deleted = 0 AND sellable_qty IS NOT NULL
                  AND locked_qty >= #{qty}
                """)
        int releaseStock(@Param("storeNo") String storeNo, @Param("skuNo") String skuNo,
                         @Param("qty") int qty);

        @Update("""
                UPDATE prd_store_sku SET sellable_qty = sellable_qty - #{qty}, locked_qty = locked_qty - #{qty},
                                         version = version + 1
                WHERE store_no = #{storeNo} AND sku_no = #{skuNo} AND deleted = 0 AND sellable_qty IS NOT NULL
                  AND locked_qty >= #{qty} AND sellable_qty >= #{qty}
                """)
        int confirmStock(@Param("storeNo") String storeNo, @Param("skuNo") String skuNo,
                         @Param("qty") int qty);

        /** 店级退货入库。理由与 {@code SkuMapper.restoreStock} 相同：只加总量。 */
        @Update("""
                UPDATE prd_store_sku SET sellable_qty = sellable_qty + #{qty}, version = version + 1
                 WHERE store_no = #{storeNo} AND sku_no = #{skuNo} AND deleted = 0 AND sellable_qty IS NOT NULL
                """)
        int restoreStock(@Param("storeNo") String storeNo, @Param("skuNo") String skuNo,
                         @Param("qty") int qty);

        /** 手改门店库存：设成这个数，{@code locked_qty} 不动；与主体级 {@link SkuMapper#setStock} 同一条：不许改到锁定量以下。 */
        @Update("""
                UPDATE prd_store_sku SET sellable_qty = #{stock}, version = version + 1
                WHERE store_no = #{storeNo} AND sku_no = #{skuNo} AND deleted = 0 AND sellable_qty IS NOT NULL
                  AND #{stock} >= 0 AND #{stock} >= COALESCE(locked_qty, 0)
                """)
        int setStock(@Param("storeNo") String storeNo, @Param("skuNo") String skuNo,
                     @Param("stock") int stock);

        /**
         * 写回（TDD §18.1）：让线上可卖 = {@code target}。<b>一条 SQL 里用当下的 locked_qty 算</b>，
         * 不先读后写 —— 读与写之间有订单锁定的话，先读后写会把那笔锁定算丢。
         * 不走 {@link #setStock}：那条路被 {@code StockPort.setOnHand} 用，会发镜像回进销存。
         */
        @Update("""
                UPDATE prd_store_sku SET sellable_qty = #{target} + locked_qty, version = version + 1
                WHERE store_no = #{storeNo} AND sku_no = #{skuNo} AND deleted = 0 AND sellable_qty IS NOT NULL
                  AND #{target} >= 0
                """)
        int syncSellable(@Param("storeNo") String storeNo, @Param("skuNo") String skuNo,
                         @Param("target") int target);

        /** 手动规则的写回：线上可卖超过 {@code cap} 才压到 cap，否则不动（只降不升） */
        @Update("""
                UPDATE prd_store_sku SET sellable_qty = #{cap} + locked_qty, version = version + 1
                WHERE store_no = #{storeNo} AND sku_no = #{skuNo} AND deleted = 0 AND sellable_qty IS NOT NULL
                  AND #{cap} >= 0 AND sellable_qty - locked_qty > #{cap}
                """)
        int capSellable(@Param("storeNo") String storeNo, @Param("skuNo") String skuNo,
                        @Param("cap") int cap);

        @Update("""
                UPDATE prd_store_sku SET sellable_qty = 0, version = version + 1
                WHERE store_no = #{storeNo} AND sku_no = #{skuNo} AND market = #{market} AND deleted = 0
                  AND sellable_qty IS NULL
                """)
        int fillStoreStock(@Param("storeNo") String storeNo, @Param("skuNo") String skuNo,
                           @Param("market") String market);

        /**
         * 让这家店的这个 SKU 进入店级库存（库存 0、锁量 0）。<b>一店一 SKU 一行</b>：
         * 已有行（只设过门店价）就补上库存列，没有行才插；已经是店级库存的什么都不动。
         * 调用方自己负责数据域（多数在 {@code executeWithoutScope} 里）。
         */
        default void startStoreStock(String entityNo, String storeNo, String skuNo, String market) {
            if (fillStoreStock(storeNo, skuNo, market) > 0) {
                return;
            }
            boolean exists = selectCount(com.baomidou.mybatisplus.core.toolkit.Wrappers.<PrdStoreSku>lambdaQuery()
                    .eq(PrdStoreSku::getStoreNo, storeNo).eq(PrdStoreSku::getSkuNo, skuNo)
                    .eq(PrdStoreSku::getMarket, market)) > 0;
            if (!exists) {
                PrdStoreSku row = new PrdStoreSku();
                row.setStoreNo(storeNo);
                row.setSkuNo(skuNo);
                row.setEntityNo(entityNo);
                row.setMarket(market);
                row.setSellableQty(0);
                row.setLockedQty(0);
                insert(row);
            }
        }
    }

    public interface TopicMapper extends BaseMapper<ai.neargo.shop.product.entity.PrdTopic> {
    }

    public interface TopicGoodsMapper extends BaseMapper<ai.neargo.shop.product.entity.PrdTopicGoods> {
    }

    /** 门店级上架关系。只有增删改查，没有原子扣减那套 —— 它不是并发争抢的资源 */
    public interface StoreGoodsMapper extends ProductTableMapper<ai.neargo.shop.product.entity.PrdStoreGoods> {
    }

    public interface StockLockMapper extends ProductTableMapper<PrdStockLock> {
        /**
         * Complete original rows, including settled rows, share one deterministic owner lock order.
         */
        @Select(
                "SELECT * FROM prd_stock_lock WHERE tenant_no='MAIN' AND lock_no=#{order} "
                        + "ORDER BY id FOR UPDATE")
        List<PrdStockLock> lockOriginal(@Param("order") String order);

        /** Native current matching set preserves SQL collation and inherited RR semantics without discovery gaps. */
        @Select("SELECT * FROM prd_stock_lock WHERE tenant_no='MAIN' AND fulfillment_ref=#{ref} "
                + "ORDER BY id FOR UPDATE")
        List<PrdStockLock> lockFulfillmentCurrent(@Param("ref") String ref);

        /** Primary physical scan establishes one current complete removal-root set in original-row-id order. */
        @Select("""
                <script>SELECT * FROM prd_stock_lock FORCE INDEX (PRIMARY) WHERE tenant_no='MAIN' AND sku_no IN
                <foreach collection="skus" item="sku" open="(" separator="," close=")">#{sku}</foreach>
                ORDER BY id FOR UPDATE</script>
                """)
        @Select(value = """
                <script>SELECT * FROM prd_stock_lock WHERE tenant_no='MAIN' AND sku_no IN
                <foreach collection="skus" item="sku" open="(" separator="," close=")">#{sku}</foreach>
                ORDER BY id FOR UPDATE</script>
                """, databaseId = "H2")
        List<PrdStockLock> lockSkuOriginalsCurrent(@Param("skus") List<String> skus);

        /** Frozen tracked identity permits source-aware legacy-return routing without balance locks. */
        @Select("SELECT COUNT(*) FROM prd_stock_lock WHERE tenant_no='MAIN' AND lock_no=#{order} "
                + "AND source_manifest_hash IS NOT NULL")
        int trackedOriginal(@Param("order") String order);

        /** Plain read for mirror evidence: every original row of one fulfillment, settled and deleted included. */
        @Select("SELECT * FROM prd_stock_lock WHERE fulfillment_ref=#{ref} ORDER BY id")
        List<PrdStockLock> fulfillmentOriginals(@Param("ref") String ref);

        /** Which of the given lock numbers hold this SKU; the delivery guard asks without joining outbox. */
        @Select("""
                <script>SELECT DISTINCT lock_no FROM prd_stock_lock WHERE sku_no=#{sku} AND lock_no IN
                <foreach collection="lockNos" item="no" open="(" separator="," close=")">#{no}</foreach></script>
                """)
        List<String> lockNosHoldingSku(@Param("sku") String sku, @Param("lockNos") java.util.Collection<String> lockNos);

    }

    /** Links are queried under existing stock roots; deleted identity conflicts are never hidden. */
    public interface ExecutionRetentionMapper extends BaseMapper<PrdExecutionRetention> {
        @Select("""
                SELECT * FROM prd_execution_retention WHERE tenant_no='MAIN' AND retention_no=#{retentionNo}
                ORDER BY original_row_id FOR UPDATE
                """)
        List<PrdExecutionRetention> findExactOriginalSet(@Param("retentionNo") String retentionNo);

        @Select("""
                <script>SELECT * FROM prd_execution_retention WHERE tenant_no='MAIN' AND original_row_id IN
                <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
                ORDER BY original_row_id,retention_no FOR UPDATE</script>
                """)
        List<PrdExecutionRetention> lockOriginalLinks(@Param("ids") List<Long> ids);

        @Insert("""
                INSERT INTO prd_execution_retention (
                    tenant_no, created_at, created_by, updated_at,
                    updated_by, version, deleted, entity_no,
                    store_no, retention_no, execution_no, unit_no,
                    grant_no, attempt_no, reference_schema, reference_json,
                    reference_hash, original_binding_bytes_hash, prepared_grant_revision, original_row_id,
                    original_lock_no, stock_original_json, original_hash, original_manifest_hash,
                    confirmation_operation_no, source_confirmation_no, original_row_version, original_disposition,
                    original_row_json, owner_set_hash, owner_set_count, request_hash,
                    resource_command_no, resource_command_hash, plan_hash, creator_namespace_hash,
                    creator_personnel_hash, accepted_authority_json, accepted_authority_hash, state,
                    issue_observation_json, issue_observation_hash, no_effect_seal_json, no_effect_seal_no,
                    no_effect_seal_revision, no_effect_seal_hash
                ) VALUES (
                    #{row.tenantNo}, #{row.createdAt}, #{row.createdBy}, #{row.updatedAt},
                    #{row.updatedBy}, #{row.version}, #{row.deleted}, #{row.entityNo},
                    #{row.storeNo}, #{row.retentionNo}, #{row.executionNo}, #{row.unitNo},
                    #{row.grantNo}, #{row.attemptNo}, #{row.referenceSchema}, #{row.referenceJson},
                    #{row.referenceHash}, #{row.originalBindingBytesHash}, #{row.preparedGrantRevision}, \
                #{row.originalRowId},
                    #{row.originalLockNo}, #{row.stockOriginalJson}, #{row.originalHash}, #{row.originalManifestHash},
                    #{row.confirmationOperationNo}, #{row.sourceConfirmationNo}, #{row.originalRowVersion}, \
                #{row.originalDisposition},
                    #{row.originalRowJson}, #{row.ownerSetHash}, #{row.ownerSetCount}, #{row.requestHash},
                    #{row.resourceCommandNo}, #{row.resourceCommandHash}, #{row.planHash}, #{row.creatorNamespaceHash},
                    #{row.creatorPersonnelHash}, #{row.acceptedAuthorityJson}, #{row.acceptedAuthorityHash}, \
                #{row.state},
                    #{row.issueObservationJson}, #{row.issueObservationHash}, #{row.noEffectSealJson}, \
                #{row.noEffectSealNo},
                    #{row.noEffectSealRevision}, #{row.noEffectSealHash}
                )
                """)
        int insertRetained(@Param("row") PrdExecutionRetention row);

        /** Immutable issue observation changes only the link revision and never its RETAINED protection state. */
        @Update("""
                UPDATE prd_execution_retention SET issue_observation_json=#{observation}, \
                issue_observation_hash=#{hash},
                    version=version+1, updated_at=CURRENT_TIMESTAMP(3)
                WHERE tenant_no=#{row.tenantNo} AND id=#{row.id} AND entity_no=#{row.entityNo} AND \
                store_no=#{row.storeNo}
                    AND execution_no=#{row.executionNo} AND original_row_id=#{row.originalRowId}
                    AND retention_no=#{row.retentionNo} AND reference_hash=#{row.referenceHash}
                    AND original_hash=#{row.originalHash} AND request_hash=#{row.requestHash}
                    AND confirmation_operation_no=#{row.confirmationOperationNo}
                    AND version=#{row.version} AND version < 9223372036854775807 AND deleted=0
                    AND state='RETAINED' AND issue_observation_hash IS NULL AND issue_observation_json IS NULL
                """)
        int recordIssuedObservation(@Param("row") PrdExecutionRetention row,
                @Param("observation") String observation, @Param("hash") String hash);

    }

    public interface CategoryMapper extends ProductTableMapper<PrdCategory> {
    }

    /**
     * 平台标准品。<b>跨商家共享的主数据，不注册数据域</b> —— 与 prd_category 同类：
     * 它不属于任何一家商家，按商家维度过滤它只会让所有人都搜不到。
     */
    public interface SpuStdMapper extends ProductTableMapper<ai.neargo.shop.product.entity.PrdSpuStd> {
    }
}
