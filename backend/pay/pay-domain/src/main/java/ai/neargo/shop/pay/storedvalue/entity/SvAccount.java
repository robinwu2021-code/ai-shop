package ai.neargo.shop.pay.storedvalue.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("sv_account")
public class SvAccount extends BaseEntity {
    private String accountNo;
    private String userNo;
    private String entityNo;
    private String currency;
    private String status;
    private Long principalMinor;
    private Long bonusMinor;
    private Long heldPrincipalMinor;
    private Long heldBonusMinor;
    private Long accountSeq;
}
