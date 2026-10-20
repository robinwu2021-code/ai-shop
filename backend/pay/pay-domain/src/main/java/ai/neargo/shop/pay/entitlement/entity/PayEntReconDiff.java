package ai.neargo.shop.pay.entitlement.entity;
import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter @TableName("pay_ent_recon_diff")
public class PayEntReconDiff extends BaseEntity {
    private String diffNo;
    private String reconNo;
    private String entityNo;
    private String resourceNo;
    private String diffKind;
    private String expectedSnapshot;
    private String actualSnapshot;
    private String exceptionNo;
    private String status;
    private String resolutionNote;
}
