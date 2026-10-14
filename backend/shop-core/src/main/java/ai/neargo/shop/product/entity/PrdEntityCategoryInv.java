package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 主体按类目设置记不记库存（V345，TDD-商品纳入进销存开关 §3）。
 *
 * <p><b>稀疏</b>：没有行 = 用平台默认（实物 / 生鲜记，服务 / 券 / 虚拟不记）。
 * <b>行只改不删</b> —— 唯一键 {@code uk_prd_entity_category_inv} 不含 deleted，
 * 软删行会占着键，同一个类目就再也设不上（经营类目那张表 2026-09-20 撞过一模一样的坑）。
 */
@Getter
@Setter
@TableName("prd_entity_category_inv")
public class PrdEntityCategoryInv extends BaseEntity {

    private String entityNo;
    private String categoryNo;
    /** 1 记库存 / 0 不记 */
    private Integer managed;
}
