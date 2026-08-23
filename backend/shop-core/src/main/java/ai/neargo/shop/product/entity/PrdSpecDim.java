package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 规格项（维度）：颜色、重量、口径、材质。<b>全局一份，不按类目复制。</b>
 *
 * <p>与它取代的 {@code prd_spec_template} 的关键差别：那张表里「颜色」在每个类目下
 * 各存一份选项 JSON，加一个新颜色要改 N 处、漏一处那个类目就永远选不到它，而且不报错。
 * 拆出来之后类目只是<b>引用</b>这个维度（{@code prd_category_spec}），并可裁剪取值子集。
 */
@Getter
@Setter
@TableName("prd_spec_dim")
public class PrdSpecDim extends BaseEntity {

    /** 平台统一维护 */
    public static final String PLATFORM = "PLATFORM";
    /** 商家自建：只对这家店下发，且不参与跨店聚合 —— 这是它的定义，不是缺陷 */
    public static final String MERCHANT = "MERCHANT";

    /** 枚举取值（颜色、包装） */
    public static final String ENUM = "ENUM";
    /** 数值 + 单位（重量、容量、时长）。这类维度的值必须有 numericValue，否则排不了序 */
    public static final String QUANT = "QUANT";

    /** 销售规格：参与 SKU 笛卡尔积，每个组合各有价与库存 */
    public static final String SALE = "SALE";
    /**
     * 描述属性：整件商品一个值，<b>不生成 SKU</b>。
     *
     * <p>材质、产地、保质期多数时候属于这一类。全塞进规格组的后果是笛卡尔积爆炸 ——
     * 「不锈钢 × 24cm × 黑色」被迫变成一个要单独定价与备库存的行，而商家其实只想说
     * 「这口锅是不锈钢的」。本版只落字段，界面二期；等有了商品再拆就拆不回来了。
     */
    public static final String PROP = "PROP";

    public static final String ACTIVE = "ACTIVE";
    public static final String ARCHIVED = "ARCHIVED";

    private String dimNo;

    /** 语义码 COLOR / WEIGHT。值编号与 optionCode 都以它为前缀 —— 改码等于换一根轴 */
    private String code;

    private String name;
    private String nameI18n;

    /** {@link #ENUM} / {@link #QUANT} */
    private String valueType;

    /** QUANT 才有：g / ml / cm / 分钟 */
    private String unit;

    /** {@link #SALE} / {@link #PROP}。类目绑定可覆盖 —— 口味在熟食是 SALE，在预包装是 PROP */
    private String usageType;

    /**
     * 通用维度。<b>判据是「值的含义是否跨类目一致」</b>，不是「用在几个类目」：
     * 锅的黑色和手机的黑色是同一个黑，所以颜色通用；段位只在婴幼儿食品下讲得通，所以专用。
     * 只绑了一个类目的通用维度依然是通用维度 —— 明天绑第二个时不用做任何事。
     */
    private Boolean universal;

    private String scope;
    private String entityNo;
    private Integer sort;
    private String status;
}
