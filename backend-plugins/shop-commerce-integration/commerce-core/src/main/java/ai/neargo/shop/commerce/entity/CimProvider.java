package ai.neargo.shop.commerce.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/** cim_provider本域持久化；物理主键不出契约边界。 */
@Getter
@Setter
@TableName("cim_provider")
public class CimProvider extends BaseEntity {
    private String providerNo;
    private String providerCode;
    private String name;
    private String status;
}
