package ai.neargo.shop.inventory.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 计量单位字典。<b>今天是留位，没有任何代码读它</b>（2026-09-02 查实）。
 *
 * <p>单位的真源是<b>商品</b>不是这张表：{@code inv_item.base_uom} 从平台商品的
 * {@code sale_unit} 投影进来，{@code InboundServiceImpl.uomOf} 取的是它、取不到就写死
 * {@code "PIECE"}；端上 {@code uomLabel()} 查的是 i18n 里写死的 {@code stock.uom} 映射表。
 * 这张表、这个实体、{@code UomMapper} 三样都在，而**一处 select 都没有**。
 *
 * <p><b>为什么不删</b>：删表要一支迁移，而它没有妨碍任何人；留着的代价是
 * 「单位字典没做」会被反复提出来（已经提过一次，见工单 T2）——
 * 所以代价用这段注释付，不用一支迁移付。
 *
 * <p><b>什么时候它才该活过来</b>：出现「商家要自定义单位」这个真实诉求时。
 * 那一刻要改的不是加一个维护页，而是把 {@code uomOf}、{@code base_uom} 的投影、
 * 端上那张映射表三处都指过来 —— 那是一件比字典页大得多的事，且要先想清楚
 * 与平台 {@code sale_unit} 的关系。**只加维护页做出来的是一个改了不影响任何东西的控件，
 * 而它看上去是能用的。**
 */
@Getter
@Setter
@TableName("inv_uom")
public class InvUom extends InvMutableEntity {

    private String uomCode;

    private String name;

    /** 1=可拆分（称重品）。称重品与计件品的分界 */
    private Integer divisible;

    private Integer sort;

}
