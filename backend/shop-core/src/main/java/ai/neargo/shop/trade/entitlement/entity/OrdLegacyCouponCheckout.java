package ai.neargo.shop.trade.entitlement.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
@TableName("ord_legacy_coupon_checkout")
public class OrdLegacyCouponCheckout extends BaseEntity {
    private String payState;
    @TableField(updateStrategy=FieldStrategy.ALWAYS)
    private String payDecisionNo;
    @TableField(updateStrategy=FieldStrategy.ALWAYS)
    private LocalDateTime payAckedAt;
    @TableField(updateStrategy=FieldStrategy.ALWAYS)
    private LocalDateTime recoveryDueAt;
    private String checkoutNo;
    private String operationNo;
    private String userNo;
    private String entityNo;
    private String commandHash;
    private String stageHash;
    private String economicHash;
    private String factSnapshot;
    private String status;
    private String heldSnapshot;
    private String decisionSnapshot;
    private String reversalSnapshot;
    private String orderNo;
}
