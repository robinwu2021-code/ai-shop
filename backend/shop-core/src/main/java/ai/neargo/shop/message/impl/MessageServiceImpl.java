package ai.neargo.shop.message.impl;

import ai.neargo.shop.message.MessageService;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.message.dto.MessageVOs.FaqVO;
import ai.neargo.shop.message.dto.MessageVOs.MessageVO;
import ai.neargo.shop.message.dto.MessageVOs.NotifyQuotaVO;
import ai.neargo.shop.message.dto.MessageVOs.TemplateVO;
import ai.neargo.shop.message.dto.MessageVOs.TicketVO;
import ai.neargo.shop.message.entity.MsgMessage;
import ai.neargo.shop.message.entity.MsgSubscribe;
import ai.neargo.shop.message.entity.MsgTemplate;
import ai.neargo.shop.message.entity.MsgTicket;
import ai.neargo.shop.message.mapper.MessageMappers.MessageMapper;
import ai.neargo.shop.message.mapper.MessageMappers.SubscribeMapper;
import ai.neargo.shop.message.mapper.MessageMappers.TemplateMapper;
import ai.neargo.shop.message.mapper.MessageMappers.TicketMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class MessageServiceImpl implements MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageServiceImpl.class);

    /** 一期硬编码；M9 接运营配置后从 sys_config 读（接口不变）。 */
    private static final List<FaqVO> FAQ = List.of(
            new FaqVO("怎么取货？", "订单支付后会生成取货码，到自提点报码或出示二维码即可。", "履约"),
            new FaqVO("能退款吗？", "未取货前可申请仅退款；小额订单支持极速退，立即到账。", "售后"),
            new FaqVO("为什么我的券用不了？", "券有使用门槛与有效期，结算页会显示不可用原因。", "优惠"),
            new FaqVO("到货时间怎么算？", "自提点页面会写明当日到货时间，一般为每晚 7 点前。", "履约"));

    private final MessageMapper messageMapper;
    private final TicketMapper ticketMapper;
    private final TemplateMapper templateMapper;
    private final ai.neargo.shop.spi.platform.SettingPort settingPort;
    private final SubscribeMapper subscribeMapper;

    public MessageServiceImpl(MessageMapper messageMapper, TicketMapper ticketMapper,
                              SubscribeMapper subscribeMapper,
                              TemplateMapper templateMapper,
                              ai.neargo.shop.spi.platform.SettingPort settingPort) {
        this.templateMapper = templateMapper;
        this.settingPort = settingPort;
        this.messageMapper = messageMapper;
        this.ticketMapper = ticketMapper;
        this.subscribeMapper = subscribeMapper;
    }

    @Override
    @Transactional
    public void push(String userNo, String type, String title, String body,
                     String link, String dedupKey) {
        if (userNo == null || userNo.isBlank()) {
            // 事件里没带用户号：记日志但不抛 —— 抛了会让整条事件卡在队列里反复重试
            log.warn("skip message without userNo: title={} dedup={}", title, dedupKey);
            return;
        }
        boolean exists = DataScopeContext.executeWithoutScope(() ->
                messageMapper.selectCount(Wrappers.<MsgMessage>lambdaQuery()
                        .eq(MsgMessage::getDedupKey, dedupKey))) > 0;
        if (exists) {
            return;   // 事件重投是正常现象，静默跳过而不是报错
        }

        MsgMessage m = new MsgMessage();
        m.setMessageNo(BizKey.next(BizKey.MESSAGE));
        m.setUserNo(userNo);
        m.setMsgType(type);
        m.setTitle(title);
        m.setBody(body);
        m.setLink(link);
        m.setIsRead(false);
        m.setDedupKey(dedupKey);
        m.setAt(System.currentTimeMillis());
        DataScopeContext.executeWithoutScope(() -> messageMapper.insert(m));
    }

    @Override
    public List<MessageVO> list() {
        return rows().stream().map(this::toVO).toList();
    }

    @Override
    @Transactional
    public List<MessageVO> markRead(String messageNo) {
        MsgMessage m = messageMapper.selectOne(Wrappers.<MsgMessage>lambdaQuery()
                .eq(MsgMessage::getMessageNo, messageNo)
                .eq(MsgMessage::getUserNo, SecurityUtils.currentUserNo())
                .last("limit 1"));
        if (m == null) {
            // 属主写进查询条件：messageNo 可猜
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        m.setIsRead(true);
        messageMapper.updateById(m);
        return list();
    }

    @Override
    @Transactional
    public List<MessageVO> markAllRead() {
        MsgMessage patch = new MsgMessage();
        patch.setIsRead(true);
        messageMapper.update(patch, Wrappers.<MsgMessage>lambdaUpdate()
                .eq(MsgMessage::getUserNo, SecurityUtils.currentUserNo())
                .eq(MsgMessage::getIsRead, false));
        return list();
    }

    @Override
    @Transactional
    public void subscribe(List<String> templateIds, boolean accepted) {
        String userNo = SecurityUtils.currentUserNo();
        for (String templateId : templateIds == null ? List.<String>of() : templateIds) {
            MsgSubscribe existing = subscribeMapper.selectOne(Wrappers.<MsgSubscribe>lambdaQuery()
                    .eq(MsgSubscribe::getUserNo, userNo)
                    .eq(MsgSubscribe::getTemplateId, templateId).last("limit 1"));
            if (existing != null) {
                existing.setAccepted(accepted);
                existing.setAt(System.currentTimeMillis());
                subscribeMapper.updateById(existing);
                continue;
            }
            MsgSubscribe s = new MsgSubscribe();
            s.setUserNo(userNo);
            s.setTemplateId(templateId);
            s.setAccepted(accepted);
            s.setAt(System.currentTimeMillis());
            subscribeMapper.insert(s);
        }
    }

    @Override
    @Transactional
    public TicketVO createTicket(String subject, String content, String orderNo) {
        MsgTicket t = new MsgTicket();
        t.setTicketNo(BizKey.next(BizKey.TICKET));
        t.setUserNo(SecurityUtils.currentUserNo());
        t.setSubject(subject);
        t.setContent(content);
        t.setOrderNo(orderNo);
        t.setStatus(MsgTicket.OPEN);
        ticketMapper.insert(t);
        return toVO(t);
    }

    @Override
    public List<TicketVO> myTickets() {
        return ticketMapper.selectList(Wrappers.<MsgTicket>lambdaQuery()
                        .eq(MsgTicket::getUserNo, SecurityUtils.currentUserNo())
                        .orderByDesc(MsgTicket::getId)).stream()
                .map(this::toVO).toList();
    }

    @Override
    public TicketVO ticket(String ticketNo) {
        MsgTicket t = ticketMapper.selectOne(Wrappers.<MsgTicket>lambdaQuery()
                .eq(MsgTicket::getTicketNo, ticketNo)
                .eq(MsgTicket::getUserNo, SecurityUtils.currentUserNo())
                .last("limit 1"));
        if (t == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return toVO(t);
    }

    @Override
    public List<FaqVO> faq() {
        return FAQ;
    }

    // ---------------------------------------------------------------- 平台侧（P-14.2）

    @Override
    public List<TicketVO> opsTickets(String status) {
        /*
         * 不按 userNo 过滤 —— 这是平台视角，要看到所有人的单。
         * C 端的 myTickets() 才带 userNo 条件，两者的区别就在这一行。
         */
        return ticketMapper.selectList(Wrappers.<MsgTicket>lambdaQuery()
                        .eq(status != null && !status.isBlank(), MsgTicket::getStatus, status)
                        // 未处理的排前面，同状态内新单在前：客服要先看没人管过的
                        .orderByAsc(MsgTicket::getStatus)
                        .orderByDesc(MsgTicket::getId)).stream()
                .map(this::toVO).toList();
    }

    @Override
    @Transactional
    public TicketVO replyTicket(String ticketNo, String reply, String operatorNo) {
        if (reply == null || reply.isBlank()) {
            // 空回复会让工单变成 REPLIED 而用户什么也没收到 —— 比不回更糟，
            // 因为它把单子从待处理队列里移走了
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        MsgTicket t = requireTicket(ticketNo);
        if (MsgTicket.CLOSED.equals(t.getStatus())) {
            // 用 CONFLICT 而不是 ORDER_STATE_ILLEGAL：后者的名字里带 ORDER，
            // 出现在工单的错误链路上会让排查的人先去翻订单状态机
            throw BizException.of(ErrorCode.CONFLICT);
        }
        t.setReply(reply);
        t.setRepliedAt(System.currentTimeMillis());
        t.setRepliedBy(operatorNo);
        t.setStatus(MsgTicket.REPLIED);
        ticketMapper.updateById(t);
        return toVO(t);
    }

    @Override
    @Transactional
    public TicketVO closeTicket(String ticketNo, String operatorNo) {
        MsgTicket t = requireTicket(ticketNo);
        if (MsgTicket.CLOSED.equals(t.getStatus())) {
            return toVO(t);   // 幂等：重复关闭不报错
        }
        t.setStatus(MsgTicket.CLOSED);
        // 关单也记处理人：没回复直接关的单，事后要能查是谁关的
        t.setRepliedBy(operatorNo);
        ticketMapper.updateById(t);
        return toVO(t);
    }

    /** 平台侧按单号取单，**不带 userNo 条件** —— 客服要处理的是别人的单 */
    private MsgTicket requireTicket(String ticketNo) {
        MsgTicket t = ticketMapper.selectOne(Wrappers.<MsgTicket>lambdaQuery()
                .eq(MsgTicket::getTicketNo, ticketNo).last("limit 1"));
        if (t == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return t;
    }

    private List<MsgMessage> rows() {
        return messageMapper.selectList(Wrappers.<MsgMessage>lambdaQuery()
                .eq(MsgMessage::getUserNo, SecurityUtils.currentUserNo())
                .orderByDesc(MsgMessage::getAt).orderByDesc(MsgMessage::getId));
    }

    private MessageVO toVO(MsgMessage m) {
        return new MessageVO(m.getMessageNo(), m.getMsgType(), m.getTitle(), m.getBody(),
                m.getLink(), Boolean.TRUE.equals(m.getIsRead()), m.getAt() == null ? 0L : m.getAt());
    }

    private TicketVO toVO(MsgTicket t) {
        return new TicketVO(t.getTicketNo(), t.getSubject(), t.getContent(), t.getOrderNo(),
                t.getStatus(), t.getReply(),
                t.getCreatedAt() == null ? 0L
                        : t.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                t.getRepliedAt());
    }
    // ---------------------------------------------------------------- 平台侧 · 触达（P-14.1）

    /** 频控参数键与默认值。默认**保守**：没配过不等于不限 */
    private static final String QUOTA_KEY = "notify.quota";
    private static final String QUOTA_DEFAULT = "{\"dailyPerUser\":5,\"minIntervalHours\":24}";

    @Override
    public List<TemplateVO> opsTemplates() {
        long since = System.currentTimeMillis() - 30L * 24 * 3600 * 1000;
        return templateMapper.selectList(Wrappers.<MsgTemplate>lambdaQuery()
                        .orderByDesc(MsgTemplate::getId)).stream()
                .map(t -> new TemplateVO(t.getTemplateNo(), t.getName(), t.getChannel(),
                        t.getContent(), t.getProviderTemplateId(),
                        !Boolean.FALSE.equals(t.getEnabled()),
                        // 近 30 天发送量按 msg_message 真算，不写死 —— 编一个数比留空更糟
                        messageMapper.selectCount(Wrappers.<MsgMessage>lambdaQuery()
                                .eq(MsgMessage::getTemplateNo, t.getTemplateNo())
                                .ge(MsgMessage::getAt, since))))
                .toList();
    }

    @Override
    @Transactional
    public TemplateVO setTemplateEnabled(String templateNo, boolean enabled, String operatorNo) {
        MsgTemplate t = templateMapper.selectOne(Wrappers.<MsgTemplate>lambdaQuery()
                .eq(MsgTemplate::getTemplateNo, templateNo).last("limit 1"));
        if (t == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        t.setEnabled(enabled);
        templateMapper.updateById(t);
        return opsTemplates().stream()
                .filter(v -> v.templateNo().equals(templateNo)).findFirst()
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
    }

    @Override
    public NotifyQuotaVO notifyQuota() {
        return parseQuota(settingPort.get(QUOTA_KEY, QUOTA_DEFAULT));
    }

    @Override
    public NotifyQuotaVO saveNotifyQuota(int dailyPerUser, int minIntervalHours, String operatorNo) {
        if (dailyPerUser <= 0 || minIntervalHours <= 0) {
            // 0 等于没有频控，但界面上看着像配了 —— 比不配更危险
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        settingPort.put(QUOTA_KEY,
                "{\"dailyPerUser\":%d,\"minIntervalHours\":%d}".formatted(dailyPerUser, minIntervalHours),
                operatorNo);
        return new NotifyQuotaVO(dailyPerUser, minIntervalHours);
    }

    /** 只有两个整数，手解比引 JSON 依赖轻；解析失败回落默认值而不是抛异常 —— 频控读不出来不该让页面打不开 */
    private NotifyQuotaVO parseQuota(String jsonText) {
        return new NotifyQuotaVO(intField(jsonText, "dailyPerUser", 5),
                intField(jsonText, "minIntervalHours", 24));
    }

    private static int intField(String jsonText, String key, int fallback) {
        var m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)").matcher(jsonText);
        return m.find() ? Integer.parseInt(m.group(1)) : fallback;
    }

}
