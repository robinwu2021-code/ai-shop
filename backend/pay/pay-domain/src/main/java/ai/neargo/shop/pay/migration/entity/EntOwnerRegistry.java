package ai.neargo.shop.pay.migration.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Persistent ownership identity: equal source numbers in different protocols are independent resources. */
@Getter
@Setter
@TableName("ent_owner_registry")
public class EntOwnerRegistry extends BaseEntity {
    private String sourceSystem;
    private String sourceKind;
    private String sourceNo;
    private String targetNo;
    private String ownerSystem;
    private String migrationBatchNo;
    private String cutoverWatermark;
    private String status;
}
