package ai.neargo.shop.community.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 社区（小区/网格）。用户的商品池、价格、履约时效都由它决定。
 *
 * <p>经纬度存 ×1e6 的整数：C 端选社区只需要「谁更近」的排序，米级精度足够，
 * 而整数比较在 SQL 里比浮点稳定得多。
 */
@Getter
@Setter
@TableName("cmt_community")
public class CmtCommunity extends BaseEntity {

    public static final String KIND_ESTATE = "ESTATE";
    public static final String KIND_VILLAGE = "VILLAGE";

    private String communityNo;
    private String name;
    private String address;

    private Integer latE6;
    private Integer lngE6;

    /** OPEN / CLOSED —— 开城开关（P-2.1.2）。CLOSED 的社区不出现在选点列表。 */
    private String status;
    /**
     * @deprecated 自由文本的城市码，**从来没有生效过**。
     *     它的原注释写着「scope=CITY 的商家靠它判定可达」，而 {@code MerchantServiceImpl}
     *     有两处明确写着「一期只有一个城市，先不按 city_code 收紧」——
     *     被声明、被塞进 VO，然后没有任何逻辑读它，开发库里也全是 NULL。
     *     用 {@link #regionCode} 代替：那是国标码，能拼出层级、能被「按区覆盖」命中。
     *     列暂时保留（见 V32 的说明），确认所有环境都没有值之后再删。
     */
    @Deprecated
    private String cityCode;

    /**
     * 所属行政区划（{@code sys_region.region_code}），**建议挂到街道级**。
     *
     * <p>社区不是行政层级，是平台的运营单元（一个小区）；但它必须挂在区划下，
     * 否则 ADR-013 的「按区/按街道覆盖」命中不了它 —— 商家勾一个「西湖区」，
     * 要能展开成该区下的全部社区。
     *
     * <p>空 = 尚未归属。**不兜底猜**：按名字猜城市会把「阳光花园」归到任意一个
     * 同名小区所在的区，而错误的归属不会报错，只会让这个社区在按区覆盖时
     * 悄悄出现在别人的范围里。宁可空着让运营去补。
     */
    private String regionCode;

    /**
     * ESTATE 小区 / VILLAGE 村。<b>只用于展示与统计口径，不参与匹配</b> ——
     * 匹配一律走 regionCode 前缀与 communityNo，避免出现第二套分类维度。
     */
    private String kind;

    /**
     * 村聚落对应的官方村码（12 位，来自 sys_region 村级词典）。
     * 查重（同一个官方村不能开成两个聚落，唯一键兜底）+ 与国家数据可对账。小区留空。
     */
    private String originCode;

    /** MERCHANT 商家提报定位 / OPS 运营补录 / SEED 种子 —— 分清「坐标是空的」与「没人核过」 */
    private String coordsSource;


    /** 本社区是否开放积分。四级串联的第二级 —— 上层关，下层一定关。 */
    private Boolean pointsEnabled;

    /** 网格：城市与社区之间的运营划分单位，BD 按网格分片包干。 */
    private String grid;

    /**
     * 覆盖围栏半径（米）。C 端按它判断地址是否落在本社区内。
     *
     * <p>默认 1000 而不是 0：0 意味着「这个社区覆盖不到任何地址」，
     * 而它看起来像「还没配」—— 一个默认值就能让整个社区静默失效。
     */
    private Integer fenceRadius;
}
