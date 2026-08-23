package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 规格值：黑色、500g、24cm。<b>值有自己的身份，才谈得上聚合、排序与比价。</b>
 *
 * <p>此前值只是模板 JSON 里的一对字符串：没有唯一约束（两条模板能给同一个 code 配不同文案）、
 * 没有归一量（「1斤」和「500g」是同一件事，但排序与比价都不知道）、没有别名。
 */
@Getter
@Setter
@TableName("prd_spec_value")
public class PrdSpecValue extends BaseEntity {

    private String valueNo;
    private String dimNo;

    /** 维度内唯一的码 W500G / CLRBLACK。<b>对外仍叫 optionCode</b>，与历史数据同名同义 */
    private String code;

    /** 展示文案，可改 —— 改了不影响已建商品：商品那侧存的是快照 */
    private String label;
    private String labelI18n;

    /**
     * 归一量：500g、半斤、0.5kg 都是 500。
     *
     * <p>没有它，「按规格从小到大」会把 1kg 排在 500g 前面（字符串序），
     * 「同规格比价」更是无从谈起 —— 而这两件事正是平台维护规格库的理由。
     */
    private BigDecimal numericValue;

    /** 与维度 unit 同口径。冗余在这里，是为了不连表就能比 */
    private String numericUnit;

    /** JSON 数组：{@code ["1斤","一斤"]}。识别、搜索与将来的自动归一用 */
    private String aliases;

    /**
     * PLATFORM / MERCHANT。
     *
     * <p><b>商家在平台维度下加的自有值也是 MERCHANT</b>，但它仍挂在同一根轴上
     * （dimNo 指平台的维度），所以「谁家 750g 的米更便宜」照样成立。
     * 用的人多了，运营可以把它提升为平台值 —— 改 scope，编号不变，商品不用重建。
     */
    /**
     * 被合并到哪个值。<b>非空即表示这一条已退役</b>（{@code status=MERGED}）。
     *
     * <p>合并不是删除：历史商品的 SKU 快照里存着被合并的那个编号，
     * 删掉之后那件货的规格就再也解释不出来了。顺着这一列还能找回保留的那一条。
     */
    private String mergedInto;

    /** 已被合并进别的值，读侧不再下发 */
    public static final String MERGED = "MERGED";

    private String scope;
    private String entityNo;
    private Integer sort;
    private String status;
}
