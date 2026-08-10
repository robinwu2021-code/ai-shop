package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 类目授权码。商家按**码**获得经营授权，类目按码设置门槛。
 *
 * <p>为什么不直接按类目节点授权：`CAT111 叶菜`、`CAT112 根茎菜` 都要 `FRESH_VEG`，
 * 而类目树会重构（合并、改名、加层），「能不能卖菜」这件事不会。
 * 按节点授权的话，运营每合并一次类目就要给全部商家重授一遍权。
 */
@Getter
@Setter
@TableName("sys_auth_code")
public class SysAuthCode extends BaseEntity {

    private String code;
    private String name;

    /** 需要的资质证件名。空 = 无证件要求。 */
    private String requiredQualification;

    private Integer sort;
    private Boolean enabled;
}
