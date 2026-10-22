package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Restaurant draft persisted in the original frozen V1 table. */
@Getter
@Setter
@TableName("fnb_check_cart")
public class FnbCheckCart extends BaseEntity {
    private String entityNo;
    private String cartNo;
    private String storeNo;
    private String checkNo;
    private String sessionNo;
    private String status;
    private String buyerNo;
    private String orderSource;
    private String fulfillment;
    private LocalDateTime scheduledAt;
}
