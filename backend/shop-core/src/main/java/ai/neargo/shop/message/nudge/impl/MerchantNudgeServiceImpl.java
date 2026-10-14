package ai.neargo.shop.message.nudge.impl;

import ai.neargo.shop.message.MessageService;
import ai.neargo.shop.message.entity.MsgMessage;
import ai.neargo.shop.message.mapper.MessageMappers.MessageMapper;
import ai.neargo.shop.message.nudge.MerchantNudgeService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.user.MerchantStaffPort;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 提醒的实现。
 *
 * <p><b>文案在后端，不在前端。</b>商家收到的那句话是平台以自己的名义说的 ——
 * 放在前端意味着「谁点的按钮、用的哪个版本的文案」由浏览器决定，
 * 而这条消息会留在商家的收件箱里、将来是要被引用的。
 */
@Service
public class MerchantNudgeServiceImpl implements MerchantNudgeService {

    /** 站内信的落点：商家 App 的商品列表。**提醒必须带出路** —— 只说问题等于没说 */
    private static final Map<String, String> LINKS = Map.of(
            Reason.NO_GOODS, "/pages/goods/list",
            Reason.NOT_ON_SALE, "/pages/goods/list",
            Reason.NO_ACCOUNT, "/pages/inventory/index",
            Reason.NO_INBOUND, "/pages/inventory/inbound",
            Reason.STALE_LEDGER, "/pages/inventory/index");

    private static final Map<String, String> TITLES = Map.of(
            Reason.NO_GOODS, "还没有上架的商品",
            Reason.NOT_ON_SALE, "有商品审核通过了，还没上架",
            Reason.NO_ACCOUNT, "商品还没建库存账",
            Reason.NO_INBOUND, "库存账建好了，还没进过货",
            Reason.STALE_LEDGER, "最近一直没有记账");

    private static final Map<String, String> BODIES = Map.of(
            Reason.NO_GOODS, "您的店铺还没有商品。发布第一个商品之后，买家才能在附近看到您。",
            Reason.NOT_ON_SALE, "有商品已经审核通过但还没上架 —— 上架之后买家才买得到。",
            Reason.NO_ACCOUNT, "有商品还没有对应的库存账，进货和盘点都用不了。",
            Reason.NO_INBOUND, "库存账已经建好，还没有第一笔进货记录。记一笔之后就能看到实时库存。",
            Reason.STALE_LEDGER, "最近一段时间没有新的出入库记录。账实不符会影响买家下单。");

    /** 运营备注的长度上限。它会原样进商家的收件箱，不是内部备注 */
    private static final int NOTE_MAX = 200;

    private final MessageService messages;
    private final MessageMapper messageMapper;
    private final MerchantStaffPort staffPort;
    private final MerchantQueryPort merchants;
    private final AuditLogPort auditLog;

    public MerchantNudgeServiceImpl(MessageService messages, MessageMapper messageMapper,
                                    MerchantStaffPort staffPort, MerchantQueryPort merchants,
                                    AuditLogPort auditLog) {
        this.messages = messages;
        this.messageMapper = messageMapper;
        this.staffPort = staffPort;
        this.merchants = merchants;
        this.auditLog = auditLog;
    }

    @Override
    @Transactional
    public NudgeResult nudge(String entityNo, String reason, String note) {
        if (!Reason.ALL.contains(reason)) {
            // 不认的事由一律拒：放行等于给自由文本开了个后门，而文案是平台的口径
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (merchants.find(entityNo).isEmpty()) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        String trimmed = note == null ? null : note.strip();
        if (trimmed != null && trimmed.length() > NOTE_MAX) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        String base = dedupBase(entityNo, reason);
        /*
         * 先查再发。**不能只靠 pushTo 的幂等** —— 它撞键是静默跳过，
         * 于是「发出去了」与「今天已经发过了」在调用方看来一模一样，
         * 而运营看不出区别就会再点一次。
         */
        if (messageMapper.exists(Wrappers.<MsgMessage>lambdaQuery()
                .likeRight(MsgMessage::getDedupKey, base + ":"))) {
            return new NudgeResult(0, true, false);
        }

        List<String> userNos = staffPort.staffUserNos(entityNo, null);
        if (userNos.isEmpty()) {
            // 与「已经提醒过了」是两回事：这家店还没配人，该做的是去配人
            return new NudgeResult(0, false, true);
        }

        String body = trimmed == null || trimmed.isEmpty()
                ? BODIES.get(reason)
                : BODIES.get(reason) + "\n\n" + trimmed;
        for (String userNo : userNos) {
            // dedupKey 必须带收件人：唯一索引是全局的，只用 base 的话
            // 第二个收件人会被当成重投而静默丢掉
            messages.pushTo(MsgMessage.RECEIVER_STAFF, userNo, MessageService.SYSTEM,
                    TITLES.get(reason), body, LINKS.get(reason), base + ":" + userNo);
        }
        auditLog.record("MERCHANT_NUDGE", entityNo,
                reason + (trimmed == null || trimmed.isEmpty() ? "" : "：" + trimmed));
        return new NudgeResult(userNos.size(), false, false);
    }

    /** 商家 × 事由 × 日期。**日期在键里** —— 一天一次是这条能力的安全边界 */
    private static String dedupBase(String entityNo, String reason) {
        return "OPS_NUDGE:" + entityNo + ":" + reason + ":" + LocalDate.now();
    }
}
