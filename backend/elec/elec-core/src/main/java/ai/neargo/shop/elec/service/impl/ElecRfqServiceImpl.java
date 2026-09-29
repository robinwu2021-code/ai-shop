package ai.neargo.shop.elec.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.Masks;
import ai.neargo.shop.elec.config.ConditionalOnElec;
import ai.neargo.shop.elec.config.ElecProperties;
import ai.neargo.shop.elec.dto.RfqDtos.CloseReq;
import ai.neargo.shop.elec.dto.RfqDtos.LineQuote;
import ai.neargo.shop.elec.dto.RfqDtos.LineReq;
import ai.neargo.shop.elec.dto.RfqDtos.LineView;
import ai.neargo.shop.elec.dto.RfqDtos.OpsLineView;
import ai.neargo.shop.elec.dto.RfqDtos.OpsRfqView;
import ai.neargo.shop.elec.dto.RfqDtos.OpsSource;
import ai.neargo.shop.elec.dto.RfqDtos.QuoteLineReq;
import ai.neargo.shop.elec.dto.RfqDtos.QuoteReq;
import ai.neargo.shop.elec.dto.RfqDtos.RfqReq;
import ai.neargo.shop.elec.dto.RfqDtos.RfqView;
import ai.neargo.shop.elec.entity.ElcPart;
import ai.neargo.shop.elec.entity.ElcRfq;
import ai.neargo.shop.elec.entity.ElcRfqLine;
import ai.neargo.shop.elec.gateway.ElecAccounts;
import ai.neargo.shop.elec.gateway.ElecAlerts;
import ai.neargo.shop.elec.gateway.ElecBuyerNotifier;
import ai.neargo.shop.elec.mapper.ElecMappers.PartMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.RfqLineMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.RfqMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.SourceRow;
import ai.neargo.shop.elec.mapper.ElecMappers.StockMapper;
import ai.neargo.shop.elec.service.ElecRfqService;
import ai.neargo.shop.elec.support.ElecKeys;
import ai.neargo.shop.elec.support.Mpn;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 询价。第一步<b>以平台为准</b>：买家提交 → 企业微信群收到（连同库里谁有货）→ 运营录入报价
 * → 微信通知买家 → 买家接受 → 企业微信群收到「接受了」→ 平台线下成交。
 *
 * <p>买家这一侧看得到的报价是<b>平台的</b>，看不到任何供应商；供应商这一侧第一步看不到询价。
 *
 * <p><b>所有通知都在事务提交之后</b>：通知失败不能把询价或报价回滚掉，通知也不能先于数据落库
 * （群里看到了、库里却没有）。送没送到落在 notified_at / buyer_notified_at，空的就是没送到。
 */
@ConditionalOnElec
@Service
public class ElecRfqServiceImpl implements ElecRfqService {

    private static final Logger log = LoggerFactory.getLogger(ElecRfqServiceImpl.class);

    public static final Set<String> INVOICES = Set.of("NONE", "VAT_NORMAL", "VAT_SPECIAL");
    public static final Set<String> DC_REQS = Set.of("ANY", "Y1", "Y2");
    public static final Set<String> CLOSE_REASONS = Set.of("NO_SOURCE", "BUYER_CANCELLED", "DONE");

    /** 单行数量上限。超了多半是多敲了几个 0 */
    private static final long MAX_QTY = 1_000_000_000L;

    /** 每行最多查几家有货的（群消息里只列 3 家，多查几家是为了「另有 N 家」） */
    private static final int SOURCES_PER_LINE = 10;

    /** 报价默认有效天数、最长天数。行情会变，给太长平台兑现不了 */
    private static final int QUOTE_DEFAULT_DAYS = 3;
    private static final int QUOTE_MAX_DAYS = 30;

    private final RfqMapper rfqMapper;
    private final RfqLineMapper lineMapper;
    private final PartMapper partMapper;
    private final StockMapper stockMapper;
    private final ElecAccounts accounts;
    private final ElecAlerts alerts;
    private final ElecBuyerNotifier buyers;
    private final ElecProperties props;
    private final TransactionTemplate tx;

    public ElecRfqServiceImpl(RfqMapper rfqMapper, RfqLineMapper lineMapper, PartMapper partMapper,
                              StockMapper stockMapper, ElecAccounts accounts, ElecAlerts alerts,
                              ElecBuyerNotifier buyers, ElecProperties props,
                              @Qualifier("elecTransactionManager") PlatformTransactionManager tm) {
        this.rfqMapper = rfqMapper;
        this.lineMapper = lineMapper;
        this.partMapper = partMapper;
        this.stockMapper = stockMapper;
        this.accounts = accounts;
        this.alerts = alerts;
        this.buyers = buyers;
        this.props = props;
        this.tx = new TransactionTemplate(tm);
    }

    // ── 买家 ────────────────────────────────────────────────────────────────

    @Override
    public RfqView submit(String userNo, RfqReq req) {
        // 询价前必须有手机号：平台要打电话跟进，而静默登录建出来的号没有手机号
        String phone = accounts.phone(userNo).orElseThrow(() -> BizException.of(ErrorCode.ELEC_PHONE_REQUIRED));
        List<LineReq> lines = req.lines() == null ? List.of() : req.lines();
        if (lines.isEmpty() || lines.size() > props.getRfqMaxLines()) {
            throw BizException.of(ErrorCode.ELEC_RFQ_LINES_INVALID, props.getRfqMaxLines());
        }
        for (LineReq l : lines) {
            if (Mpn.norm(l.mpn()).isEmpty() || l.qty() == null || l.qty() <= 0 || l.qty() > MAX_QTY
                    || (l.targetE6() != null && l.targetE6() < 0)) {
                throw BizException.of(ErrorCode.ELEC_RFQ_LINES_INVALID, props.getRfqMaxLines());
            }
        }

        ElcRfq rfq = new ElcRfq();
        rfq.setRfqNo(ElecKeys.next(ElecKeys.RFQ));
        rfq.setBuyerRef(userNo);
        rfq.setContactPhone(phone);
        rfq.setContactName(ElecSupplierServiceImpl.trimmed(req.contactName(), 32));
        rfq.setCompany(ElecSupplierServiceImpl.trimmed(req.company(), 128));
        rfq.setNeedInvoice(ElecSupplierServiceImpl.oneOf(req.needInvoice(), INVOICES, "NONE"));
        rfq.setDcReq(ElecSupplierServiceImpl.oneOf(req.dcReq(), DC_REQS, "ANY"));
        rfq.setDeliverCity(ElecSupplierServiceImpl.trimmed(req.deliverCity(), 32));
        rfq.setRemark(ElecSupplierServiceImpl.trimmed(req.remark(), 255));
        rfq.setLineCnt(lines.size());
        rfq.setStatus(ElcRfq.STATUS_SUBMITTED);
        rfq.setCreatedBy(userNo);
        rfq.setUpdatedBy(userNo);

        List<ElcRfqLine> rows = new ArrayList<>();
        int no = 0;
        for (LineReq l : lines) {
            ElcRfqLine r = new ElcRfqLine();
            r.setRfqNo(rfq.getRfqNo());
            r.setLineNo(++no);
            r.setMpnRaw(ElecPartCatalog.truncate(l.mpn().trim(), 64));
            r.setMfrRaw(ElecSupplierServiceImpl.trimmed(l.mfr(), 64));
            r.setQty(l.qty());
            r.setTargetE6(l.targetE6());
            r.setPartNo(partOf(l));
            r.setCreatedBy(userNo);
            r.setUpdatedBy(userNo);
            rows.add(r);
        }
        tx.executeWithoutResult(st -> {
            rfqMapper.insert(rfq);
            rows.forEach(lineMapper::insert);
        });

        if (alerts.newRfq(alertOf(rfq, rows))) {
            ElcRfq patch = new ElcRfq();
            patch.setId(rfq.getId());
            patch.setNotifiedAt(LocalDateTime.now());
            rfqMapper.updateById(patch);
        } else {
            log.warn("元器件询价通知没送到企业微信 rfqNo={}（elc_rfq.notified_at 为空的就是这些）", rfq.getRfqNo());
        }
        return view(rfq, rows);
    }

    @Override
    public List<RfqView> mine(String userNo, int page, int size) {
        List<ElcRfq> heads = rfqMapper.selectList(page(Wrappers.<ElcRfq>lambdaQuery()
                .eq(ElcRfq::getBuyerRef, userNo).orderByDesc(ElcRfq::getId), page, size, 50));
        Map<String, List<ElcRfqLine>> lines = linesOf(heads);
        return heads.stream().map(h -> view(h, lines.getOrDefault(h.getRfqNo(), List.of()))).toList();
    }

    @Override
    public RfqView detail(String userNo, String rfqNo) {
        ElcRfq h = mineOr404(userNo, rfqNo);
        return view(h, linesOf(rfqNo));
    }

    @Override
    public RfqView accept(String userNo, String rfqNo) {
        ElcRfq h = mineOr404(userNo, rfqNo);
        if (!ElcRfq.STATUS_QUOTED.equals(h.getStatus())) {
            throw BizException.of(ErrorCode.ELEC_RFQ_STATE);
        }
        if (expired(h)) {
            throw BizException.of(ErrorCode.ELEC_QUOTE_EXPIRED);
        }
        LocalDateTime now = LocalDateTime.now();
        // 条件更新：两次连点、或运营同时在改价，只有一次能把 QUOTED 变成 ACCEPTED
        ElcRfq patch = new ElcRfq();
        patch.setStatus(ElcRfq.STATUS_ACCEPTED);
        patch.setAcceptedAt(now);
        patch.setUpdatedBy(userNo);
        int n = rfqMapper.update(patch, Wrappers.<ElcRfq>lambdaUpdate()
                .eq(ElcRfq::getId, h.getId()).eq(ElcRfq::getStatus, ElcRfq.STATUS_QUOTED));
        if (n == 0) {
            throw BizException.of(ErrorCode.ELEC_RFQ_STATE);
        }
        h.setStatus(ElcRfq.STATUS_ACCEPTED);
        h.setAcceptedAt(now);
        List<ElcRfqLine> lines = linesOf(rfqNo);
        if (!alerts.rfqAccepted(rfqNo, h.getContactName(), h.getContactPhone(), summary(lines))) {
            log.warn("「买家接受报价」没送到企业微信 rfqNo={}", rfqNo);
        }
        return view(h, lines);
    }

    // ── 运营端 ──────────────────────────────────────────────────────────────

    @Override
    public List<OpsRfqView> opsList(String status, int page, int size) {
        LambdaQueryWrapper<ElcRfq> q = Wrappers.<ElcRfq>lambdaQuery().orderByDesc(ElcRfq::getId);
        if (status != null && !status.isBlank()) {
            q.eq(ElcRfq::getStatus, status);
        }
        List<ElcRfq> heads = rfqMapper.selectList(page(q, page, size, 100));
        Map<String, List<ElcRfqLine>> lines = linesOf(heads);
        return heads.stream().map(h -> opsView(h, lines.getOrDefault(h.getRfqNo(), List.of()), false)).toList();
    }

    @Override
    public OpsRfqView opsDetail(String rfqNo) {
        return opsView(or404(rfqNo), linesOf(rfqNo), true);
    }

    @Override
    public OpsRfqView quote(String staffNo, String rfqNo, QuoteReq req) {
        ElcRfq h = or404(rfqNo);
        if (!ElcRfq.STATUS_SUBMITTED.equals(h.getStatus()) && !ElcRfq.STATUS_QUOTED.equals(h.getStatus())) {
            throw BizException.of(ErrorCode.ELEC_RFQ_STATE);
        }
        List<ElcRfqLine> lines = linesOf(rfqNo);
        Map<Integer, ElcRfqLine> byNo = new HashMap<>();
        lines.forEach(l -> byNo.put(l.getLineNo(), l));
        List<QuoteLineReq> quoted = req.lines() == null ? List.of() : req.lines();
        for (QuoteLineReq q : quoted) {
            if (q.lineNo() == null || !byNo.containsKey(q.lineNo()) || q.priceE6() == null || q.priceE6() <= 0
                    || (q.qty() != null && q.qty() <= 0)
                    || (q.leadDays() != null && (q.leadDays() < 0 || q.leadDays() > 365))) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
        }
        if (quoted.isEmpty()) {
            // 一行都没报：那不是报价，是「暂无货源」—— 走关单，买家收到的话术不一样
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        int days = req.validDays() == null ? QUOTE_DEFAULT_DAYS : Math.max(1, Math.min(QUOTE_MAX_DAYS, req.validDays()));
        LocalDateTime now = LocalDateTime.now();
        Map<Integer, QuoteLineReq> reqByNo = new HashMap<>();
        quoted.forEach(q -> reqByNo.put(q.lineNo(), q));

        tx.executeWithoutResult(st -> {
            for (ElcRfqLine l : lines) {
                QuoteLineReq q = reqByNo.get(l.getLineNo());
                // 改价时没再列出的行要清掉旧报价 —— updateById 跳过 null，所以显式按列更新
                lineMapper.update(null, Wrappers.<ElcRfqLine>lambdaUpdate()
                        .eq(ElcRfqLine::getId, l.getId())
                        .set(ElcRfqLine::getQuoteE6, q == null ? null : q.priceE6())
                        .set(ElcRfqLine::getQuoteQty, q == null ? null : q.qty())
                        .set(ElcRfqLine::getQuoteDcYear, q == null ? null : q.dcYear())
                        .set(ElcRfqLine::getQuoteLeadDays, q == null ? null : q.leadDays())
                        .set(ElcRfqLine::getQuoteNote, q == null ? null
                                : ElecSupplierServiceImpl.trimmed(q.note(), 128))
                        .set(ElcRfqLine::getUpdatedBy, staffNo));
            }
            int n = rfqMapper.update(null, Wrappers.<ElcRfq>lambdaUpdate()
                    .eq(ElcRfq::getId, h.getId())
                    .in(ElcRfq::getStatus, ElcRfq.STATUS_SUBMITTED, ElcRfq.STATUS_QUOTED)
                    .set(ElcRfq::getStatus, ElcRfq.STATUS_QUOTED)
                    .set(ElcRfq::getQuotedAt, now)
                    .set(ElcRfq::getQuotedBy, staffNo)
                    .set(ElcRfq::getQuoteValidUntil, now.toLocalDate().plusDays(days - 1L))
                    .set(ElcRfq::getQuoteNote, ElecSupplierServiceImpl.trimmed(req.note(), 255))
                    .set(ElcRfq::getBuyerNotifiedAt, null)
                    .set(ElcRfq::getUpdatedBy, staffNo));
            if (n == 0) {
                // 买家恰好在这一刻接受了旧报价：改价作废，不能改到一张已接受的单上
                throw BizException.of(ErrorCode.ELEC_RFQ_STATE);
            }
        });
        notifyBuyer(rfqNo, h.getBuyerRef(), "QUOTED", linesOf(rfqNo));
        return opsDetail(rfqNo);
    }

    @Override
    public OpsRfqView close(String staffNo, String rfqNo, CloseReq req) {
        ElcRfq h = or404(rfqNo);
        if (ElcRfq.STATUS_CLOSED.equals(h.getStatus())) {
            throw BizException.of(ErrorCode.ELEC_RFQ_STATE);
        }
        String reason = ElecSupplierServiceImpl.oneOf(req == null ? null : req.reason(), CLOSE_REASONS, null);
        if (reason == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        String note = req.note() == null ? h.getQuoteNote() : ElecSupplierServiceImpl.trimmed(req.note(), 255);
        int n = rfqMapper.update(null, Wrappers.<ElcRfq>lambdaUpdate()
                .eq(ElcRfq::getId, h.getId()).ne(ElcRfq::getStatus, ElcRfq.STATUS_CLOSED)
                .set(ElcRfq::getStatus, ElcRfq.STATUS_CLOSED)
                .set(ElcRfq::getClosedAt, LocalDateTime.now())
                .set(ElcRfq::getCloseReason, reason)
                .set(ElcRfq::getQuoteNote, note)
                .set(ElcRfq::getUpdatedBy, staffNo));
        if (n == 0) {
            throw BizException.of(ErrorCode.ELEC_RFQ_STATE);
        }
        // 「暂无货源」也是结果：买家在等，不告诉他就只能一直等下去
        if ("NO_SOURCE".equals(reason)) {
            notifyBuyer(rfqNo, h.getBuyerRef(), "NO_SOURCE", linesOf(rfqNo));
        }
        return opsDetail(rfqNo);
    }

    private void notifyBuyer(String rfqNo, String buyer, String result, List<ElcRfqLine> lines) {
        boolean ok;
        try {
            ok = buyers.rfqResult(buyer, rfqNo, result, summary(lines));
        } catch (RuntimeException e) {
            log.warn("询价结果通知买家失败 rfqNo={} {}", rfqNo, e.toString());
            ok = false;
        }
        if (ok) {
            rfqMapper.update(null, Wrappers.<ElcRfq>lambdaUpdate().eq(ElcRfq::getRfqNo, rfqNo)
                    .set(ElcRfq::getBuyerNotifiedAt, LocalDateTime.now()));
        } else {
            log.warn("询价结果没送到买家 rfqNo={}（elc_rfq.buyer_notified_at 为空的就是这些）", rfqNo);
        }
    }

    // ── 视图 ────────────────────────────────────────────────────────────────

    /** 料号概述：「STM32F103C8T6 等 3 项」。微信模板的 thing 字段 ≤20 字，截断在这里做 */
    static String summary(List<ElcRfqLine> lines) {
        if (lines.isEmpty()) {
            return "";
        }
        String first = lines.get(0).getMpnRaw();
        String s = lines.size() == 1 ? first : first + " 等 " + lines.size() + " 项";
        return s.length() > 20 ? s.substring(0, 20) : s;
    }

    private static boolean expired(ElcRfq h) {
        return ElcRfq.STATUS_QUOTED.equals(h.getStatus()) && h.getQuoteValidUntil() != null
                && h.getQuoteValidUntil().isBefore(LocalDate.now());
    }

    private static String shownStatus(ElcRfq h) {
        return expired(h) ? ElcRfq.STATUS_EXPIRED : h.getStatus();
    }

    private static LineQuote quoteOf(ElcRfqLine l) {
        return l.getQuoteE6() == null ? null
                : new LineQuote(l.getQuoteE6(), l.getQuoteQty(), l.getQuoteDcYear(), l.getQuoteLeadDays(),
                l.getQuoteNote());
    }

    private static RfqView view(ElcRfq h, List<ElcRfqLine> lines) {
        return new RfqView(h.getRfqNo(), shownStatus(h), h.getCreatedAt(), h.getLineCnt(), h.getNeedInvoice(),
                h.getDcReq(), h.getDeliverCity(), h.getCompany(), h.getContactName(), Masks.phone(h.getContactPhone()),
                h.getRemark(), h.getQuotedAt(), h.getQuoteValidUntil(), h.getQuoteNote(), h.getCloseReason(),
                lines.stream().map(l -> new LineView(l.getLineNo(), l.getPartNo(), l.getMpnRaw(), l.getMfrRaw(),
                        l.getQty(), l.getTargetE6(), quoteOf(l))).toList());
    }

    private OpsRfqView opsView(ElcRfq h, List<ElcRfqLine> lines, boolean withSources) {
        LocalDate today = LocalDate.now();
        List<OpsLineView> views = new ArrayList<>();
        for (ElcRfqLine l : lines) {
            List<OpsSource> sources = !withSources || l.getPartNo() == null ? List.of()
                    : stockMapper.sourcesOf(l.getPartNo(), today, SOURCES_PER_LINE).stream()
                    .map(ElecRfqServiceImpl::opsSource).toList();
            views.add(new OpsLineView(l.getLineNo(), l.getPartNo(), l.getMpnRaw(), l.getMfrRaw(), l.getQty(),
                    l.getTargetE6(), quoteOf(l), sources));
        }
        return new OpsRfqView(h.getRfqNo(), shownStatus(h), h.getCreatedAt(), h.getLineCnt(), h.getContactName(),
                h.getContactPhone(), h.getCompany(), h.getNeedInvoice(), h.getDcReq(), h.getDeliverCity(),
                h.getRemark(), h.getQuotedAt(), h.getQuotedBy(), h.getQuoteValidUntil(), h.getQuoteNote(),
                h.getBuyerNotifiedAt() != null, h.getCloseReason(), views);
    }

    private static OpsSource opsSource(SourceRow s) {
        return new OpsSource(s.getSupplierNo(), s.getCompanyName(), s.getContactPhone(), s.getQty(),
                s.getDateCode(), s.getPriceE6(), Boolean.TRUE.equals(s.getTaxIncluded()));
    }

    // ── 查询小件 ────────────────────────────────────────────────────────────

    /**
     * 这一行对应料号库里的哪个料号。端上带了 partNo 且存在就用它；
     * 没带就按规范化料号找，<b>恰好一个</b>才认（同串多厂牌时不猜，交给运营）。
     */
    private String partOf(LineReq l) {
        if (l.partNo() != null && !l.partNo().isBlank()) {
            ElcPart p = partMapper.selectOne(Wrappers.<ElcPart>lambdaQuery().eq(ElcPart::getPartNo, l.partNo()));
            if (p != null) {
                return p.getPartNo();
            }
        }
        List<ElcPart> same = partMapper.selectList(Wrappers.<ElcPart>lambdaQuery()
                .eq(ElcPart::getMpnNorm, Mpn.norm(l.mpn()))
                .ne(ElcPart::getStatus, ElcPart.STATUS_MERGED)
                .last("LIMIT 2"));
        return same.size() == 1 ? same.get(0).getPartNo() : null;
    }

    /** 给运营群的内容：连同库里谁有货 —— 买家侧任何地方都看不到这一段 */
    private ElecAlerts.RfqAlert alertOf(ElcRfq rfq, List<ElcRfqLine> rows) {
        LocalDate today = LocalDate.now();
        List<ElecAlerts.RfqLine> lines = new ArrayList<>();
        for (ElcRfqLine r : rows) {
            List<ElecAlerts.Source> sources = r.getPartNo() == null ? List.of()
                    : stockMapper.sourcesOf(r.getPartNo(), today, SOURCES_PER_LINE).stream()
                    .map(s -> new ElecAlerts.Source(s.getCompanyName(), s.getContactPhone(), s.getQty(),
                            s.getDateCode(), s.getPriceE6(), Boolean.TRUE.equals(s.getTaxIncluded())))
                    .toList();
            lines.add(new ElecAlerts.RfqLine(r.getMpnRaw(), r.getMfrRaw(), r.getQty(), r.getTargetE6(), sources));
        }
        return new ElecAlerts.RfqAlert(rfq.getRfqNo(), rfq.getContactName(), rfq.getContactPhone(),
                rfq.getCompany(), rfq.getNeedInvoice(), rfq.getDcReq(), rfq.getDeliverCity(), rfq.getRemark(),
                lines);
    }

    /** 按买家号一起查：别人的询价单号猜中了也是 404，不是 403 —— 不告诉他这张单存在 */
    private ElcRfq mineOr404(String userNo, String rfqNo) {
        ElcRfq h = rfqMapper.selectOne(Wrappers.<ElcRfq>lambdaQuery()
                .eq(ElcRfq::getRfqNo, rfqNo).eq(ElcRfq::getBuyerRef, userNo));
        if (h == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return h;
    }

    private ElcRfq or404(String rfqNo) {
        ElcRfq h = rfqMapper.selectOne(Wrappers.<ElcRfq>lambdaQuery().eq(ElcRfq::getRfqNo, rfqNo));
        if (h == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return h;
    }

    private List<ElcRfqLine> linesOf(String rfqNo) {
        return lineMapper.selectList(Wrappers.<ElcRfqLine>lambdaQuery()
                .eq(ElcRfqLine::getRfqNo, rfqNo).orderByAsc(ElcRfqLine::getLineNo));
    }

    private Map<String, List<ElcRfqLine>> linesOf(List<ElcRfq> heads) {
        if (heads.isEmpty()) {
            return Map.of();
        }
        return lineMapper.selectList(Wrappers.<ElcRfqLine>lambdaQuery()
                        .in(ElcRfqLine::getRfqNo, heads.stream().map(ElcRfq::getRfqNo).toList())
                        .orderByAsc(ElcRfqLine::getLineNo))
                .stream().collect(Collectors.groupingBy(ElcRfqLine::getRfqNo));
    }

    /** 本域工厂没装分页插件：offset/limit 手写 */
    private static <T> LambdaQueryWrapper<T> page(LambdaQueryWrapper<T> q, int page, int size, int max) {
        int p = Math.max(1, page);
        int n = Math.min(max, Math.max(1, size));
        return q.last("LIMIT " + n + " OFFSET " + (long) (p - 1) * n);
    }
}
