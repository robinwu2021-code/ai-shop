package ai.neargo.shop.pay.storedvalue.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("sv_recharge_plan_version")
public class SvRechargePlanVersion extends BaseEntity {
    private String versionNo;
    private String planNo;
    private Integer versionIndex;
    private Long paidAmountMinor;
    private Long bonusAmountMinor;
    private String scopeSnapshot;
    private Integer schemaVersion;
    private String snapshotHash;
}
