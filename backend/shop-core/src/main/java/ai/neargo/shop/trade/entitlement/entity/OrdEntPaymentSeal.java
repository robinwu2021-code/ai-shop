package ai.neargo.shop.trade.entitlement.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Once cash initiation is allowed, payable amount can no longer change. */
@Getter
@Setter
@TableName("ord_ent_payment_seal")
public class OrdEntPaymentSeal extends BaseEntity {
    private String orderNo;
    private Long payAmount;
    private String currency;
}
