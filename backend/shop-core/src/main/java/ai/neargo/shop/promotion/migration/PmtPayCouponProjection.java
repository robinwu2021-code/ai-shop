package ai.neargo.shop.promotion.migration;
import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;
@Getter @Setter @TableName("pmt_pay_coupon_projection")
public class PmtPayCouponProjection extends BaseEntity {
    private String instanceNo;private String userNo;private String entityNo;private String sourceSystem;private String sourceTemplateNo;
    private String targetTemplateNo;private String templateVersionNo;private String migrationBatchNo;private String planHash;
    private String issueNo;private String grantNo;private String actorNo;private String factHash;private LocalDateTime occurredAt;
}
