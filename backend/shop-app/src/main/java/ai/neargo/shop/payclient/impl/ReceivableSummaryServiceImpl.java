package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.acquiring.service.MerchantCollectLinkQueryService.BizLink;
import ai.neargo.shop.pay.biz.PayBizFacade.BizReceipts;
import ai.neargo.shop.pay.biz.PayBizFacade.StoredValueReceipt;
import ai.neargo.shop.payclient.BizPaymentAppService;
import ai.neargo.shop.payclient.ReceivableSummaryService;
import ai.neargo.shop.trade.service.OfflineRecordService;
import ai.neargo.shop.trade.service.OfflineRecordService.OfflineRecordView;
import ai.neargo.shop.trade.service.impl.OfflineRecordServiceImpl;

import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** {@link ReceivableSummaryService} 的实现：收款域给原始行，外部记账在本库，这里只做加减。 */
@Service
public class ReceivableSummaryServiceImpl implements ReceivableSummaryService {

    private static final long MAX_MINOR = 99_999_999_999L;

    private final BizPaymentAppService pay;
    private final OfflineRecordService offline;

    public ReceivableSummaryServiceImpl(BizPaymentAppService pay, OfflineRecordService offline) {
        this.pay = pay;
        this.offline = offline;
    }

    @Override
    public Summary summarize(String entityNo, String storeNo, String actorNo, String bizNo, String relatedBizNo,
                             long dueMinor, String currency) {
        if (!valid(bizNo) || relatedBizNo != null && (!valid(relatedBizNo) || relatedBizNo.equals(bizNo))
                || dueMinor < 0 || dueMinor > MAX_MINOR || currency == null || !currency.matches("[A-Z]{3}")) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        List<Line> lines = new ArrayList<>();
        collect(lines, entityNo, storeNo, actorNo, bizNo);
        if (relatedBizNo != null) {
            collect(lines, entityNo, storeNo, actorNo, relatedBizNo);
        }
        lines.sort(Comparator.comparing(Line::at, Comparator.nullsLast(Comparator.naturalOrder())));
        long received = 0;
        List<Line> shown = new ArrayList<>(lines.size());
        List<Line> anomalies = new ArrayList<>();
        for (Line l : lines) {
            if (l.countedMinor() > 0 && !currency.equals(l.currency())) {
                Line excluded = new Line(l.bizNo(), l.kind(), l.refNo(), l.status(), l.currency(), l.amountMinor(), 0, l.at());
                shown.add(excluded);
                anomalies.add(excluded);
            } else {
                shown.add(l);
                received = Math.addExact(received, l.countedMinor());
            }
        }
        return new Summary(bizNo, currency, dueMinor, received, Math.max(0, dueMinor - received),
                Math.max(0, received - dueMinor), List.copyOf(shown), List.copyOf(anomalies));
    }

    private static boolean valid(String no) {
        return no != null && no.matches(OfflineRecordServiceImpl.BIZ_NO);
    }

    /** 一张单名下三处的原始行 */
    private void collect(List<Line> lines, String entityNo, String storeNo, String actorNo, String bizNo) {
        BizReceipts receipts = pay.receipts(storeNo, bizNo);
        for (BizLink l : receipts.links()) {
            long counted = "PAID".equals(l.status()) ? Math.max(0, l.paidMinor() - l.refundedMinor()) : 0;
            lines.add(new Line(bizNo, "PAY_LINK", l.payOrderNo(), l.status(), l.currency(), l.amountMinor(), counted,
                    l.paidAt() != null ? l.paidAt() : l.createdAt()));
        }
        for (OfflineRecordView r : offline.ofBizNo(new OfflineRecordService.Scope(entityNo, storeNo, actorNo), bizNo)) {
            long counted = "VOIDED".equals(r.status()) ? 0 : r.amountMinor();
            lines.add(new Line(bizNo, "OFFLINE", r.recordNo(), r.status(), r.currency(), r.amountMinor(), counted,
                    r.receivedAt() == null ? null : OffsetDateTime.parse(r.receivedAt()).toInstant().toEpochMilli()));
        }
        for (StoredValueReceipt u : receipts.storedValue()) {
            long counted = "COMMITTED".equals(u.status()) ? u.amountMinor() : 0;
            lines.add(new Line(bizNo, "STORED_VALUE", u.usageNo(), u.status(), u.currency(), u.amountMinor(), counted,
                    u.occurredAt()));
        }
    }
}
