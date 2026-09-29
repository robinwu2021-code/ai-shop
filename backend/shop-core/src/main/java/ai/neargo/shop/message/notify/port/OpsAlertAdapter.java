package ai.neargo.shop.message.notify.port;

import ai.neargo.shop.common.Masks;
import ai.neargo.shop.message.MessageService;
import ai.neargo.shop.message.entity.MsgMessage;
import ai.neargo.shop.message.entity.SysNotifyLog;
import ai.neargo.shop.message.notify.WeComBotSender;
import ai.neargo.shop.spi.notify.OpsAlertPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link OpsAlertPort} 的实现：站内消息 + 企业微信群机器人。
 *
 * <p><b>薄转发</b> —— 这一层只做两件事：决定消息长什么样，以及把它交给两条出口。
 * 「谁该收」由 platform 域传进来（它才知道运营账号表长什么样）。
 */
@Component
public class OpsAlertAdapter implements OpsAlertPort {

    private static final Logger log = LoggerFactory.getLogger(OpsAlertAdapter.class);

    private final MessageService messageService;
    private final WeComBotSender weComBotSender;

    public OpsAlertAdapter(MessageService messageService, WeComBotSender weComBotSender) {
        this.messageService = messageService;
        this.weComBotSender = weComBotSender;
    }

    @Override
    public void newMerchantApply(NewApply apply, List<String> opsReceivers, String reviewLink) {
        String title = "新的入驻意向";
        String body = apply.shopName() + "（" + nvl(apply.category(), "未填经营范围") + "）";

        /*
         * 站内消息按人发 —— 收件箱按 receiverNo 分（MessageServiceImpl#list 查的是
         * 当前登录者）。
         *
         * <b>dedupKey 必须带收件人</b>：dedup 唯一索引是<b>全局</b>的，只用单号的话
         * 第一个人收到之后，其余的全被当成重投静默丢掉 —— 实测 11 个在用运营只进了 1 条。
         * {@code NotificationConsumer} 的类注释早就写明了这一条（B 端扇出给多个员工时
         * 用 {@code eventNo + ":" + userNo}），这里是同一个形状。
         *
         * **一个人失败不该拖累其余的**：逐个 try，否则第一个账号出问题时
         * 后面的人一条都收不到，而这种事只在有人报名时才发生，很难复现。
         */
        for (String receiver : opsReceivers == null ? List.<String>of() : opsReceivers) {
            try {
                messageService.pushTo(MsgMessage.RECEIVER_OPS, receiver, MessageService.SYSTEM,
                        title, body, reviewLink, "APPLY_NEW:" + apply.applyNo() + ":" + receiver);
            } catch (RuntimeException e) {
                log.warn("入驻意向的站内消息没进 {} 的收件箱：{}", receiver, e.toString());
            }
        }

        /*
         * 群机器人。**手机号在这里掩码** —— 群里可能有不该看全号的人，
         * 而运营点链接进运营端就能看到全号，那一侧有权限控制。
         */
        StringBuilder md = new StringBuilder()
                .append("**新的入驻意向**\n")
                .append("> 店铺：").append(apply.shopName()).append('\n')
                .append("> 类型：").append(nvl(apply.industry(), "未选")).append('\n')
                .append("> 经营范围：").append(nvl(apply.category(), "未填")).append('\n')
                .append("> 手机：").append(nvl(Masks.phone(apply.contactPhone()), "未填"));
        if (reviewLink != null && !reviewLink.isBlank()) {
            md.append('\n').append("[去运营端审核](").append(reviewLink).append(')');
        }
        weComBotSender.sendMarkdown(SysNotifyLog.BIZ_MERCHANT_APPLY, md.toString());
    }

    private static String nvl(String v, String def) {
        return v == null || v.isBlank() ? def : v;
    }
}
