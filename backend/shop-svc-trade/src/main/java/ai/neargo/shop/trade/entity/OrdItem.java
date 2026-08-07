package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 订单行。**商品信息全部是下单时的快照**（标题/图/规格/单价），不是外键引用。
 *
 * <p>商品会改名、会换图、会调价、会下架。订单是一份历史凭证，
 * 引用当前商品意味着「三个月前买的东西，详情页显示的是今天的价格」。
 */
@Getter
@Setter
@TableName("ord_item")
public class OrdItem extends BaseEntity {

    private String subOrderNo;
    private String orderNo;

    private String goodsNo;
    private String skuNo;

    private String title;
    private String cover;
    private String spec;

    /** 成交单价（分）——快照，不随商品调价变动。 */
    private Long price;
    private Integer qty;
    private Long amount;

    private String categoryType;
    /** FRESH 按重计价：下单时锁定的标称克重。 */
    private Integer nominalGram;

    /** 是否已实际称重；未称重时差价恒为 0。 */
    private Boolean weighed;

    /** 赠品行：价格为 0、不参与计价。认不出来的话，按行退款会把赠品也退钱。 */
    private Boolean isGift;

}
