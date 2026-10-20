package ai.neargo.shop.pay.entitlement.entity;
import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;
import java.time.LocalDateTime;
@Getter @Setter @TableName("pay_ent_recon_run")
public class PayEntReconRun extends BaseEntity {
    private String reconNo;
    private String requestKey;
    private String payloadHash;
    private String entityNo;
    private String axis;
    private String currency;
    private LocalDate bizDate;
    private String marketZone;
    private String status;
    private String scanCursor;
    private Long checkedCount;
    private Long diffCount;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}
