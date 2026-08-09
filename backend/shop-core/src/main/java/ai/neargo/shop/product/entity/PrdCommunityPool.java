package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 社区商品池：**只决定可见性，不存价**（TDD-backend §6.3）。
 *
 * <p>一个商家在哪些社区卖货由它表达。首页「按社区逛」= 这张表 join 商品表，
 * 价格始终来自 {@link PrdSku}。
 */
@Getter
@Setter
@TableName("prd_community_pool")
public class PrdCommunityPool extends BaseEntity {

    private String communityNo;
    private String goodsNo;
    private String entityNo;

    /** 排序权重，运营可置顶。 */
    private Integer sortWeight;
}
