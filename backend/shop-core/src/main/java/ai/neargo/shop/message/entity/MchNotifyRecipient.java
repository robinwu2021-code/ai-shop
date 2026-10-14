package ai.neargo.shop.message.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 门店的通知收件地址（TDD-来单四渠道与商家通知设置 §2.5）。
 *
 * <p>一个门店一行，三条通道各一列。与 {@link MchNotifyPref}（开关）分开：
 * 开关是「发不发」，这里是「发给谁」—— 关掉短信不该把填好的号删掉，
 * 重新打开时他得再填一遍。
 *
 * <p><b>三列都是明文</b>（用户 2026-10-10：「webhook 存明文即可，加密将来再考虑」）。
 * 手机号与邮箱本来就该明文 —— 它们是收件人，店主填完要能回显核对。
 * webhook 不同：它是凭据（拿到的人就能往那个群发消息），这个决定的代价记在
 * V395 的注释里，要改回加密时 {@link NotifyCredCipher} 那套是现成的。
 *
 * <p><b>店主的登录手机号不在这张表里</b>：那一个恒发、删不掉，真源是
 * {@code mch_account.login_phone}。冗余进来的话，店主改了登录号这里就是个过期的号，
 * 而症状是「短信发到旧号上」，没有任何报错。
 */
@Getter
@Setter
@TableName("mch_notify_recipient")
public class MchNotifyRecipient extends BaseEntity {

    /** 额外短信号最多几个。**店主的登录手机号不计在内** */
    public static final int MAX_EXTRA_PHONES = 2;

    private String storeNo;

    /**
     * 额外的短信接收号，<b>逗号分隔</b>，最多 {@link #MAX_EXTRA_PHONES} 个。
     * 空 = 只发店主自己。
     */
    private String smsPhones;

    /** 邮件接收地址。空 = 这家店不发邮件 */
    private String email;

    /** 企微群 Webhook 地址。<b>仍然不回显给前端</b> —— 存明文是一回事，下发是另一回事 */
    private String wecomWebhook;
}
