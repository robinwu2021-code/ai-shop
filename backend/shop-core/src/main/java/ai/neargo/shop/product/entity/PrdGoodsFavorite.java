package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 商品收藏：买家 × 商品（V343，TDD-C端商品收藏与送达判断）。
 *
 * <p>会员营销库起并进通用收藏 {@code mbr_favorite}（TDD-商品领域迁到门店交易库 第 3 批，用户 2026-11-28 定）：
 * 同一张表里还有门店收藏，商品收藏是 {@code favorite_type = GOODS} 的那一类；读写一律从
 * {@code GoodsFavoriteMapper.goodsOf} 起手，免得把门店收藏读进来。
 *
 * <p>取消收藏走 {@link ai.neargo.shop.product.mapper.ProductMappers.GoodsFavoriteMapper#purge} 真删 ——
 * 唯一键 {@code uk_mbr_favorite_goods} 不含 deleted，软删行占着它，同一件商品取消后就再也收藏不上。
 */
@Getter
@Setter
@TableName("mbr_favorite")
public class PrdGoodsFavorite extends BaseEntity {
    /** 收藏对象类型：商品 */
    public static final String TYPE_GOODS = "GOODS";

    private String userNo;
    private String favoriteType = TYPE_GOODS;
    /** 商品所属主体，冗余便于按商家看被收藏数 */
    private String entityNo;
    private String goodsNo;

    /** 新表的 tenant_no 没有默认值，不填就是 NULL，唯一键挡不住重复 */
    public PrdGoodsFavorite() {
        setTenantNo("MAIN");
    }
}
