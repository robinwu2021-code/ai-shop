package ai.neargo.shop.promotion.migration;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Immutable source binding; LegacyCouponSourceCommitMapper is its sole writer. */
@Getter
@Setter
@TableName("pmt_migration_source")
public class PmtMigrationSource extends BaseEntity {
    private String sourceSystem;
    private String sourceTemplateNo;
    private String decisionNo;
}
