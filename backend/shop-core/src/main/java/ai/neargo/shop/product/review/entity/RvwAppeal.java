package ai.neargo.shop.product.review.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 商家对差评的申诉。
 *
 * <p>这是**唯一能把差评送进平台裁决台的入口** —— 平台端 P-13.1.3 的裁决页早就建好，
 * 此前没有单据表，那张台子收不到任何单，等于空转。
 *
 * <p>注意与风控域的「黑名单申诉」是两回事（一个是商家申诉差评，一个是被拉黑者申诉解禁），
 * 契约层刻意用了不同的类型名，这里也不要合并。
 */
@Getter
@Setter
@TableName("rvw_appeal")
public class RvwAppeal extends BaseEntity {

    public static final String PENDING = "PENDING";
    /** 申诉成立 —— 原评价下架。 */
    public static final String UPHELD = "UPHELD";
    /** 申诉驳回 —— 评价保留。 */
    public static final String REJECTED = "REJECTED";

    private String appealNo;

    /** 一条评价只能申诉一次：被驳回后再申诉，等于把裁决当抽奖。库里有唯一键。 */
    private String reviewNo;
    private String entityNo;

    private String reason;

    /** 举证图（聊天记录、物流截图），JSON 数组。 */
    private String images;

    private String status;
    private Long submittedAt;

    /** 裁决说明：**无论成立还是驳回都必须写** —— 商家会看到，「已读不处理」不是一种结果。 */
    private String verdict;
    private Long decidedAt;
    private String decidedBy;
}
