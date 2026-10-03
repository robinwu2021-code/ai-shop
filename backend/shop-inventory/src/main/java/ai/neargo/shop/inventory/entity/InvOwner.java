package ai.neargo.shop.inventory.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 业主：库存归谁所有 */
@Getter
@Setter
@TableName("inv_owner")
public class InvOwner extends InvMutableEntity {

    /** 业主业务键，本域生成 */
    private String ownerId;

    private String name;

    /** 嵌入平台时 = mch_entity.entity_no；独立交付时为空 */
    private String externalRef;

    /** ACTIVE / ARCHIVED */
    private String status;

}
