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
    private String accountNo;
    private String entityNo;
    private String profileNo;
    private String externalAccountRef;
    private String activeAuthorizationNo;
    private String status;
}
