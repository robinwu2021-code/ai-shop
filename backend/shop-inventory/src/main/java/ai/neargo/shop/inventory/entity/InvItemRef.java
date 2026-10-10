package ai.neargo.shop.inventory.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 外部引用：与外部世界唯一的钩子
 *
 * <p><b>只追加</b>：继承 {@link InvEntity} 而不是 {@link InvMutableEntity} ——
 * 实体上没有 updatedAt，「改一行」在编译期就不成立。
 */
@Getter
@Setter
@TableName("inv_item_ref")
public class InvItemRef extends InvEntity {

    private String ownerId;

    /** AISHOP 平台 / ERP 商家自有 / BARCODE 条码 / POS 收银。列名不用 system —— 那是 MySQL 8 的保留字 */
    private String refSystem;

    private String ref;

    private String itemId;

}
