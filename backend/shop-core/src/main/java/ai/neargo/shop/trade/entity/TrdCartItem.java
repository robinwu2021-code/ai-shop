package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 购物车行（服务端购物车）。
 *
 * <p>只存 {@code skuNo + qty + selected}，**不存价格与标题** ——
 * 购物车里的价格必须是实时的，存快照会让用户看到「加购时 8 块，结算时 10 块」的跳变，
 * 而这正是 c-app 的失效区/涨价提示要覆盖的场景（结算时统一以商品域为准）。
 */
@Getter
@Setter
@TableName("trd_cart_item")
public class TrdCartItem extends BaseEntity {

    private String userNo;
    private String goodsNo;
    private String skuNo;
    private Integer qty;

    /** 是否勾选。结算只取勾选行。 */
    private Boolean selected;
}
