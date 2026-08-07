package ai.neargo.shop.message.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 客服工单。{@code repliedBy} 记客服身份 —— 代客操作要能追到人（M6 权限边界）。 */
@Getter
@Setter
@TableName("msg_ticket")
public class MsgTicket extends BaseEntity {

    public static final String OPEN = "OPEN";
    public static final String REPLIED = "REPLIED";
    public static final String CLOSED = "CLOSED";

    private String ticketNo;
    private String userNo;
    private String subject;
    private String content;
    private String orderNo;
    private String status;
    private String reply;
    private Long repliedAt;
    private String repliedBy;
}
