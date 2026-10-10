package ai.neargo.shop.message.api.biz;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.message.NotifyScene;
import ai.neargo.shop.message.entity.MchNotifyPref;
import ai.neargo.shop.message.entity.NotifyChannel;
import ai.neargo.shop.message.notify.MerchantChannelService;
import ai.neargo.shop.message.notify.MerchantNotifyPrefs;
import ai.neargo.shop.message.notify.MerchantWecomWebhook;
import ai.neargo.shop.message.notify.WeComBotSender;
import ai.neargo.shop.spi.notify.NotifyBizType;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 商家端「通知设置」（TDD-来单四渠道与商家通知设置 §2.4）。
 *
 * <p>来单这一条四条腿同时走，这里是店主管那四个开关的地方，
 * 外加录入这家店自己的企业微信群机器人地址。
 *
 * <p><b>粒度是门店</b>（用户 2026-10-10 订正）：改的是
 * {@link BizContext#currentStoreNo()} 那一家，由请求头 {@code X-Store-No} 指定。
 * 自营一个主体下已有 4 家店，各店的人不同、各店可以有自己的群。
 *
 * <p><b>不要求 BizPerms</b>（登记在 {@code BizEndpointPermTest} 的 PUBLIC 表）——
 * 同 {@code /biz/message} 的理由：作用域由当前门店限住，改的永远是自己这家店的设置。
 * 要权限码的话，恰恰是收不到来单提醒的那个人没法去把开关打开。
 *
 * <p><b>webhook 永不回显</b>：它是凭据（拿到的人都能往那个群发消息），
 * 回显只给「已配置 / 未配置」。想换就重新填一次。
 */
@Profile("api")
@RestController
public class BizNotifySettingController {

    /**
     * 这一屏管的三个场景：来单、售后申请、新评价。
     *
     * <p><b>顺序即页面上的顺序</b>，而来单排第一 —— 「最重要的通知就是消费者下单后」
     * （用户 2026-10-10）。这三个正好是 {@code fanOutToStaff} 处理的全部 B 端场景，
     * 也正好是三个事件都带 {@code storeNo} 的那三个（后两个的 storeNo 是同一天补的）。
     */
    private static final List<String> SCENES = List.of(
            NotifyScene.SUB_ORDER_PAID, NotifyScene.AFTER_SALE_APPLIED, NotifyScene.REVIEW_CREATED);

    private final MerchantNotifyPrefs prefs;
    private final MerchantChannelService channels;
    private final MerchantWecomWebhook webhooks;
    private final WeComBotSender sender;

    public BizNotifySettingController(MerchantNotifyPrefs prefs, MerchantChannelService channels,
                                      MerchantWecomWebhook webhooks, WeComBotSender sender) {
        this.prefs = prefs;
        this.channels = channels;
        this.webhooks = webhooks;
        this.sender = sender;
    }

    /**
     * @param scene    场景码（{@link #SCENES} 之一）
     * @param switches 四个开关，键是通道码（{@link MchNotifyPref#SWITCHABLE} 的顺序）。
     *                 <b>是店主自己那一层</b>，不是与平台总闸串联后的结果 ——
     *                 页面要显示的是「我选了什么」，而不是「现在实际会不会发」
     */
    public record SceneSwitchesVO(String scene, Map<String, Boolean> switches) {
    }

    /**
     * @param scenes     三个场景各一组开关，顺序即页面顺序
     * @param wecomReady 这家店的企微群配过没有。<b>URL 本身不回传</b> —— 它是凭据
     */
    public record SettingVO(List<SceneSwitchesVO> scenes, boolean wecomReady) {
    }

    @GetMapping("/biz/notify/setting")
    public SettingVO setting() {
        String storeNo = requireStore();
        return new SettingVO(
                SCENES.stream()
                        .map(sc -> new SceneSwitchesVO(sc, prefs.switchesOf(storeNo, sc)))
                        .toList(),
                webhooks.of(storeNo).isPresent());
    }

    /**
     * @param scene   场景码。**必须在 {@link #SCENES} 里** —— 别的场景没有门店维度，
     *                存进去也没人读，而店主会以为自己关掉了
     * @param channel {@link MchNotifyPref#SWITCHABLE} 之一。INAPP 会被拒 —— 站内信不可关
     */
    public record SwitchReq(String scene, String channel, Boolean enabled) {
    }

    @PutMapping("/biz/notify/setting")
    public SettingVO save(@RequestBody SwitchReq req) {
        String storeNo = requireStore();
        if (req == null || req.enabled() == null || !SCENES.contains(req.scene())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        prefs.set(storeNo, req.scene(), req.channel(), req.enabled(), SecurityUtils.currentUserNo());
        return setting();
    }

    /** @param webhook 企微群机器人地址。<b>进加密列，永不回显</b> */
    public record WecomReq(String webhook) {
    }

    /**
     * 存自己的企微群地址。
     *
     * <p>校验（含「凭证 JSON 必须有 webhook 字段」与「加密密钥必须已配」）
     * 都在 {@link MerchantChannelService#upsert} 里，与运营端那条入口同一份 ——
     * 两处各写一遍校验，迟早有一处松。
     */
    @PutMapping("/biz/notify/wecom")
    public SettingVO saveWecom(@RequestBody WecomReq req) {
        String storeNo = requireStore();
        String url = req == null || req.webhook() == null ? "" : req.webhook().trim();
        if (!url.startsWith("https://qyapi.weixin.qq.com/")) {
            // 填错地址的后果是「开关开着却永远收不到」，而那时没有任何线索指向这一格
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        // owner_no 存的是**门店号**（群按门店不按主体，见 MerchantWecomWebhook 的注释）
        channels.upsert(storeNo, NotifyChannel.TYPE_WEBHOOK, NotifyChannel.PROV_WECOM,
                "{}", "{\"webhook\":\"" + url + "\"}", SecurityUtils.currentUserNo());
        return setting();
    }

    /**
     * 往自己的群发一条测试。
     *
     * <p><b>失败要原样告诉店主</b> —— 这是四条出口里唯一一条他能自己验的，
     * 而「填了地址但群里没动静」的原因（地址失效、机器人被移出群、撞限流）
     * 只有企微的 errcode 说得清。这里不吞。
     */
    @PostMapping("/biz/notify/wecom/test")
    public boolean testWecom() {
        String url = webhooks.of(requireStore())
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
        return sender.sendMarkdown(NotifyBizType.TEST,
                "**通知设置测试**\n> 看到这条，说明来单提醒能发到这个群", url);
    }

    /**
     * 当前门店 —— 由请求头 {@code X-Store-No} 指定。
     *
     * <p>**不回落到「主体下的第一家店」**：那会让店主在没选店的情况下
     * 悄悄改了另一家店的设置，而页面上看不出改的是谁。
     */
    private String requireStore() {
        String storeNo = BizContext.current().currentStoreNo();
        if (storeNo == null || storeNo.isBlank()) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        return storeNo;
    }
}
