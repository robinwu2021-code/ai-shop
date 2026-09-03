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

    /**
     * 门牌号（楼号-单元-室），V319 从 {@code detail} 里分出来。
     *
     * <p><b>与 detail 的区别不是长短，是来源</b>：地址主体现在由选点页给出（带坐标），
     * 门牌只能手打。合在一列里时，用户改一个字就可能让坐标与文字对不上，而没地方看得出来。
     * 存量地址这一列为空 —— 照旧只显示 detail 那一串。
     */
    private String houseNo;

    private Integer latE6;
    private Integer lngE6;

    private Boolean isDefault;

    /** 家 / 公司 / 其他。 */
    private String tag;
}
