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
    /** 冗余主体号：运营端要按商家看「这家店被摆进了哪些专题」，没有它每次都要 join */
    private String entityNo;
    private Integer sort;
}
