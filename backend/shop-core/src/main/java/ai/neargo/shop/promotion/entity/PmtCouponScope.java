package ai.neargo.shop.promotion.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 券的适用范围（<b>规则</b>）。
 *
 * <p>老模型只有一句文案 {@code scope_desc}「仅限粮油类」，而校验只看 {@code entity_no} ——
 * 买猫粮照样能用。文案与规则不一致时商家不会怀疑文案，他会认为是<b>算错了钱</b>。
 *
 * <p>只存号不存名字：类目会改名，商品会下架换标题，号不变。
 */
@Getter
@Setter
@TableName("pmt_coupon_scope")
public class PmtCouponScope extends BaseEntity {

    private String couponNo;
    private String scopeType;
    private String refNo;
}
