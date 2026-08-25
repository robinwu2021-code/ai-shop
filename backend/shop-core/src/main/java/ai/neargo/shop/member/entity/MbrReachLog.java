package ai.neargo.shop.member.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 触达记录：<b>频次闸查它，效果也算它</b>。
 *
 * <p>只增不改（除了回填打开与下单两个时刻）。「发过什么」是事实，
 * 用状态字段覆盖会丢掉历史 —— 而这正是「我们到底打扰了他几次」唯一能追的地方。
 */
@Getter
@Setter
@TableName("mbr_reach_log")
public class MbrReachLog extends BaseEntity {

    /** 店铺公告 */
    public static final String SCENE_NOTICE = "NOTICE";
    /** 唤回很久没来的人 */
    public static final String SCENE_WAKEUP = "WAKEUP";
    /** 发券通知 */
    public static final String SCENE_COUPON = "COUPON";

    private String reachNo;
    private String entityNo;
    private String memberNo;
    private String segmentNo;
    private String taskNo;
    private String channel;
    private String scene;
    private Long sentAt;
    private Long openedAt;
    /**
     * 收到后 7 天内下过单没有。<b>效果只认这个</b> ——
     * 打开率不是生意：他点开看了一眼然后关掉，对商家没有任何意义。
     */
    private Long orderedAt;
}
