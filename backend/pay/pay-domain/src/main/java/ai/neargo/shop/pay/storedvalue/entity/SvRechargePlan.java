package ai.neargo.shop.pay.storedvalue.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("sv_recharge_plan")
public class SvRechargePlan extends BaseEntity {
    /** Generated unique-index projection; the database is the sole writer. */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String legacyNameKey;
    private String issuerNo;
    private String migratedReleaseNo;
    private String governedActivityNo;
    private String governedTierKey;

    private String planNo;
    private String entityNo;
    private String storeNo;
    private String name;
    private String currency;
    private String status;
    private String currentVersionNo;
}
