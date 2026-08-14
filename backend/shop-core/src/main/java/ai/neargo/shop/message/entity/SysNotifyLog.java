package ai.neargo.shop.message.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 短信/邮件发送记录。
 *
 * <p><b>不继承 {@code BaseEntity}</b>：那上面有逻辑删除与更新时间，
 * 而发送记录是**只追加**的事实流水——它没有「修改」这个动作，
 * 也不该能被删（能删的审计等于没有审计）。
 *
 * <p><b>{@code target} 存掩码</b>：这张表运营都看得到，而收件人是用户的手机号与邮箱。
 * 要查具体一条，靠 {@code providerMsgId} 去通道后台查——通道那边本来就有明文。
 */
@Getter
@Setter
@TableName("sys_notify_log")
public class SysNotifyLog {

    public static final String SMS = "SMS";
    public static final String MAIL = "MAIL";
    /** 微信小程序订阅消息（服务通知）。target 存掩码后的 openid。 */
    public static final String WXSUB = "WXSUB";
    /** App 推送（个推/uni-push，ADR-018）。target 存掩码后的 clientId。 */
    public static final String PUSH = "PUSH";

    public static final String SENT = "SENT";
    public static final String FAILED = "FAILED";

    /** 验证码。自动触发，没有操作人。 */
    public static final String BIZ_OTP = "OTP";
    /** 运营账号初始密码。 */
    public static final String BIZ_OPS_INIT_PASSWORD = "OPS_INIT_PASSWORD";
    /** 运营账号密码重置。 */
    public static final String BIZ_OPS_RESET_PASSWORD = "OPS_RESET_PASSWORD";
    /** 运营端页面上手动触发的测试发送。 */
    public static final String BIZ_TEST = "TEST";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String notifyNo;
    private String channel;
    private String bizType;

    /** **掩码后的**收件人。明文不落库 */
    private String target;

    /** 短信为阿里云模板号；邮件为主题 */
    private String templateCode;

    /**
     * 平台业务模板号（{@code msg_template.template_no}）。自由文本发送为空。
     *
     * <p><b>与 {@link #templateCode} 不是一回事</b>：那个是**通道方的**码
     * （阿里云 SMS_xxx / 邮件主题），排查时拿它去通道后台查回执；
     * 这个是我们自己的号，对应运营能改的那份模板，用来回答
     * 「这条模板最近发了多少次、还能不能下线」。
     */
    private String templateNo;

    private String status;

    /** 通道返回的错误码与消息，失败时才有 */
    private String error;

    /** 阿里云 {@code BizId} / 邮件 {@code Message-ID} —— 找通道对账靠它 */
    private String providerMsgId;

    /** 谁触发的。OTP 这类自动发出的为空 */
    private String operatorNo;

    private String clientIp;

    private LocalDateTime createdAt;
}
