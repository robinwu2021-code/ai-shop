package ai.neargo.shop.pay.entitlement.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Payment-owned catalog row; published snapshots are never rewritten or deleted. */
@Getter
@Setter
@TableName("pay_ent_command_dedup")
public class PayEntCommandDedup extends BaseEntity {
    private String operationKind;
    private String requestKey;
    private String payloadHash;
    private String resourceNo;
    private String resultStatus;
    private String responseSnapshot;
}
