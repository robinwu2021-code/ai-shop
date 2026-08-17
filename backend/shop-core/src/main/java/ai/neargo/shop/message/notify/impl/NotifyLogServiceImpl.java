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
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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

    @Override
    public PageData<SysNotifyLog> list(String channel, String provider, String status, String bizType,
                                       String from, String to, String target,
                                       long page, long size) {
        String needle = maskedNeedle(target);
        /*
         * **先算好再传**：`ge(condition, col, value)` 的 value 是提前求值的，
         * 条件为 false 也照样执行 —— 直接写 startOf(from) 会在不筛时间时 NPE。
         */
        LocalDateTime fromAt = startOf(from);
        // 「到 8/14」在人的说法里含 8/14 一整天，所以是次日零点的开区间，
        // 不是 8/14 00:00 —— 按闭区间写的话选「今天到今天」会一条都查不到
        LocalDateTime toAt = endOf(to);
        var q = Wrappers.<SysNotifyLog>lambdaQuery()
                .eq(channel != null && !channel.isBlank(), SysNotifyLog::getChannel, channel)
                .eq(provider != null && !provider.isBlank(), SysNotifyLog::getProvider, provider)
                .eq(status != null && !status.isBlank(), SysNotifyLog::getStatus, status)
                .eq(bizType != null && !bizType.isBlank(), SysNotifyLog::getBizType, bizType)
                .ge(fromAt != null, SysNotifyLog::getCreatedAt, fromAt)
                .lt(toAt != null, SysNotifyLog::getCreatedAt, toAt)
                .like(needle != null, SysNotifyLog::getTarget, needle)
                .orderByDesc(SysNotifyLog::getId);
        // 平台侧运维记录，没有数据域概念。
        // **走库分页而不是全捞进内存**：这张表只增不删（发送记录长期保留是明确决定），
        // 每翻一页就把全表读进 JVM 的话，撑不到第一次真正需要查它的时候
        return DataScopeContext.executeWithoutScope(
                () -> PageData.of(mapper.selectPage(Page.of(page, size), q)));
    }

    /**
     * 把运营输入的收件人变成能匹配<b>掩码值</b>的模糊串。
     *
     * <p><b>这是这个功能唯一容易做错的地方</b>：库里存的是 {@code 138****8000}，
     * 运营手上有的是完整手机号 {@code 13800008000}。不做这层转换的话，
     * 输入正确的号码得到的是「没有记录」—— 而那会被读成「这条根本没发出去」，
     * 排查直接走错方向。
     *
     * <p>片段输入（尾四位、域名）原样模糊匹配即可：掩码保留了头尾，片段本来就能命中。
     */
    private String maskedNeedle(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        if (s.indexOf('@') > 0) {
            return ai.neargo.shop.common.Masks.email(s);
        }
        // 纯数字且够长 = 完整手机号；否则当片段
        return s.length() >= 8 && s.chars().allMatch(Character::isDigit)
                ? ai.neargo.shop.common.Masks.phone(s) : s;
    }

    /** 空 = 不筛。返回 null 而不是抛，调用方按 null 决定加不加条件。 */
    private LocalDateTime startOf(String day) {
        return day == null || day.isBlank()
                ? null : java.time.LocalDate.parse(day.trim()).atStartOfDay();
    }

    private LocalDateTime endOf(String day) {
        return day == null || day.isBlank()
                ? null : java.time.LocalDate.parse(day.trim()).plusDays(1).atStartOfDay();
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
        String scene = sceneOf(c.paramOr("scene", null));
        precheckTestTarget(SysNotifyLog.WXSUB, userNo, scene);
        // thing2 是微信模板里允许自定义的字段（≤20 字）。此前写死在网关里，
        // 改一句话都要发版 —— 现在从这里传，模拟发送与真实发送共用同一条路
        String tip = c.paramOr("thing2", null);
        if (WxSubscribePort.SCENE_REFUNDED.equals(scene)) {
            // 金额随便给一个可辨认的小数：这条是测话术与通道，不是测金额格式化
            wxSender.refunded(userNo, c.paramOr("amount1", "0.01元"), "pages/orders/index", tip);
        } else {
            wxSender.orderArrived(userNo, 1, "pages/orders/index", tip);
        }
    }

    /** 认不出的场景一律当到货 —— 两条模板里它是更常用的那条，且不会误发退款话术。 */
    private String sceneOf(String raw) {
        return WxSubscribePort.SCENE_REFUNDED.equals(raw)
                ? WxSubscribePort.SCENE_REFUNDED : WxSubscribePort.SCENE_ORDER_ARRIVED;
    }

    /** 预检。**与真正发送共用同一份判断** —— 两份判断迟早分叉，而分叉时预检会放行一个发不出去的目标。 */
    @Override
    public void precheckTestTarget(String channel, String target, String scene) {
        if (target == null || target.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (SysNotifyLog.WXSUB.equals(channel)) {
            /*
             * **按选中的那条模板查额度**。写死到货的话，运营选了退款、
             * 预检说「有额度」，然后那一发静默没出去 —— 而额度本来就是逐模板授权的，
             * 用户点「允许」的是哪条就只有哪条有额度。
             */
            String templateId = wxSender.templateIdOf(sceneOf(scene));
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
        precheckTestTarget(SysNotifyLog.PUSH, userNo, null);
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
