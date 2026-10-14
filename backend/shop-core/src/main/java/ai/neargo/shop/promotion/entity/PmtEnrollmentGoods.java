package ai.neargo.shop.promotion.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 报名的商品。平台活动只对这些货生效 */
@Getter
@Setter
@TableName("pmt_enrollment_goods")
public class PmtEnrollmentGoods extends BaseEntity {

    private String enrollmentNo;
    private String goodsNo;
}
