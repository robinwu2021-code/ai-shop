package ai.neargo.shop.commerce.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/** cim_product_profile本域持久化；物理主键不出契约边界。 */
@Getter
@Setter
@TableName("cim_product_profile")
public class CimProductProfile extends BaseEntity {
    private String profileNo;
    private String providerNo;
    private String productCode;
    private String market;
    private String protocolCode;
    private String capabilityDefinition;
    private Integer identitySchemaVersion;
    private String identityNamespace;
    private String storeIdentityScope;
    private String status;
}
