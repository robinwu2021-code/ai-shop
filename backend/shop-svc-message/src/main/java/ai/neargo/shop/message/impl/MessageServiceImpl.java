package ai.neargo.shop.message.impl;

import ai.neargo.shop.message.MessageService;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.message.dto.MessageVOs.FaqVO;
import ai.neargo.shop.message.dto.MessageVOs.MessageVO;
import ai.neargo.shop.message.dto.MessageVOs.TicketVO;
import ai.neargo.shop.message.entity.MsgMessage;
import ai.neargo.shop.message.entity.MsgSubscribe;
import ai.neargo.shop.message.entity.MsgTicket;
import ai.neargo.shop.message.mapper.MessageMappers.MessageMapper;
import ai.neargo.shop.message.mapper.MessageMappers.SubscribeMapper;
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
    private final SubscribeMapper subscribeMapper;

    public MessageServiceImpl(MessageMapper messageMapper, TicketMapper ticketMapper,
                              SubscribeMapper subscribeMapper) {
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
}
