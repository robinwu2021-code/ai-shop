package ai.neargo.shop.promotion.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 活动作用在哪些商品上。
 *
 * <p><b>用表不用 TEXT</b>：建活动时要按商品号反查「这件商品已经在哪些活动里」，
 * 好在保存前告诉商家「它已经在『周三特价』里，同类取最优」。
 * 塞在 {@code goods_nos TEXT} 里的话，这个问题只能全表扫。
 */
@Getter
@Setter
@TableName("pmt_activity_goods")
public class PmtActivityGoods extends BaseEntity {

    public static final String GOODS = "GOODS";
    public static final String CATEGORY = "CATEGORY";
    /** 全店。{@code refNo} 填 {@code *} */
    public static final String ALL = "ALL";

    private String activityNo;
    private String entityNo;
    private String scopeType;
    private String refNo;
}
