package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 平台标准品：商家引用建品的**模子**。
 *
 * <p><b>无价、无库存、无履约、无上下架</b> —— 那些永远是商家的。
 * 标准品一旦带价，它就成了平台指导价，那是完全另一件事（且有法律含义）。
 *
 * <p>存在的理由是 {@code spec_groups} 里的 <b>optionCode</b>：
 * {@code optionCode}（B-4.5）做了「一期只写入不消费」，而它要消费的前提是
 * 「同一件货在不同店里指向同一个东西」—— 没有标准品，那个前提永远不成立。
 * 三家店各自录「本地菠菜」得到三个毫无关系的商品，聚合、比价、统计全都无从谈起。
 *
 * <p><b>一期是「复制 + 溯源」，不是完整的引用式</b>：取用时把字段填进商家品，
 * 标准品之后改了<b>不回流</b>（回流要配审计与回滚，先看商家用不用）。
 * 与「直接复制」的差别只剩 {@code prd_goods.std_no} 那一条线 ——
 * 而那条线正是聚合与统计要用的东西。
 */
@Getter
@Setter
@TableName("prd_spu_std")
public class PrdSpuStd extends BaseEntity {

    public static final String ACTIVE = "ACTIVE";
    public static final String ARCHIVED = "ARCHIVED";

    private String stdNo;

    /**
     * 所属类目。<b>必填</b> —— 形态由它派生，与商家品同一条规则。
     *
     * <p>商家取用时<b>类目不可改</b>（服务端覆盖请求值）：改了类目形态就变了，
     * 那就不是这个标准品了，而 {@code std_no} 还挂着，溯源会说谎。
     */
    private String categoryNo;

    private String title;
    /** JSON {@code {"en":…,"ar":…}}。译文附件，缺的语言回落 {@link #title}。 */
    private String titleI18n;
    private String subtitle;
    private String cover;
    /** JSON 数组。 */
    private String images;

    /**
     * JSON，与 {@code prd_goods.spec_groups} 同构，<b>每个选项必须带 optionCode</b>。
     *
     * <p>不带 code 的标准品与商家手输没有任何区别，它唯一的作用是让人
     * <b>以为</b>规格统一了 —— 与平台规格模板同一条校验（{@code SPEC_TEMPLATE_CODE_REQUIRED}）。
     */
    private String specGroups;

    /** 别名/品牌/俗称，空格分隔。一期按名称搜索，不做条码（生鲜与手工品本来就没有条码）。 */
    private String keywords;

    /** {@link #ACTIVE} / {@link #ARCHIVED}。归档<b>不影响已引用的商品</b> —— 那是溯源不是外键。 */
    private String status;

    /**
     * 被引用次数。<b>只服务运营侧排序与去重判断，不参与任何校验</b> ——
     * 所以由定时统计刷新即可，不挂在建品的写路径上（那会让每次建品多一次写）。
     */
    private Integer refCount;
}
