package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Durable stock admission metadata; never an additional stock balance. */
@Getter
@Setter
@TableName("prd_stock_writer")
public class PrdStockWriter extends BaseEntity {
    private String entityNo;
    private String mode;
}
