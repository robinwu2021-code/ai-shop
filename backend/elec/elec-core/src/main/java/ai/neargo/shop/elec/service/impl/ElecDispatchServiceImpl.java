package ai.neargo.shop.elec.service.impl;

import ai.neargo.elec.api.ElecInternal;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.elec.config.ConditionalOnElec;
import ai.neargo.shop.elec.config.ElecProperties;
import ai.neargo.shop.elec.dto.RfqDtos.DeclineReq;
import ai.neargo.shop.elec.dto.RfqDtos.DispatchView;
import ai.neargo.shop.elec.dto.RfqDtos.Offer;
import ai.neargo.shop.elec.dto.RfqDtos.OpsOffer;
import ai.neargo.shop.elec.dto.RfqDtos.OpsQuoteRow;
import ai.neargo.shop.elec.dto.RfqDtos.SupplierQuote;
import ai.neargo.shop.elec.dto.RfqDtos.SupplierQuoteReq;
import ai.neargo.shop.elec.entity.ElcDispatch;
import ai.neargo.shop.elec.entity.ElcQuote;
import ai.neargo.shop.elec.entity.ElcRfq;
import ai.neargo.shop.elec.entity.ElcRfqLine;
import ai.neargo.shop.elec.entity.ElcStock;
import ai.neargo.shop.elec.entity.ElcSupplier;
import ai.neargo.shop.elec.gateway.ElecAlerts;
import ai.neargo.shop.elec.gateway.ElecAlerts.DeclineAlert;
import ai.neargo.shop.elec.gateway.ElecAlerts.QuoteAlert;
import ai.neargo.shop.elec.gateway.ElecBuyerNotifier;
import ai.neargo.shop.elec.gateway.ElecSupplierNotifier;
import ai.neargo.shop.elec.mapper.ElecMappers.DispatchMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.CodeCount;
import ai.neargo.shop.elec.mapper.ElecMappers.DispatchRow;
import ai.neargo.shop.elec.mapper.ElecMappers.OpsOfferRow;
import ai.neargo.shop.elec.mapper.ElecMappers.OpsQuoteRaw;
import ai.neargo.shop.elec.mapper.ElecMappers.QuoteMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.RfqLineMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.RfqMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.StockMapper;
import ai.neargo.shop.elec.service.ElecDispatchService;
import ai.neargo.shop.elec.service.ElecMarketService;
import ai.neargo.shop.elec.support.ElecKeys;
import ai.neargo.shop.elec.support.ElecValues;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 求购 → 派单 → 供应商报价 → 买家看到（匿名、已加价）。
 *
 * <h2>两个方向的匿名</h2>
 * <ul>
 *   <li><b>供应商看不到买家</b>：派单给的是 dispatch_no，查询语句里一个买家字段都不取</li>
 *   <li><b>买家看不到供应商</b>：报价出去前换成这一行内的代号（报价 A / B / C），
 *       且代号<b>只在这一行内有意义</b> —— 跨行跨单的 A 不是同一家，否则一对比就能聚出来</li>
 * </ul>
 *
 * <h2>为什么派单不在询价那个事务里</h2>
 * 派单要查库存、要发通知，慢且会失败。放进去的话，一次通知抖动会让整张询价单回滚 ——
 * 而询价已经是买家按下提交的事实。分开之后最坏情况是「询价在、没派出去」，
 * 运营在企业微信群里照样看得到，可以手工指派。
 */
@ConditionalOnElec
@Service
public class ElecDispatchServiceImpl implements ElecDispatchService {

    private static final Logger log = LoggerFactory.getLogger(ElecDispatchServiceImpl.class);

    /** 买家看到的代号 */
    private static final String[] LABELS = {"A", "B", "C", "D", "E", "F", "G", "H"};

    private static final int QUOTE_DEFAULT_DAYS = 3;
    private static final int QUOTE_MAX_DAYS = 30;

    private final DispatchMapper dispatchMapper;
    private final QuoteMapper quoteMapper;
    private final StockMapper stockMapper;
    private final RfqMapper rfqMapper;
    private final ElecSupplierAccess access;
    private final ElecSupplierNotifier notifier;
    private final ElecMarketService market;
    private final ElecProperties props;
    private final RfqLineMapper lineMapper;
    private final ElecBuyerNotifier buyers;
    private final ElecAlerts alerts;
    private final TransactionTemplate tx;

    public ElecDispatchServiceImpl(DispatchMapper dispatchMapper, QuoteMapper quoteMapper, StockMapper stockMapper,
                                   RfqMapper rfqMapper,
                                   ElecSupplierAccess access, ElecSupplierNotifier notifier,
                                   ElecMarketService market, ElecProperties props, RfqLineMapper lineMapper,
                                   ElecBuyerNotifier buyers, ElecAlerts alerts,
                                   @Qualifier("elecTransactionManager") PlatformTransactionManager tm) {
        this.dispatchMapper = dispatchMapper;
        this.quoteMapper = quoteMapper;
        this.stockMapper = stockMapper;
        this.rfqMapper = rfqMapper;
        this.access = access;
        this.notifier = notifier;
        this.market = market;
        this.props = props;
        this.lineMapper = lineMapper;
        this.buyers = buyers;
        this.alerts = alerts;
        this.tx = new TransactionTemplate(tm);
    }

    // ── 派单 ────────────────────────────────────────────────────────────────

    @Override
    public int dispatch(ElcRfq rfq, List<ElcRfqLine> lines) {
        LocalDate today = LocalDate.now();
        /*
         * 买家本人如果也是供应商，自己的求购不派给自己：占掉一个派单名额（每行有上限），
         * 还能给自己报价、把「几家报了价」撑高。他搜料号时照样看得到自己的货 —— 那是档位，不泄露什么。
         */
        ElcSupplier self = access.of(rfq.getBuyerRef());
        String selfNo = self == null ? null : self.getSupplierNo();
        List<ElcDispatch> rows = new ArrayList<>();
        Set<String> suppliers = new java.util.LinkedHashSet<>();
        for (ElcRfqLine line : lines) {
            if (line.getPartNo() == null) {
                continue;   // 料号库里没有这个料号：谁有货无从查起，交给运营手工指派
            }
            List<ElcStock> stock = stockMapper.selectList(Wrappers.<ElcStock>lambdaQuery()
                    .eq(ElcStock::getPartNo, line.getPartNo())
                    .eq(ElcStock::getStatus, ElcStock.STATUS_ON)
                    .ge(ElcStock::getValidUntil, today));
            Set<String> hit = new java.util.LinkedHashSet<>();
            for (ElcStock s : stock) {
                if (!s.getSupplierNo().equals(selfNo)) {
                    hit.add(s.getSupplierNo());
                }
            }
            /*
             * **每行派几家有上限**（默认 5）。派太多的后果不是吵，是响应率整体塌掉：
             * 一条求购派给二十家，十九家白填一遍报价，下次就没人填了。
             */
            int n = 0;
            for (String supplierNo : hit) {
                if (n++ >= props.getDispatchMaxPerLine()) {
                    break;
                }
                ElcDispatch d = new ElcDispatch();
                d.setDispatchNo(ElecKeys.next(ElecKeys.DISPATCH));
                d.setRfqNo(rfq.getRfqNo());
                d.setLineNo(line.getLineNo());
                d.setSupplierNo(supplierNo);
                d.setVia(ElcDispatch.VIA_AUTO);
                d.setStatus(ElcDispatch.STATUS_SENT);
                d.setCreatedBy("AUTO");
                d.setUpdatedBy("AUTO");
                rows.add(d);
                suppliers.add(supplierNo);
            }
        }
        if (rows.isEmpty()) {
            return 0;
        }
        insertAll(rows);
        countBack(rfq.getRfqNo());
        notifySuppliers(rfq.getRfqNo(), suppliers);
        return suppliers.size();
    }

    @Override
    public int dispatchTo(String rfqNo, int lineNo, List<String> supplierNos, String staffNo) {
        if (supplierNos == null || supplierNos.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        List<ElcDispatch> rows = new ArrayList<>();
        for (String supplierNo : supplierNos) {
            ElcDispatch d = new ElcDispatch();
            d.setDispatchNo(ElecKeys.next(ElecKeys.DISPATCH));
            d.setRfqNo(rfqNo);
            d.setLineNo(lineNo);
            d.setSupplierNo(supplierNo);
            d.setVia(ElcDispatch.VIA_OPS);
            d.setStatus(ElcDispatch.STATUS_SENT);
            d.setCreatedBy(staffNo);
            d.setUpdatedBy(staffNo);
            rows.add(d);
        }
        int n = insertAll(rows);
        countBack(rfqNo);
        notifySuppliers(rfqNo, new java.util.LinkedHashSet<>(supplierNos));
        return n;
    }

    /**
     * 逐条插，撞唯一键就跳过。
     *
     * <p><b>不用批量插</b>：这里正常就是个位数，而「同一行已经派过这家」是常态
     * （运营手工补派时多半会包含已经派过的），批量插会让整批一起失败。
     */
    private int insertAll(List<ElcDispatch> rows) {
        int n = 0;
        for (ElcDispatch d : rows) {
            try {
                dispatchMapper.insert(d);
                n++;
            } catch (DuplicateKeyException e) {
                log.debug("这一行已经派过这家，跳过 rfqNo={} line={} supplier={}",
                        d.getRfqNo(), d.getLineNo(), d.getSupplierNo());
            }
        }
        return n;
    }

    /** 通知供应商。**一家一条**，不按行发 —— 一张 BOM 派给他五行，他要的是「有新求购」一条 */
    private void notifySuppliers(String rfqNo, Set<String> suppliers) {
        for (String supplierNo : suppliers) {
            String account = access.ownerAccount(supplierNo);
            if (account == null) {
                continue;
            }
            long cnt = dispatchMapper.selectCount(Wrappers.<ElcDispatch>lambdaQuery()
                    .eq(ElcDispatch::getRfqNo, rfqNo).eq(ElcDispatch::getSupplierNo, supplierNo));
            boolean ok;
            try {
                ok = notifier.newDispatch(account, supplierNo, (int) cnt);
            } catch (RuntimeException e) {
                log.warn("通知供应商失败 supplierNo={} {}", supplierNo, e.toString());
                ok = false;
            }
            if (ok) {
                ElcDispatch patch = new ElcDispatch();
                patch.setNotifiedAt(LocalDateTime.now());
                dispatchMapper.update(patch, Wrappers.<ElcDispatch>lambdaUpdate()
                        .eq(ElcDispatch::getRfqNo, rfqNo).eq(ElcDispatch::getSupplierNo, supplierNo));
            } else {
                log.warn("求购通知没送到供应商 rfqNo={} supplierNo={}（notified_at 为空的就是这些）",
                        rfqNo, supplierNo);
            }
        }
    }

    /** 回写「派了几家、几家报了价」。列表页要显示，不能每次去数 */
    private void countBack(String rfqNo) {
        long dispatched = dispatchMapper.selectList(Wrappers.<ElcDispatch>lambdaQuery()
                        .eq(ElcDispatch::getRfqNo, rfqNo))
                .stream().map(ElcDispatch::getSupplierNo).distinct().count();
        long quoted = quoteMapper.selectList(Wrappers.<ElcQuote>lambdaQuery()
                        .eq(ElcQuote::getRfqNo, rfqNo).eq(ElcQuote::getStatus, ElcQuote.STATUS_ACTIVE))
                .stream().map(ElcQuote::getSupplierNo).distinct().count();
        rfqMapper.update(null, Wrappers.<ElcRfq>lambdaUpdate().eq(ElcRfq::getRfqNo, rfqNo)
                .set(ElcRfq::getDispatchCnt, (int) dispatched)
                .set(ElcRfq::getQuoteCnt, (int) quoted));
    }

    // ── 供应商侧 ────────────────────────────────────────────────────────────

    @Override
    public List<DispatchView> mine(String userNo, String status, int page, int size) {
        ElcSupplier s = access.requireActive(userNo);
        int n = Math.min(50, Math.max(1, size));
        long offset = (long) (Math.max(1, page) - 1) * n;
        List<DispatchRow> rows = dispatchMapper.mine(s.getSupplierNo(),
                status == null || status.isBlank() ? null : status, n, offset);
        Map<String, ElcQuote> quotes = quotesOf(rows.stream().map(DispatchRow::getDispatchNo).toList());
        return rows.stream().map(r -> view(r, s.getSupplierNo(), quotes.get(r.getDispatchNo()))).toList();
    }

    @Override
    public DispatchView detail(String userNo, String dispatchNo) {
        ElcSupplier s = access.requireActive(userNo);
        ElcDispatch d = mineOr404(s.getSupplierNo(), dispatchNo);
        // 看过了就记一下：响应率的分母是「看到的」，不是「派出去的」
        if (ElcDispatch.STATUS_SENT.equals(d.getStatus())) {
            dispatchMapper.update(null, Wrappers.<ElcDispatch>lambdaUpdate()
                    .eq(ElcDispatch::getId, d.getId()).eq(ElcDispatch::getStatus, ElcDispatch.STATUS_SENT)
                    .set(ElcDispatch::getStatus, ElcDispatch.STATUS_VIEWED)
                    .set(ElcDispatch::getViewedAt, LocalDateTime.now()));
        }
        DispatchRow row = dispatchMapper.mine(s.getSupplierNo(), null, 100, 0).stream()
                .filter(x -> x.getDispatchNo().equals(dispatchNo)).findFirst()
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
        return view(row, s.getSupplierNo(), quotesOf(List.of(dispatchNo)).get(dispatchNo));
    }

    @Override
    public DispatchView quote(String userNo, String dispatchNo, SupplierQuoteReq req) {
        ElcSupplier s = access.requireActive(userNo);
        ElcDispatch d = mineOr404(s.getSupplierNo(), dispatchNo);
        if (ElcDispatch.STATUS_DECLINED.equals(d.getStatus())) {
            throw BizException.of(ErrorCode.ELEC_RFQ_STATE);
        }
        if (req == null || req.priceE6() == null || req.priceE6() <= 0
                || req.qtyAvailable() == null || req.qtyAvailable() <= 0
                || (req.leadDays() != null && (req.leadDays() < 0 || req.leadDays() > 365))) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        int days = req.validDays() == null ? QUOTE_DEFAULT_DAYS
                : Math.max(1, Math.min(QUOTE_MAX_DAYS, req.validDays()));
        LocalDateTime now = LocalDateTime.now();

        if (chosenLines(d.getRfqNo()).contains(d.getLineNo())) {
            // 这一行买家已经选了一家：再报价或改价都没有意义，还会让买家那边的报价列表多出一条「选不了」的
            throw BizException.of(ErrorCode.ELEC_RFQ_STATE);
        }
        ElcQuote existing = quoteMapper.selectOne(Wrappers.<ElcQuote>lambdaQuery()
                .eq(ElcQuote::getDispatchNo, dispatchNo));
        ElcQuote q = existing != null ? existing : new ElcQuote();
        if (existing != null && ElcQuote.STATUS_ACCEPTED.equals(existing.getStatus())) {
            // 买家已经选了这条，不能再改价 —— 改了就是成交价被人单方面变了
            throw BizException.of(ErrorCode.ELEC_RFQ_STATE);
        }
        if (existing == null) {
            q.setQuoteNo(ElecKeys.next(ElecKeys.QUOTE));
            q.setDispatchNo(dispatchNo);
            q.setRfqNo(d.getRfqNo());
            q.setLineNo(d.getLineNo());
            q.setSupplierNo(s.getSupplierNo());
            q.setCreatedBy(userNo);
        }
        q.setPriceE6(req.priceE6());
        q.setCurrency(ElecSupplierServiceImpl.oneOf(ElecValues.upper(req.currency()),
                ElecValues.CURRENCIES, "CNY"));
        q.setTaxIncluded(req.taxIncluded() == null || req.taxIncluded());
        q.setQtyAvailable(req.qtyAvailable());
        q.setDateCode(ElecSupplierServiceImpl.trimmed(req.dateCode(), 16));
        q.setDcYear(ai.neargo.shop.elec.support.Cells.dcYear(req.dateCode()));
        q.setLeadDays(req.leadDays());
        q.setCondGrade(ElecSupplierServiceImpl.oneOf(req.cond(), ElecValues.CONDITIONS, null));
        q.setPacking(ElecSupplierServiceImpl.oneOf(req.packing(), ElecValues.PACKINGS, null));
        q.setMoq(req.moq());
        q.setValidUntil(LocalDate.now().plusDays(days - 1L));
        q.setRemark(ElecSupplierServiceImpl.trimmed(req.remark(), 255));
        q.setStatus(ElcQuote.STATUS_ACTIVE);
        q.setUpdatedBy(userNo);

        tx.executeWithoutResult(st -> {
            if (existing == null) {
                quoteMapper.insert(q);
            } else {
                quoteMapper.updateById(q);
            }
            dispatchMapper.update(null, Wrappers.<ElcDispatch>lambdaUpdate()
                    .eq(ElcDispatch::getId, d.getId())
                    .set(ElcDispatch::getStatus, ElcDispatch.STATUS_QUOTED)
                    .set(ElcDispatch::getRespondedAt, now)
                    .set(ElcDispatch::getDeclineReason, null)
                    .set(ElcDispatch::getUpdatedBy, userNo));
        });
        countBack(d.getRfqNo());
        afterQuote(d, q, existing == null, s);
        return detail(userNo, dispatchNo);
    }

    @Override
    public DispatchView decline(String userNo, String dispatchNo, DeclineReq req) {
        ElcSupplier s = access.requireActive(userNo);
        ElcDispatch d = mineOr404(s.getSupplierNo(), dispatchNo);
        String reason = ElecSupplierServiceImpl.oneOf(req == null ? null : req.reason(),
                Set.of("NO_STOCK", "PRICE", "OTHER"), "OTHER");
        boolean already = ElcDispatch.STATUS_DECLINED.equals(d.getStatus());
        tx.executeWithoutResult(st -> {
            dispatchMapper.update(null, Wrappers.<ElcDispatch>lambdaUpdate()
                    .eq(ElcDispatch::getId, d.getId())
                    .set(ElcDispatch::getStatus, ElcDispatch.STATUS_DECLINED)
                    .set(ElcDispatch::getDeclineReason, reason)
                    .set(ElcDispatch::getRespondedAt, LocalDateTime.now())
                    .set(ElcDispatch::getUpdatedBy, userNo));
            // 已经报过价又改口说没货：把那条报价撤掉，不然买家还看得到
            quoteMapper.update(null, Wrappers.<ElcQuote>lambdaUpdate()
                    .eq(ElcQuote::getDispatchNo, dispatchNo)
                    .eq(ElcQuote::getStatus, ElcQuote.STATUS_ACTIVE)
                    .set(ElcQuote::getStatus, ElcQuote.STATUS_WITHDRAWN)
                    .set(ElcQuote::getUpdatedBy, userNo));
        });
        countBack(d.getRfqNo());
        if (!already) {
            // 同一条再拒一次（端上重复点）不是新的事，不再通知
            afterDecline(d, reason, s);
        }
        return detail(userNo, dispatchNo);
    }

    // ── 报价与拒绝之后的通知（事务提交之后发：通知失败不回滚业务，也不先于数据落库）──

    /**
     * 企业微信每次都推（改价也推，标成「改价」）；买家<b>只在首次报价时</b>通知 ——
     * 改价是常事，每改一次推一条是骚扰，买家打开详情看到的永远是最新价。
     */
    private void afterQuote(ElcDispatch d, ElcQuote q, boolean first, ElcSupplier s) {
        ElcRfq h = rfqOf(d.getRfqNo());
        ElcRfqLine line = lineOf(d.getRfqNo(), d.getLineNo());
        if (h == null || line == null) {
            return;
        }
        int offers = quoteMapper.selectCount(Wrappers.<ElcQuote>lambdaQuery()
                .eq(ElcQuote::getRfqNo, d.getRfqNo()).eq(ElcQuote::getLineNo, d.getLineNo())
                .eq(ElcQuote::getStatus, ElcQuote.STATUS_ACTIVE)
                .ge(ElcQuote::getValidUntil, LocalDate.now())).intValue();
        safeAlert(() -> alerts.supplierQuoted(new QuoteAlert(h.getRfqNo(), line.getLineNo(), line.getMpnRaw(),
                line.getQty(), s.getCompanyName(), s.getContactPhone(), q.getPriceE6(), q.getCurrency(),
                Boolean.TRUE.equals(q.getTaxIncluded()), q.getQtyAvailable(), q.getDateCode(), q.getLeadDays(),
                q.getCondGrade(), offers, !first)), "供应商报价", h.getRfqNo());
        if (!first || !open(h)) {
            return;
        }
        if (notifyBuyer(h, ElecInternal.RESULT_OFFER, line.getMpnRaw())) {
            quoteMapper.update(null, Wrappers.<ElcQuote>lambdaUpdate().eq(ElcQuote::getId, q.getId())
                    .set(ElcQuote::getBuyerNotifiedAt, LocalDateTime.now()));
        } else {
            log.warn("「有供应商报价」没送到买家 quoteNo={}（elc_quote.buyer_notified_at 为空的就是这些）",
                    q.getQuoteNo());
        }
    }

    private void afterDecline(ElcDispatch d, String reason, ElcSupplier s) {
        ElcRfq h = rfqOf(d.getRfqNo());
        ElcRfqLine line = lineOf(d.getRfqNo(), d.getLineNo());
        if (h == null || line == null) {
            return;
        }
        boolean allDeclined = lineAllDeclined(line);
        safeAlert(() -> alerts.supplierDeclined(new DeclineAlert(h.getRfqNo(), line.getLineNo(), line.getMpnRaw(),
                line.getQty(), s.getCompanyName(), s.getContactPhone(), reason, allDeclined)),
                "供应商拒绝", h.getRfqNo());
        if (allDeclined && open(h) && !notifyBuyer(h, ElecInternal.RESULT_LINE_NO_OFFER, line.getMpnRaw())) {
            log.warn("「有一项暂无货源」没送到买家 rfqNo={} line={}", h.getRfqNo(), line.getLineNo());
        }
    }

    /**
     * 这一行<b>已经没人能接了</b>：派出去的都回了话（没有 SENT / VIEWED）、没有有效的供应商报价、平台也没报。
     * 三条缺一条都不算 —— 还有人没回话就说「没货」，第二家报价进来买家会觉得平台前后矛盾。
     */
    boolean lineAllDeclined(ElcRfqLine line) {
        if (line.getQuoteE6() != null) {
            return false;
        }
        long pending = dispatchMapper.selectCount(Wrappers.<ElcDispatch>lambdaQuery()
                .eq(ElcDispatch::getRfqNo, line.getRfqNo()).eq(ElcDispatch::getLineNo, line.getLineNo())
                .in(ElcDispatch::getStatus, ElcDispatch.STATUS_SENT, ElcDispatch.STATUS_VIEWED));
        if (pending > 0) {
            return false;
        }
        long offers = quoteMapper.selectCount(Wrappers.<ElcQuote>lambdaQuery()
                .eq(ElcQuote::getRfqNo, line.getRfqNo()).eq(ElcQuote::getLineNo, line.getLineNo())
                .in(ElcQuote::getStatus, ElcQuote.STATUS_ACTIVE, ElcQuote.STATUS_ACCEPTED)
                .ge(ElcQuote::getValidUntil, LocalDate.now()));
        return offers == 0;
    }

    /** 只有还在询价中的单子才打扰买家：已接受、已关单的不再推 */
    private static boolean open(ElcRfq h) {
        return ElcRfq.STATUS_SUBMITTED.equals(h.getStatus()) || ElcRfq.STATUS_QUOTED.equals(h.getStatus());
    }

    private boolean notifyBuyer(ElcRfq h, String result, String mpn) {
        String summary = mpn == null ? "" : (mpn.length() > 20 ? mpn.substring(0, 20) : mpn);
        try {
            return buyers.rfqResult(h.getBuyerRef(), h.getRfqNo(), result, summary);
        } catch (RuntimeException e) {
            log.warn("通知买家失败 rfqNo={} result={} {}", h.getRfqNo(), result, e.toString());
            return false;
        }
    }

    private void safeAlert(java.util.function.BooleanSupplier send, String what, String rfqNo) {
        boolean ok;
        try {
            ok = send.getAsBoolean();
        } catch (RuntimeException e) {
            ok = false;
        }
        if (!ok) {
            log.warn("「{}」没送到企业微信 rfqNo={}", what, rfqNo);
        }
    }

    private ElcRfq rfqOf(String rfqNo) {
        return rfqMapper.selectOne(Wrappers.<ElcRfq>lambdaQuery().eq(ElcRfq::getRfqNo, rfqNo));
    }

    private ElcRfqLine lineOf(String rfqNo, int lineNo) {
        return lineMapper.selectOne(Wrappers.<ElcRfqLine>lambdaQuery()
                .eq(ElcRfqLine::getRfqNo, rfqNo).eq(ElcRfqLine::getLineNo, lineNo));
    }

    // ── 买家侧（匿名、已加价）────────────────────────────────────────────

    @Override
    public Map<Integer, List<Offer>> offersOf(String rfqNo, List<ElcRfqLine> lines, boolean platformAccepted) {
        LocalDate today = LocalDate.now();
        // 有效的报价要在有效期内；**选中的那条不看有效期** —— 他选过的价过了期也得看得到
        List<ElcQuote> quotes = quoteMapper.selectList(Wrappers.<ElcQuote>lambdaQuery()
                .eq(ElcQuote::getRfqNo, rfqNo)
                .and(w -> w.eq(ElcQuote::getStatus, ElcQuote.STATUS_ACCEPTED)
                        .or(x -> x.eq(ElcQuote::getStatus, ElcQuote.STATUS_ACTIVE).ge(ElcQuote::getValidUntil, today))));
        Map<Integer, List<ElcQuote>> byLine = quotes.stream()
                .collect(Collectors.groupingBy(ElcQuote::getLineNo));
        Map<Integer, List<Offer>> out = new LinkedHashMap<>();
        for (ElcRfqLine line : lines) {
            List<Offer> offers = new ArrayList<>();
            if (line.getQuoteE6() != null) {
                // 平台自己报的那条：已经是对买家的价，不再加价
                offers.add(new Offer("P" + line.getLineNo(), "平台", line.getQuoteE6(), line.getQuoteQty(),
                        line.getQuoteDcYear(), line.getQuoteLeadDays(), line.getQuoteCond(),
                        line.getQuotePacking(), null, line.getQuoteNote(), "PLATFORM", platformAccepted));
            }
            List<ElcQuote> mine = byLine.getOrDefault(line.getLineNo(), List.of()).stream()
                    .sorted(Comparator.comparingLong(q -> toBuyerPrice(q)))
                    .toList();
            int i = 0;
            for (ElcQuote q : mine) {
                /*
                 * **代号只在这一行内有意义**：下一行的 A 不是这一行的 A。
                 * 跨行用同一个代号的话，同一家在整张 BOM 上的报价能被对齐，
                 * 报几次价、每次差多少都露出来了 —— 那等于把「这家是谁」还原了一半。
                 */
                String label = "报价 " + LABELS[Math.min(i, LABELS.length - 1)];
                i++;
                offers.add(new Offer(q.getQuoteNo(), label, toBuyerPrice(q), q.getQtyAvailable(),
                        q.getDcYear(), q.getLeadDays(), q.getCondGrade(), q.getPacking(),
                        q.getValidUntil(), null, "SUPPLIER", ElcQuote.STATUS_ACCEPTED.equals(q.getStatus())));
            }
            out.put(line.getLineNo(), offers);
        }
        return out;
    }

    /** 供应商的价 → 买家看到的价：换成人民币含税，再按平台规则加价 */
    long toBuyerPrice(ElcQuote q) {
        return market.withMarkup(market.toCnyWithTax(q.getPriceE6(), q.getCurrency(), q.getTaxIncluded()));
    }

    @Override
    public void acceptOffer(String rfqNo, int lineNo, String offerNo) {
        ElcQuote q = quoteMapper.selectOne(Wrappers.<ElcQuote>lambdaQuery()
                .eq(ElcQuote::getQuoteNo, offerNo).eq(ElcQuote::getRfqNo, rfqNo)
                .eq(ElcQuote::getLineNo, lineNo));
        if (q == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (!ElcQuote.STATUS_ACTIVE.equals(q.getStatus())) {
            throw BizException.of(ErrorCode.ELEC_RFQ_STATE);
        }
        if (q.getValidUntil().isBefore(LocalDate.now())) {
            throw BizException.of(ErrorCode.ELEC_QUOTE_EXPIRED);
        }
        tx.executeWithoutResult(st -> {
            /*
             * 先锁住这一行：对 elc_rfq_line 做一次无害的 UPDATE，拿到行锁。
             * 两个请求同时选同一行的两家时，第二个会等第一个提交后再往下走，看到「已选过」—— 不靠分布式锁。
             */
            lineMapper.update(null, Wrappers.<ElcRfqLine>lambdaUpdate()
                    .eq(ElcRfqLine::getRfqNo, rfqNo).eq(ElcRfqLine::getLineNo, lineNo)
                    .set(ElcRfqLine::getUpdatedAt, LocalDateTime.now()));
            long chosen = quoteMapper.selectCount(Wrappers.<ElcQuote>lambdaQuery()
                    .eq(ElcQuote::getRfqNo, rfqNo).eq(ElcQuote::getLineNo, lineNo)
                    .eq(ElcQuote::getStatus, ElcQuote.STATUS_ACCEPTED));
            if (chosen > 0) {
                throw BizException.of(ErrorCode.ELEC_RFQ_STATE);   // 一行只能成交一家
            }
            int n = quoteMapper.update(null, Wrappers.<ElcQuote>lambdaUpdate()
                    .eq(ElcQuote::getId, q.getId()).eq(ElcQuote::getStatus, ElcQuote.STATUS_ACTIVE)
                    .set(ElcQuote::getStatus, ElcQuote.STATUS_ACCEPTED));
            if (n == 0) {
                throw BizException.of(ErrorCode.ELEC_RFQ_STATE);
            }
            // 同一行其余还有效的：未被选中。只改状态不推通知，供应商在求购列表里看得到结果
            quoteMapper.update(null, Wrappers.<ElcQuote>lambdaUpdate()
                    .eq(ElcQuote::getRfqNo, rfqNo).eq(ElcQuote::getLineNo, lineNo)
                    .eq(ElcQuote::getStatus, ElcQuote.STATUS_ACTIVE)
                    .set(ElcQuote::getStatus, ElcQuote.STATUS_NOT_CHOSEN));
        });
        String account = access.ownerAccount(q.getSupplierNo());
        if (account != null && !notifier.quoteAccepted(account, q.getQuoteNo(), q.getQtyAvailable())) {
            log.warn("「你的报价被选中」没送到供应商 quoteNo={}", q.getQuoteNo());
        }
    }

    @Override
    public Set<Integer> chosenLines(String rfqNo) {
        return quoteMapper.selectList(Wrappers.<ElcQuote>lambdaQuery()
                        .eq(ElcQuote::getRfqNo, rfqNo).eq(ElcQuote::getStatus, ElcQuote.STATUS_ACCEPTED))
                .stream().map(ElcQuote::getLineNo).collect(Collectors.toSet());
    }

    // ── 运营端 ──────────────────────────────────────────────────────────────

    @Override
    public Map<Integer, List<OpsOffer>> opsOffersOf(String rfqNo) {
        Map<Integer, List<OpsOffer>> out = new LinkedHashMap<>();
        for (OpsOfferRow r : dispatchMapper.opsOffers(rfqNo)) {
            Long buyerPrice = r.getPriceE6() == null ? null
                    : market.withMarkup(market.toCnyWithTax(r.getPriceE6(), r.getCurrency(), r.getTaxIncluded()));
            out.computeIfAbsent(r.getLineNo(), k -> new ArrayList<>()).add(new OpsOffer(r.getDispatchNo(),
                    r.getSupplierNo(), r.getCompanyName(), r.getContactPhone(), r.getVia(), r.getDispatchStatus(),
                    r.getDeclineReason(), r.getNotifiedAt(), r.getRespondedAt(), r.getQuoteNo(), r.getPriceE6(),
                    r.getCurrency(), r.getTaxIncluded(), buyerPrice, r.getQtyAvailable(), r.getDateCode(),
                    r.getLeadDays(), r.getCondGrade(), r.getPacking(), r.getMoq(), r.getValidUntil(),
                    r.getRemark(), shownQuoteStatus(r.getQuoteStatus(), r.getValidUntil())));
        }
        return out;
    }

    @Override
    public Map<String, Integer> respondedCounts(Collection<String> rfqNos) {
        if (rfqNos.isEmpty()) {
            return Map.of();
        }
        return dispatchMapper.respondedByRfq(rfqNos).stream()
                .collect(Collectors.toMap(CodeCount::getCode, c -> c.getCnt().intValue()));
    }

    @Override
    public List<OpsQuoteRow> opsQuotes(String supplierNo, String status, int page, int size) {
        int n = Math.min(100, Math.max(1, size));
        long offset = (long) (Math.max(1, page) - 1) * n;
        String supplier = supplierNo == null || supplierNo.isBlank() ? null : supplierNo.trim();
        String st = status == null || status.isBlank() ? null : status;
        List<OpsQuoteRaw> rows = quoteMapper.opsList(supplier, st, LocalDate.now(), n, offset);
        return rows.stream().map(r -> new OpsQuoteRow(r.getQuoteNo(), r.getRfqNo(), r.getLineNo(), r.getMpn(),
                r.getQtyWanted() == null ? 0 : r.getQtyWanted(), r.getSupplierNo(), r.getCompanyName(),
                r.getPriceE6(), r.getCurrency(), Boolean.TRUE.equals(r.getTaxIncluded()),
                market.withMarkup(market.toCnyWithTax(r.getPriceE6(), r.getCurrency(), r.getTaxIncluded())),
                r.getQtyAvailable() == null ? 0 : r.getQtyAvailable(), r.getLeadDays(), r.getValidUntil(),
                shownQuoteStatus(r.getStatus(), r.getValidUntil()), r.getCreatedAt())).toList();
    }

    /** 「已过期」不是存下来的状态：有效期过了的 ACTIVE 显示成 EXPIRED。与报价记录的筛选同一个口径 */
    static String shownQuoteStatus(String status, LocalDate validUntil) {
        if (ElcQuote.STATUS_ACTIVE.equals(status) && validUntil != null && validUntil.isBefore(LocalDate.now())) {
            return ElcQuote.STATUS_EXPIRED;
        }
        return status;
    }

    // ── 小件 ────────────────────────────────────────────────────────────────

    private ElcDispatch mineOr404(String supplierNo, String dispatchNo) {
        ElcDispatch d = dispatchMapper.selectOne(Wrappers.<ElcDispatch>lambdaQuery()
                .eq(ElcDispatch::getDispatchNo, dispatchNo).eq(ElcDispatch::getSupplierNo, supplierNo));
        if (d == null) {
            // 别人的派单号猜中了也是 404：不告诉他这条求购存在
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return d;
    }

    private Map<String, ElcQuote> quotesOf(List<String> dispatchNos) {
        if (dispatchNos.isEmpty()) {
            return Map.of();
        }
        Map<String, ElcQuote> out = new HashMap<>();
        for (ElcQuote q : quoteMapper.selectList(Wrappers.<ElcQuote>lambdaQuery()
                .in(ElcQuote::getDispatchNo, dispatchNos))) {
            out.put(q.getDispatchNo(), q);
        }
        return out;
    }

    private DispatchView view(DispatchRow r, String supplierNo, ElcQuote q) {
        Long inStock = null;
        List<ElcStock> stock = stockMapper.selectList(Wrappers.<ElcStock>lambdaQuery()
                .eq(ElcStock::getSupplierNo, supplierNo)
                .eq(ElcStock::getMpnNorm, ai.neargo.shop.elec.support.Mpn.norm(r.getMpnRaw()))
                .eq(ElcStock::getStatus, ElcStock.STATUS_ON));
        if (!stock.isEmpty()) {
            inStock = stock.stream().mapToLong(ElcStock::getQty).sum();
        }
        return new DispatchView(r.getDispatchNo(), r.getStatus(), r.getCreatedAt(), r.getMpnRaw(),
                r.getMfrRaw(), r.getQty(), r.getTargetE6(), r.getDcReq(), r.getCondReq(), r.getPackingReq(),
                r.getNeedByDays(), Boolean.TRUE.equals(r.getAllowAlt()), r.getNeedInvoice(),
                province(r.getDeliverCity()), inStock,
                q == null ? null : new SupplierQuote(q.getQuoteNo(), q.getPriceE6(), q.getCurrency(),
                        Boolean.TRUE.equals(q.getTaxIncluded()), q.getQtyAvailable(), q.getDateCode(),
                        q.getLeadDays(), q.getCondGrade(), q.getPacking(), q.getMoq(), q.getValidUntil(),
                        q.getRemark(), q.getStatus()));
    }

    /**
     * 收货地只给到省。**给到市就能把买家缩小一大截** —— 一个料号、一个数量、一个城市，
     * 在这个圈子里足够认出是哪家厂在备货。
     */
    static String province(String city) {
        if (city == null || city.isBlank()) {
            return null;
        }
        return ai.neargo.shop.elec.support.Provinces.of(city);
    }
}
