package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 商品收藏：买家 × 商品（V343，TDD-C端商品收藏与送达判断）。
 *
 * <p>取消收藏走 {@link ai.neargo.shop.product.mapper.ProductMappers.GoodsFavoriteMapper#purge} 真删 ——
 * 软删行会占着唯一键 {@code uk_prd_goods_favorite}，同一件商品取消后就再也收藏不上。
 */
@Getter
@Setter
@TableName("prd_goods_favorite")
public class PrdGoodsFavorite extends BaseEntity {

    private String userNo;
    private String goodsNo;
}
