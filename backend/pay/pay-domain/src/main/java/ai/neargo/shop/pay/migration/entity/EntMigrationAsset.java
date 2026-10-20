package ai.neargo.shop.pay.migration.entity;
import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter @TableName("ent_migration_asset")
public class EntMigrationAsset extends BaseEntity {
    private String importNo;
    private String sourceSystem;
    private String sourceInstanceNo;
    private String instanceNo;
    private String baselineNo;
    private Integer archivalOnly;
    private Integer totalUnits;
    private Integer baselineRemainingUnits;
}
