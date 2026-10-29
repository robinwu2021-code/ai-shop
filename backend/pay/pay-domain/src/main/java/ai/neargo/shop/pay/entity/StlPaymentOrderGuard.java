package ai.neargo.shop.pay.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/** Durable process evidence; amounts remain in the original payment ledger. */
@Getter
@Setter
@TableName("stl_payment_order_guard")
public class StlPaymentOrderGuard extends BaseEntity {
    private String orderNo;
}
