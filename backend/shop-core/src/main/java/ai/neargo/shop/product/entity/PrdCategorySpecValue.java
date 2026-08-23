package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 类目下的取值子集。<b>没有行 = 该维度全部值都能选。</b>
 *
 * <p>家居用品的颜色给四个、手机数码给另外五个，而值池是同一份 —— 这正是拆表的收益：
 * 换成复制模板的话，加一个新颜色要在每个类目下各改一遍。
 */
@Getter
@Setter
@TableName("prd_category_spec_value")
public class PrdCategorySpecValue extends BaseEntity {

    private String categoryNo;
    private String dimNo;
    private String valueNo;

    /**
     * 同一个值在这一类目下换个说法：500g 在蔬菜下叫「约1斤」，在粮油下就叫「500g」。
     * <b>换的是说法，不是轴</b> —— 归一量仍是 500，两边照样可比。
     */
    private String labelOverride;

    private Integer sort;
}
