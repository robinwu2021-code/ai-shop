package ai.neargo.shop.message.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 消息模板（P-14.1.1）。
 *
 * <p><b>为什么需要它</b>：{@code msg_subscribe.template_id} 一直在引用模板 ID，
 * 而没有任何表管理这些模板——模板是谁建的、还启不启用、正文长什么样，全都无处可查。
 * 运营想停掉一个扰民的模板只能去微信后台改，平台侧完全不知情。
 */
@Getter
@Setter
@TableName("msg_template")
public class MsgTemplate extends BaseEntity {

    /** 订阅消息（微信）。 */
    public static final String SUBSCRIBE = "SUBSCRIBE";
    /** App 推送。 */
    public static final String PUSH = "PUSH";
    /** 站内信。 */
    public static final String INBOX = "INBOX";

    /** 默认语言。**存量与新建都落它** —— 回落链的终点（G2c）。 */
    public static final String LANG_DEFAULT = "zh-CN";

    private String templateNo;
    private String name;
    private String channel;

    /**
     * 语言（zh-CN / en / ar）。
     *
     * <p><b>与 templateNo 一起构成唯一键</b>：一条业务模板 + N 份翻译，
     * 而不是三个模板号。按模板号分语言的话，运营端的模板列表会变成三倍长
     * 且无法分组 —— 一条业务模板在上面出现三次，认不出哪三条是同一件事。
     */
    private String lang;

    private String content;

    /** 渠道侧模板 ID（如微信的）。站内信为空。 */
    private String providerTemplateId;

    /** 停用后引用它的推送发不出去。 */
    private Boolean enabled;
}
