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

    /**
     * ISO 3166-1 两位码，默认 {@code CN}。
     *
     * <p><b>非 CN 时端上整段换形状</b>：关掉地点搜索、附近、地图选点、省市区拆分 ——
     * 高德不覆盖海外，给一个点了搜不到东西的搜索框比没有更糟。
     * 那时 {@code province/city/district} 三列不再是国标行政区划，
     * 而是用户自己填的 City / State —— 这一点必须靠这一列才判得出来。
     */
    private String countryCode;

    /** 邮编。中国大陆不用，海外多数国家必填 */
    private String postalCode;

    /** 手机国家区号（不带 +），默认 86。位数校验按国家放宽，不再写死 11 位 */
    private String phoneCc;

    private Integer latE6;
    private Integer lngE6;

    private Boolean isDefault;

    /** 家 / 公司 / 其他。 */
    private String tag;
}
