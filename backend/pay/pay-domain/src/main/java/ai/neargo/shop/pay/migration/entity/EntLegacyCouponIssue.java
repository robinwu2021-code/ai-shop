package ai.neargo.shop.pay.migration.entity;
import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;
@Getter @Setter @TableName("ent_legacy_coupon_issue")
public class EntLegacyCouponIssue extends BaseEntity {
    private String issueNo;private String batchNo;private String entityNo;private String sourceSystem;private String sourceTemplateNo;
    private String targetTemplateNo;private String templateVersionNo;private String actorNo;private String mode;
    private String frozenSnapshot;private String status;private String resultSnapshot;private LocalDateTime completedAt;
}
