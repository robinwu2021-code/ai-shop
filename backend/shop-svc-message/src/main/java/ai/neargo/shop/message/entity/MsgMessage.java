package ai.neargo.shop.message.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 站内消息。
 *
 * <p>{@link #dedupKey} 是幂等键：事件是 at-least-once 投递的，
 * 同一事件重投时靠它挡住第二条 —— 用户不该因为投递器重试而收到两条「支付成功」。
 */
@Getter
@Setter
@TableName("msg_message")
public class MsgMessage extends BaseEntity {

    public static final String TRADE = "TRADE";
    public static final String MARKETING = "MARKETING";
    public static final String SYSTEM = "SYSTEM";

    private String messageNo;
    private String userNo;

    /** 列名 msg_type：type 在部分数据库里是保留字，且 SysOutbox 已有 eventType，避免混淆。 */
    private String msgType;

    private String title;
    private String body;
    private String link;
    private Boolean isRead;
    private String dedupKey;
    private Long at;
}
