package ai.neargo.shop.pay.entitlement.entity;
import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter @TableName("pay_ent_exception_action")
public class PayEntExceptionAction extends BaseEntity {
    private String actionNo;
    private String operationKind;
    private String exceptionNo;
    private String requestKey;
    private String payloadHash;
    private String reason;
    private String entityNo;
    private String actorNo;
    private String status;
    private String responseSnapshot;
}
