package ai.neargo.shop.product.mapper;

import ai.neargo.shop.product.entity.PrdCategory;
import ai.neargo.shop.product.entity.PrdCommunityPool;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.entity.PrdSpecTemplate;
import ai.neargo.shop.product.entity.PrdStockLock;
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
    }

    public interface CommunityPoolMapper extends BaseMapper<PrdCommunityPool> {
    }

    public interface StockLockMapper extends BaseMapper<PrdStockLock> {
    }

    public interface CategoryMapper extends BaseMapper<PrdCategory> {
    }
}
