package ai.neargo.shop.message.impl;

import ai.neargo.shop.message.MessageService;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.Perms;
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
import ai.neargo.shop.message.entity.SysNotifyLog;
import ai.neargo.shop.message.entity.MsgTicket;
import ai.neargo.shop.message.entity.MsgFaq;
import ai.neargo.shop.message.mapper.MessageMappers.FaqMapper;
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

    private final MessageMapper messageMapper;
    private final TicketMapper ticketMapper;
    private final TemplateMapper templateMapper;
    private final ai.neargo.shop.spi.platform.SettingPort settingPort;
    private final ai.neargo.shop.spi.platform.OpsStaffPort opsStaffPort;
    private final SubscribeMapper subscribeMapper;
    /** 外发模板的发送量从这张表数 —— 它们不写 notify_message */
    private final ai.neargo.shop.message.mapper.MessageMappers.NotifyLogMapper notifyLogMapper;
    private final FaqMapper faqMapper;
    private final ai.neargo.shop.spi.platform.AuditLogPort auditLogPort;

    public MessageServiceImpl(MessageMapper messageMapper, TicketMapper ticketMapper,
                              SubscribeMapper subscribeMapper,
                              TemplateMapper templateMapper,
                              ai.neargo.shop.spi.platform.SettingPort settingPort,
                              ai.neargo.shop.spi.platform.OpsStaffPort opsStaffPort,
                              ai.neargo.shop.message.mapper.MessageMappers.NotifyLogMapper notifyLogMapper,
                              FaqMapper faqMapper,
                              ai.neargo.shop.spi.platform.AuditLogPort auditLogPort) {
        this.templateMapper = templateMapper;
        this.settingPort = settingPort;
        this.opsStaffPort = opsStaffPort;
        this.messageMapper = messageMapper;
        this.ticketMapper = ticketMapper;
        this.subscribeMapper = subscribeMapper;
        this.notifyLogMapper = notifyLogMapper;
        this.faqMapper = faqMapper;
        this.auditLogPort = auditLogPort;
    }

    @Override
    public void push(String userNo, String type, String title, String body,
                     String link, String dedupKey) {
        pushTo(MsgMessage.RECEIVER_USER, userNo, type, title, body, link, dedupKey);
    }

    @Override
    @Transactional
    public void pushTo(String receiverType, String receiverNo, String type,
                       String title, String body, String link, String dedupKey) {
        if (receiverNo == null || receiverNo.isBlank()) {
            // 事件里没带收件人：记日志但不抛 —— 抛了会让整条事件卡在队列里反复重试
            log.warn("skip message without receiver: title={} dedup={}", title, dedupKey);
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
        m.setReceiverType(receiverType);
        m.setReceiverNo(receiverNo);
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
    @Transactional
    public boolean pushMarketing(String userNo, String templateNo, String title, String body,
                                 String link, String dedupKey) {
        if (userNo == null || userNo.isBlank() || templateNo == null || templateNo.isBlank()) {
            log.warn("skip marketing message without userNo/templateNo: dedup={}", dedupKey);
            return false;
        }
        // 停用即刻生效：运营停掉扰民模板的那一刻起，引用它的推送一条都不该再出去
        MsgTemplate tpl = templateMapper.selectOne(Wrappers.<MsgTemplate>lambdaQuery()
                .eq(MsgTemplate::getTemplateNo, templateNo).last("limit 1"));
        if (tpl == null || Boolean.FALSE.equals(tpl.getEnabled())) {
            log.info("[quota] 模板不存在或已停用，营销消息拦下 template={} user={}", templateNo, userNo);
            return false;
        }

        NotifyQuotaVO quota = notifyQuota();
        long now = System.currentTimeMillis();
        long dayStart = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault())
                .toInstant().toEpochMilli();

        // 日上限：只数营销类 —— 交易消息不占用户的「被打扰额度」
        long today = DataScopeContext.executeWithoutScope(() ->
                messageMapper.selectCount(Wrappers.<MsgMessage>lambdaQuery()
                        .eq(MsgMessage::getReceiverType, MsgMessage.RECEIVER_USER)
                        .eq(MsgMessage::getReceiverNo, userNo)
                        .eq(MsgMessage::getMsgType, MsgMessage.MARKETING)
                        .ge(MsgMessage::getAt, dayStart)));
        if (today >= quota.dailyPerUser()) {
            log.info("[quota] 日上限拦下 user={} today={}/{}", userNo, today, quota.dailyPerUser());
            return false;
        }

        // 同模板最小间隔 —— notify_message.templateNo 为此存在（见实体注释）
        boolean recent = DataScopeContext.executeWithoutScope(() ->
                messageMapper.selectCount(Wrappers.<MsgMessage>lambdaQuery()
                        .eq(MsgMessage::getReceiverType, MsgMessage.RECEIVER_USER)
                        .eq(MsgMessage::getReceiverNo, userNo)
                        .eq(MsgMessage::getTemplateNo, templateNo)
                        .ge(MsgMessage::getAt, now - quota.minIntervalHours() * 3600_000L))) > 0;
        if (recent) {
            log.info("[quota] 模板间隔拦下 user={} template={}", userNo, templateNo);
            return false;
        }

        boolean exists = DataScopeContext.executeWithoutScope(() ->
                messageMapper.selectCount(Wrappers.<MsgMessage>lambdaQuery()
                        .eq(MsgMessage::getDedupKey, dedupKey))) > 0;
        if (exists) {
            return false;
        }
        MsgMessage m = new MsgMessage();
        m.setMessageNo(BizKey.next(BizKey.MESSAGE));
        m.setReceiverType(MsgMessage.RECEIVER_USER);
        m.setReceiverNo(userNo);
        m.setMsgType(MsgMessage.MARKETING);
        m.setTemplateNo(templateNo);
        m.setTitle(title);
        m.setBody(body);
        m.setLink(link);
        m.setIsRead(false);
        m.setDedupKey(dedupKey);
        m.setAt(now);
        DataScopeContext.executeWithoutScope(() -> messageMapper.insert(m));
        return true;
    }

    @Override
    public List<MessageVO> list(String receiverType) {
        return rows(receiverType).stream().map(this::toVO).toList();
    }

    @Override
    @Transactional
    public List<MessageVO> markRead(String receiverType, String messageNo) {
        MsgMessage m = messageMapper.selectOne(Wrappers.<MsgMessage>lambdaQuery()
                .eq(MsgMessage::getMessageNo, messageNo)
                // 属主 + 收件箱都进查询条件：messageNo 可猜，且同一个人的
                // C/B 两个收件箱不能互相标已读（会把对方的角标悄悄清掉）
                .eq(MsgMessage::getReceiverType, receiverType)
                .eq(MsgMessage::getReceiverNo, SecurityUtils.currentUserNo())
                .last("limit 1"));
        if (m == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        m.setIsRead(true);
        messageMapper.updateById(m);
        return list(receiverType);
    }

    @Override
    @Transactional
    public List<MessageVO> markAllRead(String receiverType) {
        MsgMessage patch = new MsgMessage();
        patch.setIsRead(true);
        messageMapper.update(patch, Wrappers.<MsgMessage>lambdaUpdate()
                .eq(MsgMessage::getReceiverType, receiverType)
                .eq(MsgMessage::getReceiverNo, SecurityUtils.currentUserNo())
                .eq(MsgMessage::getIsRead, false));
        return list(receiverType);
    }

    @Override
    public long unreadCount(String receiverType) {
        return unreadCountOf(receiverType, SecurityUtils.currentUserNo());
    }

    @Override
    public long unreadCountOf(String receiverType, String receiverNo) {
        return messageMapper.selectCount(Wrappers.<MsgMessage>lambdaQuery()
                .eq(MsgMessage::getReceiverType, receiverType)
                .eq(MsgMessage::getReceiverNo, receiverNo)
                .eq(MsgMessage::getIsRead, false));
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
                if (accepted) {
                    // 一次性订阅：每次「允许」都是新攒一次发送额度，不是覆盖开关
                    existing.setQuota((existing.getQuota() == null ? 0 : existing.getQuota()) + 1);
                }
                existing.setAt(System.currentTimeMillis());
                subscribeMapper.updateById(existing);
                continue;
            }
            MsgSubscribe s = new MsgSubscribe();
            s.setUserNo(userNo);
            s.setTemplateId(templateId);
            s.setAccepted(accepted);
            s.setQuota(accepted ? 1 : 0);
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

        /*
         * P-N-1：新工单进有权处理的客服的收件箱。同域直推，不绕 Outbox ——
         * 工单和通知在同一个事务里，没有跨域一致性问题要解。
         * 按权限码找人而不是按角色名：角色随时会被运营改组（见 OpsStaffPort）。
         */
        for (String staffNo : opsStaffPort.staffNosWithPerm(Perms.MESSAGE_TICKET_HANDLE)) {
            pushTo(MsgMessage.RECEIVER_OPS, staffNo, SYSTEM,
                    "新工单", "「" + subject + "」等待处理",
                    "/messages?tab=tickets",   // ops-web 的工单池就在消息页的 tickets tab
                    "TICKET:" + t.getTicketNo() + ":" + staffNo);
        }
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
        return faqMapper.selectList(Wrappers.<MsgFaq>lambdaQuery()
                        .eq(MsgFaq::getPublished, true)
                        .orderByAsc(MsgFaq::getSort))
                .stream().map(f -> FaqVO.forC(f.getQuestion(), f.getAnswer(), f.getCategory()))
                .toList();
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

    private List<MsgMessage> rows(String receiverType) {
        return messageMapper.selectList(Wrappers.<MsgMessage>lambdaQuery()
                .eq(MsgMessage::getReceiverType, receiverType)
                .eq(MsgMessage::getReceiverNo, SecurityUtils.currentUserNo())
                .orderByDesc(MsgMessage::getAt).orderByDesc(MsgMessage::getId));
    }

    private MessageVO toVO(MsgMessage m) {
        return new MessageVO(m.getMessageNo(), m.getMsgType(), m.getTitle(), m.getBody(),
                m.getLink(), Boolean.TRUE.equals(m.getIsRead()), m.getAt() == null ? 0L : m.getAt());
    }

    // ---------------------------------------------------------------- 运营侧 FAQ（P-14.2.4）

    @Override
    public ai.neargo.shop.common.PageData<FaqVO> opsFaqs(long page, long size) {
        // 运营编辑视图：所有条目，含草稿
        List<FaqVO> all = faqMapper.selectList(Wrappers.<MsgFaq>lambdaQuery()
                        .orderByAsc(MsgFaq::getSort)).stream()
                .map(this::toFaqVO).toList();
        return ai.neargo.shop.common.PageData.ofAll(all, page, size);
    }

    @Override
    @Transactional
    public FaqVO saveFaq(SaveFaqCommand cmd, String operatorNo) {
        if (cmd.faqNo() != null && !cmd.faqNo().isBlank()) {
            // 更新
            MsgFaq f = faqMapper.selectOne(Wrappers.<MsgFaq>lambdaQuery()
                    .eq(MsgFaq::getFaqNo, cmd.faqNo()).last("limit 1"));
            if (f == null) throw BizException.of(ErrorCode.NOT_FOUND);
            f.setQuestion(cmd.question());
            f.setAnswer(cmd.answer());
            f.setCategory(cmd.category() == null ? f.getCategory() : cmd.category());
            f.setSort(cmd.sort() == null ? f.getSort() : cmd.sort());
            faqMapper.updateById(f);
            auditLogPort.record("FAQ_UPDATE", f.getFaqNo(), cmd.question());
            return toFaqVO(f);
        }
        // 新建
        MsgFaq f = new MsgFaq();
        f.setFaqNo(BizKey.next("FAQ"));
        f.setQuestion(cmd.question());
        f.setAnswer(cmd.answer() == null ? "" : cmd.answer());
        f.setCategory(cmd.category() == null ? "" : cmd.category());
        f.setSort(cmd.sort() == null ? 0 : cmd.sort());
        f.setPublished(false); // 草稿状态，显式上架才对 C 端可见
        faqMapper.insert(f);
        auditLogPort.record("FAQ_CREATE", f.getFaqNo(), cmd.question());
        return toFaqVO(f);
    }

    @Override
    @Transactional
    public FaqVO setFaqPublished(String faqNo, boolean published, String operatorNo) {
        MsgFaq f = faqMapper.selectOne(Wrappers.<MsgFaq>lambdaQuery()
                .eq(MsgFaq::getFaqNo, faqNo).last("limit 1"));
        if (f == null) throw BizException.of(ErrorCode.NOT_FOUND);
        if (published && (f.getAnswer() == null || f.getAnswer().isBlank())) {
            // 空答案比没有条目更糟：用户点进去只看到空白
            throw BizException.of(ErrorCode.BAD_REQUEST, "上架前答案不能为空");
        }
        f.setPublished(published);
        faqMapper.updateById(f);
        auditLogPort.record(published ? "FAQ_PUBLISH" : "FAQ_UNPUBLISH", faqNo, f.getQuestion());
        return toFaqVO(f);
    }

    // ---------------------------------------------------------------- 运营侧工单（补齐）

    @Override
    @Transactional
    public TicketVO assignTicket(String ticketNo, String assigneeNo, String operatorNo) {
        MsgTicket t = requireTicket(ticketNo);
        t.setAssignedTo(assigneeNo);
        t.setAssignedAt(System.currentTimeMillis());
        ticketMapper.updateById(t);
        auditLogPort.record("TICKET_ASSIGN", ticketNo, "指派给 " + assigneeNo);
        return toVO(t);
    }

    @Override
    public TicketVO addProxyAction(String ticketNo, String action, String operatorNo) {
        // 只写审计日志：代客操作本身已在对应业务端点完成，这里只留痕「在工单上下文里做的」
        MsgTicket t = requireTicket(ticketNo);
        auditLogPort.record("TICKET_PROXY_ACTION", ticketNo, action);
        return toVO(t);
    }

    private FaqVO toFaqVO(MsgFaq f) {
        return new FaqVO(f.getFaqNo(), f.getQuestion(), f.getAnswer(), f.getCategory(),
                f.getSort(), f.getPublished());
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
                        // 同一模板号的多语言必须相邻：按 id 排的话，
                        // 后补的英文译文会掉到列表最后，与它的中文原文隔着十几行
                        .orderByAsc(MsgTemplate::getTemplateNo)
                        .orderByAsc(MsgTemplate::getLang)).stream()
                .map(t -> new TemplateVO(t.getTemplateNo(), t.getName(), t.getChannel(),
                        // 同一个模板号现在会有多行（每种语言一行，V145）——
                        // 不下发 lang 的话，运营在列表上看到两条一模一样的
                        t.getLang(), t.getContent(), t.getProviderTemplateId(),
                        !Boolean.FALSE.equals(t.getEnabled()),
                        sentCountOf(t, since)))
                .toList();
    }

    @Override
    public ai.neargo.shop.common.PageData<ai.neargo.shop.message.dto.MessageVOs.InAppLogVO>
            opsInAppMessages(String receiverType, String receiverNo,
                             String from, String to, long page, long size) {
        /*
         * 与发送记录同一套口径（见 NotifyLogServiceImpl.list）：
         * 「到 X 日」含当天，所以是次日零点的开区间；条件值先算好再传 ——
         * MyBatis-Plus 的 ge(condition, col, value) 的 value 是提前求值的。
         */
        Long fromAt = dayStart(from);
        Long toAt = dayEndExclusive(to);
        var q = Wrappers.<MsgMessage>lambdaQuery()
                .eq(receiverType != null && !receiverType.isBlank(),
                        MsgMessage::getReceiverType, receiverType)
                .eq(receiverNo != null && !receiverNo.isBlank(),
                        MsgMessage::getReceiverNo, receiverNo)
                .ge(fromAt != null, MsgMessage::getAt, fromAt)
                .lt(toAt != null, MsgMessage::getAt, toAt)
                .orderByDesc(MsgMessage::getId);
        // 平台侧运维视图，没有数据域概念；走库分页，这张表会一直涨
        return DataScopeContext.executeWithoutScope(() ->
                ai.neargo.shop.common.MybatisPages.of(
                        messageMapper.selectPage(
                                com.baomidou.mybatisplus.extension.plugins.pagination.Page
                                        .of(page, size), q)
                                .convert(m -> new ai.neargo.shop.message.dto.MessageVOs.InAppLogVO(
                                        m.getMessageNo(), m.getReceiverType(), m.getReceiverNo(),
                                        m.getMsgType(), m.getTitle(), m.getTemplateNo(),
                                        Boolean.TRUE.equals(m.getIsRead()), m.getAt()))));
    }

    /** notify_message.at 存的是毫秒时间戳，不是 DATETIME —— 与 sys_notify_log 不同，别照抄 */
    private Long dayStart(String day) {
        return day == null || day.isBlank() ? null
                : java.time.LocalDate.parse(day.trim()).atStartOfDay(
                        java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private Long dayEndExclusive(String day) {
        return day == null || day.isBlank() ? null
                : java.time.LocalDate.parse(day.trim()).plusDays(1).atStartOfDay(
                        java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /**
     * 近 30 天发送量。**按通道取不同的真源** —— 这是这个数唯一能对的方式：
     *
     * <ul>
     *   <li>站内信：它就是 {@code notify_message} 那张表</li>
     *   <li>其余四条外发通道：它们**不写** {@code notify_message}，只写 {@code sys_notify_log}</li>
     * </ul>
     *
     * <p>此前一律按 {@code notify_message} 数，于是外发模板<b>永远显示 0</b> ——
     * 而 {@code TPL_SMS_OTP} 真发了十几次。运营拿这一列判断「哪条模板可以下线」，
     * 一个恒为 0 的数会让他把还在用的模板停掉。
     */
    private long sentCountOf(MsgTemplate t, long since) {
        if (MsgTemplate.CHANNEL_INAPP.equals(t.getChannel())) {
            return messageMapper.selectCount(Wrappers.<MsgMessage>lambdaQuery()
                    .eq(MsgMessage::getTemplateNo, t.getTemplateNo())
                    .ge(MsgMessage::getAt, since));
        }
        java.time.LocalDateTime from = java.time.Instant.ofEpochMilli(since)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
        return DataScopeContext.executeWithoutScope(() ->
                notifyLogMapper.selectCount(Wrappers.<SysNotifyLog>lambdaQuery()
                        .eq(SysNotifyLog::getTemplateNo, t.getTemplateNo())
                        .ge(SysNotifyLog::getCreatedAt, from)));
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
