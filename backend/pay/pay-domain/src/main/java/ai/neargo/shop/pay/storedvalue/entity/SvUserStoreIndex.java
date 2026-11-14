package ai.neargo.shop.pay.storedvalue.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("sv_user_store_index")
public class SvUserStoreIndex extends BaseEntity {
    private String userNo;
    private String customerNo;
    public String getCustomerIdentity(){return customerIdentity();}
    public String customerIdentity(){return customerNo==null?userNo:customerNo;}
    private String entityNo;
    private String storeNo;
    private String storeName;
    private Integer hasRecharge;
    private LocalDateTime lastRechargeAt;
    private LocalDateTime lastConsumeAt;
    private LocalDateTime lastActivityAt;
    private String lastActivityType;
    private String storeStatus;
}
