package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 主题 × 商品，多对多。 */
@Getter
@Setter
@TableName("prd_topic_goods")
public class PrdTopicGoods extends BaseEntity {

    private String topicNo;
    private String goodsNo;
    /** 按商品挑选时为空串：唯一键 (topic_no, goods_no, sku_no) 里 NULL 互不相等，存 NULL 挡不住重复 */
    private String skuNo = "";
    /** 冗余主体号：运营端要按商家看「这家店被摆进了哪些专题」，没有它每次都要 join */
    private String entityNo;
    private Integer sort;
}
