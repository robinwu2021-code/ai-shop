package ai.neargo.shop.commerce.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/** cim_account本域持久化；物理主键不出契约边界。 */
@Getter
@Setter
@TableName("cim_account")
public class CimAccount extends BaseEntity {
    /** 状态取值（零售域状态短值换值表）：只在这里写字面量，别处引用本常量。 */
    public static final String AUTH_REJECTED = "AUTH_REJECTED";
    private String accountNo;
    private String entityNo;
    private String profileNo;
    private String externalAccountRef;
    private String activeAuthorizationNo;
    private String activeOperationNo;
    private String status;
}
