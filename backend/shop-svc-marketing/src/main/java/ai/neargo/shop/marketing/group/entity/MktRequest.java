package ai.neargo.shop.marketing.group.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 邻里求团需求单（C-8.2）。
 *
 * <p>{@link #ownerId} 是**团实例上的字段，不是身份**（ADR-004）：
 * 只有发起人能选定报价，但他不因此获得任何角色或收益。
 *
 * <p>{@link #lockedPrice} 是**选定那一刻的价格快照**（ADR-003）：
 * 之后商家改价不影响这一单 —— 不锁的话「不做事前审核」就等于让商家随时改价。
 */
@Getter
@Setter
@TableName("mkt_request")
public class MktRequest extends BaseEntity {

    public static final String COLLECTING = "COLLECTING";
    public static final String QUOTED = "QUOTED";
    public static final String LOCKED = "LOCKED";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String CLOSED = "CLOSED";

    private String requestNo;
    private String ownerId;
    private String title;
    private String description;
    private String images;
    private Integer expectCount;

    /** +1 数：**意向，不是订单**。 */
    private Integer interestCount;

    private String status;
    private String chosenQuoteNo;
    private Long lockedPrice;
    private Long endAt;
    /** 需求所属自提点/小区 —— 邻里的意义就在于此。 */
    private String pickupNo;

    /** 发起人心理价位（分），可不填；填了商家报价更有的放矢。 */
    private Long budgetMinor;

    /** MATCHED 后回填：选定报价转成的正式团。 */
    private String groupNo;

}
