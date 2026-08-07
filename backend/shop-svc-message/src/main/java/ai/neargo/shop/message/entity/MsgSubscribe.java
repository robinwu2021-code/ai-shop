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
    private Long at;
}
