package ai.neargo.shop.message.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 客服工单。{@code repliedBy} 记客服身份 —— 代客操作要能追到人（M6 权限边界）。 */
@Getter
@Setter
@TableName("notify_ticket")
public class MsgTicket extends BaseEntity {

    public static final String OPEN = "OPEN";
    public static final String REPLIED = "REPLIED";
    public static final String CLOSED = "CLOSED";

    /** C 端用户提的工单（存量行的默认值）。 */
    public static final String SOURCE_C_USER = "C_USER";
    /** 商户 App 的问题回报（TDD-商户App问题回报与版本）。两边按它隔离，不靠账号号段恰好不撞。 */
    public static final String SOURCE_B_MERCHANT = "B_MERCHANT";

    private String ticketNo;
    private String userNo;
    private String subject;
    private String content;
    private String orderNo;
    private String status;
    private String reply;
    private Long repliedAt;
    private String repliedBy;
    /** 指派给的客服 staffNo */
    private String assignedTo;
    /** 指派时间（ms） */
    private Long assignedAt;

    // ---- 商户问题回报（V638）。C 端工单这些列都为空，source=C_USER

    /** {@link #SOURCE_C_USER} / {@link #SOURCE_B_MERCHANT} */
    private String source;
    /** BUG / PAYMENT / IDEA / OTHER */
    private String category;
    /** 提交时所在门店（已核对属于该账号） */
    private String storeNo;
    /** 带了门店号但不属于该账号而被丢弃：客服据此知道「他提交时已经进不去那家店了」 */
    private Boolean storeDropped;
    private String clientVersion;
    /** ANDROID / IOS / OTHER */
    private String clientPlatform;
    private String errorCode;
    private String page;
    /** 清洗后的诊断数组 JSON（{@code FeedbackDiagnostics}）；90 天后清空 */
    private String diagnostics;
}
