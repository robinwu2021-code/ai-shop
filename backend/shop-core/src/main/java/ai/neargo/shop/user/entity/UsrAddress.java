package ai.neargo.shop.user.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 收货地址（M1 / R1）。
 *
 * <p>「默认地址至多一条」由应用层保证（设新默认时先清旧），**不建唯一索引**：
 * `(user_no, is_default)` 唯一会导致「非默认地址也只能有一条」，语义完全相反。
 */
@Getter
@Setter
@TableName("usr_address")
public class UsrAddress extends BaseEntity {

    private String addressId;
    private String userNo;

    private String name;

    /** 收件人手机号。一期明文存储，**出参按视角脱敏**（db-design §6）。 */
    private String phone;

    /**
     * 省市区整串（V193）。端上就是一个输入框/一次地图选点，拆不出三段来。
     * 下面三列保留给将来的结构化地址，现阶段不写。
     */
    private String region;

    private String province;
    private String city;
    private String district;
    private String detail;

    private Integer latE6;
    private Integer lngE6;

    private Boolean isDefault;

    /** 家 / 公司 / 其他。 */
    private String tag;
}
