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

    /**
     * 这个门槛要哪一类证：{@code BUSINESS_LICENSE / FOOD_PERMIT / FOOD_WORKSHOP / OTHER}，
     * 与 {@code mch_qualification.qual_type} 同值域。{@code null} = 无需证件（日用百货、家政）。
     *
     * <p>与 {@link #requiredQualification} 的分工：那一列是给人读的一句话
     * （「食品经营许可证」），这一列是<b>给程序判的类型</b>。只有后者，
     * 「这家店传了执照能解锁哪几类」才是机器算得出来的 ——
     * 而在它之前，这个问题只能靠人对着两张表比，于是线上一条资质、一条授权都没有。
     */
    private String qualType;

    private Integer sort;
    private Boolean enabled;
}
