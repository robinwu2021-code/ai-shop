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

    /**
     * 下单时的<b>二级类目</b>快照。
     *
     * <p>与 {@link #categoryType} 是两件事：那个只有三档（标品/生鲜/服务），
     * 而积分规则按二级类目配 —— 生鲜里蔬菜和水果的毛利就不一样。
     *
     * <p><b>快照而不是发分时现查商品</b>：商品可以改类目，改完不该让历史订单的
     * 积分口径跟着变。事后查「这单当初为什么发 30 分」要能原样复现。
     *
     * <p>存量行为空：那些单的分早就发完了。
     */
    private String categoryNo;
    /** FRESH 按重计价：下单时锁定的标称克重。 */
    private Integer nominalGram;

    /** 是否已实际称重；未称重时差价恒为 0。 */
    private Boolean weighed;

    /** 赠品行：价格为 0、不参与计价。认不出来的话，按行退款会把赠品也退钱。 */
    private Boolean isGift;


    /**
     * 本行称重差价（分）：正 = 补款，负 = 退款；未称重时为 0。
     *
     * <p>差价发生在**行**上（是哪个商品称少了），子单上那列是本子单各行的汇总。
     * 只有汇总的话，用户问「差价 −3.50 是哪个商品」时客服答不上来。
     */
    private Long weighAdjustMinor;
}
