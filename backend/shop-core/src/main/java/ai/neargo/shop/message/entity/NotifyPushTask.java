package ai.neargo.shop.message.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 平台营销广播推送任务（设计：触达推送中台-模块抽象 · N6）。
 *
 * <p>运营主动发起的一次群发：圈人群、预估触达、定时下发。与事件驱动触达
 * （{@code NotificationConsumer}）分开 —— 那个是「系统必须告诉你」，这个是「平台想推给你」。
 */
@Getter
@Setter
@TableName("notify_push_task")
public class NotifyPushTask extends BaseEntity {

    // ---- 人群
    /** 全体装了 App（有推送令牌）的消费者。 */
    public static final String AUD_ALL_APP_USER = "ALL_APP_USER";
    /** 全体装了 App 的商家员工（平台面向 B 端的公告广播）。 */
    public static final String AUD_ALL_STAFF = "ALL_STAFF";

    // ---- 状态
    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_CANCELLED = "CANCELLED";

    private String taskNo;
    private String name;
    private String audienceType;
    private String channel;
    private String title;
    private String body;
    private String link;
    private LocalDateTime scheduledAt;
    private String status;
    private Integer estimatedCount;
    private Integer sentCount;
    private LocalDateTime finishedAt;
}
