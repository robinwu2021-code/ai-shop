package ai.neargo.shop.message.notify.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.common.captcha.CaptchaService;
import ai.neargo.shop.common.ratelimit.RateLimiter;
import ai.neargo.shop.common.ratelimit.RateRule;
import ai.neargo.shop.message.MessageService;
import ai.neargo.shop.message.entity.MsgMessage;
import ai.neargo.shop.message.entity.MsgPushToken;
import ai.neargo.shop.message.entity.MsgSubscribe;
import ai.neargo.shop.message.entity.SysNotifyLog;
import ai.neargo.shop.message.notify.NotifyLogService;
import ai.neargo.shop.spi.notify.PushPort;
import ai.neargo.shop.spi.notify.WxSubscribePort;
import ai.neargo.shop.message.notify.port.NotifyLoggingMailPort;
import ai.neargo.shop.message.notify.port.NotifyLoggingSmsPort;
import ai.neargo.shop.message.mapper.MessageMappers.NotifyLogMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/** {@link NotifyLogService} 的实现。 */
@Service
public class NotifyLogServiceImpl implements NotifyLogService {

    /** 测试发送：同一个操作人每小时的上限。人工点几下够用，脚本刷不动 */
    private static final RateRule TEST_SEND_PER_OPERATOR =
            RateRule.of("notify.test", Duration.ofHours(1), 10);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final NotifyLogMapper mapper;
    private final CaptchaService captcha;
    private final RateLimiter limiter;
    private final NotifyLoggingSmsPort smsPort;
    private final NotifyLoggingMailPort mailPort;
    private final ai.neargo.shop.message.notify.WxSubscribeSender wxSender;
    private final ai.neargo.shop.message.notify.PushSender pushSender;
    private final ai.neargo.shop.message.MessageService messageService;
    private final ai.neargo.shop.message.mapper.MessageMappers.SubscribeMapper subscribeMapper;
    private final ai.neargo.shop.message.mapper.MessageMappers.PushTokenMapper pushTokenMapper;
    private final boolean rateLimitOn;

    public NotifyLogServiceImpl(NotifyLogMapper mapper, CaptchaService captcha, RateLimiter limiter,
                            NotifyLoggingSmsPort smsPort, NotifyLoggingMailPort mailPort,
                            ai.neargo.shop.message.notify.WxSubscribeSender wxSender,
                            ai.neargo.shop.message.notify.PushSender pushSender,
                            ai.neargo.shop.message.MessageService messageService,
                            ai.neargo.shop.message.mapper.MessageMappers.SubscribeMapper subscribeMapper,
                            ai.neargo.shop.message.mapper.MessageMappers.PushTokenMapper pushTokenMapper,
                            @Value("${shop.otp.rate-limit:true}") boolean rateLimitOn) {
        this.mapper = mapper;
        this.captcha = captcha;
        this.limiter = limiter;
        this.smsPort = smsPort;
        this.mailPort = mailPort;
        this.wxSender = wxSender;
        this.pushSender = pushSender;
        this.messageService = messageService;
        this.subscribeMapper = subscribeMapper;
        this.pushTokenMapper = pushTokenMapper;
        this.rateLimitOn = rateLimitOn;
    }

    /** @param channel/status/bizType 传 null 表示不筛 */
    @Override
    public PageData<SysNotifyLog> list(String channel, String status, String bizType,
                                       long page, long size) {
        var q = Wrappers.<SysNotifyLog>lambdaQuery()
                .eq(channel != null && !channel.isBlank(), SysNotifyLog::getChannel, channel)
                .eq(status != null && !status.isBlank(), SysNotifyLog::getStatus, status)
                .eq(bizType != null && !bizType.isBlank(), SysNotifyLog::getBizType, bizType)
                .orderByDesc(SysNotifyLog::getId);
        // 平台侧运维记录，没有数据域概念
        List<SysNotifyLog> all = DataScopeContext.executeWithoutScope(() -> mapper.selectList(q));
        return PageData.ofAll(all, page, size);
    }

    /**
     * 测试发送。**只发得出去，读不回来** —— 不返回验证码内容，
     * 否则这个接口就成了「给任意手机号发一个我知道的验证码」，那正是它要防的事。
     *
     * @param target    手机号或邮箱
     * @param captchaId 图形验证码挑战 ID
     * @param code      用户输入的图形验证码
     */
    @Override
    public void testSend(String channel, String target, String level, TestContent content,
                         String captchaId, String code, String operatorNo) {
        if (target == null || target.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        /*
         * **验证码先验**：放在限流之后的话，攻击者可以用错误的验证码把
         * 操作人的限流额度刷满 —— 一个不需要正确验证码就能实施的拒绝服务。
         */
        captcha.verifyAndConsume(captchaId, code);

        if (rateLimitOn && !limiter.tryAcquire("notify:test:" + operatorNo, TEST_SEND_PER_OPERATOR)
                .allowed()) {
            throw BizException.of(ErrorCode.TOO_MANY_REQUESTS);
        }

        TestContent c = content == null
                ? new TestContent(null, null, java.util.Map.of()) : content;
        if (c.body() != null && c.body().length() > TestContent.MAX_BODY) {
            // 可填之后要有上限：不设的话这个入口就成了群发工具
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        switch (channel == null ? "" : channel) {
            case SysNotifyLog.MAIL -> mailPort.send(
                    target,
                    c.subjectOr("【数智邻购】通道联通测试"),
                    c.bodyOr("这是一封测试邮件，用于确认邮件通道可用。\n发送时间：" + LocalDateTime.now()),
                    SysNotifyLog.BIZ_TEST, operatorNo);
            case SysNotifyLog.WXSUB -> testWxSubscribe(target, c);
            case SysNotifyLog.PUSH -> testPush(target, level, c);
            /*
             * 默认短信。**只认模板参数，不认自由文本**：阿里云收的是
             * TemplateCode + TemplateParam，发未报备的内容会被直接拒
             * （界面上也照这个口径说明，见 TDD-触达中心界面优化 §2.3）。
             * 不填 code 时仍随机六位 —— 与改造前逐字相同。
             */
            default -> smsPort.sendOtp(target,
                    c.paramOr("code", "%06d".formatted(RANDOM.nextInt(1_000_000))),
                    SysNotifyLog.BIZ_TEST, operatorNo);
        }
    }

    /**
     * 微信订阅消息的模拟发送。**target 是 userNo，不是 openid** ——
     * 运营手上没有 openid，而且 openid 不该出现在运营界面上。
     *
     * <p><b>额度照常扣减</b>（{@link WxSubscribeSender} 内部逻辑）：不扣的话测的就不是
     * 真实链路，而「测通了、真发时不通」正是这种替身要防的事。
     * 代价是它会烧掉这个用户一次真实的订阅额度 —— 所以先做一次可读的预检，
     * 额度为 0 时直接告诉运营「换测试账号」，而不是发出去被微信以 43101 拒。
     */
    private void testWxSubscribe(String userNo, TestContent c) {
        precheckTestTarget(SysNotifyLog.WXSUB, userNo);
        // thing2 是微信模板里允许自定义的字段（≤20 字）。此前写死在网关里，
        // 改一句话都要发版 —— 现在从这里传，模拟发送与真实发送共用同一条路
        wxSender.orderArrived(userNo, 1, "pages/orders/index",
                c.paramOr("thing2", null));
    }

    /** 预检。**与真正发送共用同一份判断** —— 两份判断迟早分叉，而分叉时预检会放行一个发不出去的目标。 */
    @Override
    public void precheckTestTarget(String channel, String target) {
        if (target == null || target.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (SysNotifyLog.WXSUB.equals(channel)) {
            String templateId = wxSender.templateIdOf(WxSubscribePort.SCENE_ORDER_ARRIVED);
            boolean hasQuota = templateId != null && DataScopeContext.executeWithoutScope(() ->
                    subscribeMapper.selectCount(Wrappers.<MsgSubscribe>lambdaQuery()
                            .eq(MsgSubscribe::getUserNo, target)
                            .eq(MsgSubscribe::getTemplateId, templateId)
                            .gt(MsgSubscribe::getQuota, 0))) > 0;
            if (!hasQuota) {
                throw BizException.of(ErrorCode.NOTIFY_WX_QUOTA_EMPTY);
            }
        } else if (SysNotifyLog.PUSH.equals(channel)) {
            boolean hasDevice = DataScopeContext.executeWithoutScope(() ->
                    pushTokenMapper.selectCount(Wrappers.<MsgPushToken>lambdaQuery()
                            .eq(MsgPushToken::getReceiverNo, target))) > 0;
            if (!hasDevice) {
                throw BizException.of(ErrorCode.NOTIFY_NO_DEVICE);
            }
        }
        // 短信/邮件没有可预检的东西：号码对不对只有发出去才知道
    }

    /**
     * App 推送的模拟发送。**target 是 userNo，不是 clientId** —— 运营拿不到 cid。
     *
     * <p>没绑设备时给一条可读错误：静默成功会让人以为「推了但他没看见」，
     * 而真相是这个人压根没装 App，两者的下一步动作完全不同。
     *
     * @param level {@code RING} 时按响铃级推 —— B 端新订单就是这个形态，
     *              上线前必须能在真机上验一次它到底响不响（免费档还受厂商配额约束）
     */
    private void testPush(String userNo, String level, TestContent c) {
        precheckTestTarget(SysNotifyLog.PUSH, userNo);
        String title = c.subjectOr("通道联通测试");
        String body = c.bodyOr("这是一条测试推送，用于确认推送通道可用。");
        String link = "/pages/message/index";
        // 两个收件箱都试：一个人可能只在其中一端登录过 App
        for (String receiverType : List.of(MsgMessage.RECEIVER_USER, MsgMessage.RECEIVER_STAFF)) {
            if (PushPort.LEVEL_RING.equals(level)) {
                pushSender.ring(receiverType, userNo, title, body, link);
            } else {
                pushSender.notify(receiverType, userNo, title, body, link);
            }
        }
    }

    /**
     * 站内信的模拟发送。**形态与前四条不同**：它不进 {@code sys_notify_log}
     * （站内信自己就是一张可查的表），也不需要图形验证码 ——
     * 它发不出平台，骚扰不到任何外部的人。权限码与审计仍然要。
     */
    @Override
    public void testInApp(String receiverType, String receiverNo, String title, String body,
                          String link, String operatorNo) {
        if (receiverNo == null || receiverNo.isBlank() || title == null || title.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        // dedupKey 带操作人与时间：同一个运营连点两次要能各进一条，否则第二条会被当成重投丢掉
        String dedupKey = "OPS_TEST:" + operatorNo + ":" + System.currentTimeMillis();
        messageService.pushTo(receiverType, receiverNo, MessageService.SYSTEM,
                title, body == null ? "" : body, link, dedupKey);
    }
}
