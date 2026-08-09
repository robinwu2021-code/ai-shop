package ai.neargo.shop.platform.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 行业注册表：商家的基础属性，**平台维护**（V40）。
 *
 * <p><b>行业与商品类目是两个维度</b>：行业挂商家（一家一个），
 * 类目挂商品（一家可以卖多个类目）。一家便利店的行业是「线下零售」，
 * 但它横跨生鲜、日用、饮料多个商品类目。
 *
 * <p>建这张表最主要的理由是 {@link #wechatMicroAllowed}：
 * 微信小微的准入白名单是按行业给的，而白名单是<b>通道的规则</b>会变 ——
 * 写死在代码里每次变都要发版。取值域仍在 shared 的 {@code Industry} 联合类型里，
 * 由 {@code capability.test.ts} 断言两边一致。<b>表管能力，类型管取值</b>。
 */
@Getter
@Setter
@TableName("sys_industry")
public class SysIndustry extends BaseEntity {

    public static final String CATERING = "CATERING";
    public static final String RETAIL = "RETAIL";
    public static final String LIFE_SERVICE = "LIFE_SERVICE";
    public static final String ENTERTAINMENT = "ENTERTAINMENT";
    public static final String TRANSPORT = "TRANSPORT";
    public static final String ONLINE = "ONLINE";
    public static final String OTHER = "OTHER";

    private String industry;

    /** 展示名，商家入驻时看到的就是它。 */
    private String name;

    private Integer sort;

    private Boolean enabled;

    /**
     * 该行业能否以<b>小微</b>主体在微信进件。
     *
     * <p><b>默认 0 是刻意的</b> —— 默认允许等于默认让商家撞墙：
     * 他填完全部资料、选了小微，进件时才被通道拒绝。
     */
    private Boolean wechatMicroAllowed;

    /**
     * 支付宝同上。
     *
     * <p>⚠️ 支付宝的行业限制<b>尚未确认</b>，seed 里全部保守置 0 ——
     * 确认前不放开支付宝侧的小微进件。
     */
    private Boolean alipayMicroAllowed;

    /**
     * 该行业是否<b>强制开启积分</b>（商家不可自行关闭）。
     *
     * <p>它补上了 {@code mch_entity.points_forced} 一直缺的判断依据 ——
     * 那一列的注释从建表起就写着「按行业强制开」，而行业字段直到 V40 才存在。
     */
    private Boolean pointsForced;

    /** 给运营看的说明：为什么这个行业是这个准入结论。 */
    private String remark;
}
