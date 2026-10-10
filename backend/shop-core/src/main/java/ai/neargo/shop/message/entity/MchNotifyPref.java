package ai.neargo.shop.message.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 门店自己的通知开关（TDD-来单四渠道与商家通知设置 §2.1）。
 *
 * <p>一行 = 「这家**门店**在这个场景上，这条通道开不开」。
 * 粒度是门店不是主体：自营一个主体下已有 4 家店，各店的人不同、
 * 各店可以有自己的企微群 —— 「粮油店的来单别往鲜果的群里发」在主体粒度下表达不了。
 *
 * <p>⚠️ <b>这个粒度只管得到来单</b>：{@code SubOrderPaid} 带 {@code storeNo}，
 * 而售后申请与新评价那两个事件只有 {@code entityNo}，所以它们仍然只走平台总闸。
 * 与 {@link MsgSceneChannel}（平台级、运营配）**串联**：平台关了这里开也不发，
 * 商家级只能更严。
 *
 * <p><b>缺行 = 开。</b> 整张表最要紧的语义，别反过来 ——
 * 反过来的话这张表一建，所有存量商家当天就一条来单提醒都收不到，
 * 而症状是「没有消息」：没有报错、没有日志，商家只会以为最近没单。
 *
 * <p><b>没有 INAPP</b>：站内信是事实记录，恒发不可关（与 {@link MsgSceneChannel}
 * 的 INAPP 同一个口径）。{@link #SWITCHABLE} 就是这条规矩的那份名单 ——
 * 想关 INAPP 的请求在服务层被拒，前端被绕过也兜住。
 */
@Getter
@Setter
@TableName("mch_notify_pref")
public class MchNotifyPref extends BaseEntity {

    /** 微信订阅消息（商家在小程序里授权的那条） */
    public static final String CH_WXSUB = MsgSceneChannel.CH_WXSUB;
    /** 企业微信群机器人 */
    public static final String CH_WEBHOOK = SysNotifyLog.WEBHOOK;
    /** 短信（只发店主） */
    public static final String CH_SMS = MsgSceneChannel.CH_SMS;
    /** App 推送 */
    public static final String CH_PUSH = MsgSceneChannel.CH_PUSH;

    /**
     * 商家能自己开关的四条。**顺序即 B 端页面上的顺序**，
     * 端上不要另写一份 —— 两份名单迟早分叉，而分叉的症状是「页面上少一个开关」。
     */
    public static final List<String> SWITCHABLE = List.of(CH_WXSUB, CH_WEBHOOK, CH_SMS, CH_PUSH);

    private String storeNo;
    private String scene;
    private String channel;
    private Boolean enabled;
}
