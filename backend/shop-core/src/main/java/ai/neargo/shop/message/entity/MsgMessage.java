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

    /** 消费者（C 端，receiver_no = userNo）。 */
    public static final String RECEIVER_USER = "USER";
    /** 商家侧员工（B 端）。与 C 端共用账号池，receiver_no 同样是 userNo —— 同一个人的两个收件箱靠 type 分开。 */
    public static final String RECEIVER_STAFF = "STAFF";
    /** 平台运营（receiver_no = staffNo）。 */
    public static final String RECEIVER_OPS = "OPS";

    private String messageNo;
    private String receiverType;
    private String receiverNo;

    /** 列名 msg_type：type 在部分数据库里是保留字，且 SysOutbox 已有 eventType，避免混淆。 */
    private String msgType;

    private String title;
    private String body;
    private String link;
    private Boolean isRead;
    private String dedupKey;

    /**
     * 所用模板；系统消息可为空。
     *
     * <p><b>不是为了统计好看，是频控的执行前提</b>：触达频控里有一条
     * 「同一模板对同一用户的最小间隔」（P-14.1.4），不知道每条消息用的哪个模板，
     * 那条规则根本无法执行——配了也只是个摆设。
     */
    private String templateNo;
    private Long at;
}
