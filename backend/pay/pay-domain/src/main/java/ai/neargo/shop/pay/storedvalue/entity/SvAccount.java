package ai.neargo.shop.pay.storedvalue.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("sv_account")
public class SvAccount extends BaseEntity {
    private String issuerNo;
    private String issuerVersionNo;

    private String accountNo;
    private String userNo;
    private String customerNo;
    public String getCustomerIdentity(){return customerIdentity();}
    public String customerIdentity(){return customerNo==null?userNo:customerNo;}
    private String entityNo;
    private String storeNo;
    private String currency;
    private String status;
    private Long principalMinor;
    private Long bonusMinor;
    private Long heldPrincipalMinor;
    private Long heldBonusMinor;
    private Long accountSeq;
}
