package ai.neargo.shop.pay.entitlement.entity;
import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter
@TableName("pay_ent_exception")
public class PayEntException extends BaseEntity {
    private String exceptionNo;
    private String entityNo;
    private String kind;
    private String bizNo;
    private String status;
    private String evidence;
    private String lastAction;
    private String handledBy;
    private LocalDateTime handledAt;
}
