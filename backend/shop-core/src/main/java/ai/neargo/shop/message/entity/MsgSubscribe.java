package ai.neargo.shop.message.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 订阅消息授权（C-MS-01）。**拒绝也要记**：
 * 不记的话每次进页面都会再弹一次授权框，用户会直接把小程序删了。
 */
@Getter
@Setter
@TableName("msg_subscribe")
public class MsgSubscribe extends BaseEntity {

    private String userNo;
    private String templateId;
    private Boolean accepted;

    /**
     * 未消耗的发送额度。微信订阅消息是**一次性**授权：用户点一次「允许」= 攒一次
     * 发送机会，发一条耗一条。{@code accepted} 只记最近一次的选择（给「要不要再弹窗」用），
     * 能不能发要看这里 —— 只看 accepted 的话，一次授权会被反复用来发无限条。
     */
    private Integer quota;

    private Long at;
}
