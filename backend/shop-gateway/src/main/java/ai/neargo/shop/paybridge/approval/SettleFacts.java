package ai.neargo.shop.paybridge.approval;

import ai.neargo.shop.pay.SettleBatchService.BatchVO;
import ai.neargo.shop.spi.approval.ApprovalSubject.Fact;
import java.util.ArrayList;
import java.util.List;

/** 结算批次在审批详情里的申请内容。 */
final class SettleFacts {

    private SettleFacts() {
    }

    static List<Fact> of(BatchVO b, List<Fact> extra) {
        List<Fact> facts = new ArrayList<>();
        facts.add(Fact.of("批次", b.batchNo()));
        facts.add(Fact.of("商户", b.entityNo()));
        facts.add(Fact.of("渠道 / 周期", b.payChannel() + " / " + b.settleCycle()));
        facts.add(Fact.of("账单数", String.valueOf(b.billCount())));
        facts.add(Fact.of("毛额", PayApprovalSupport.money(b.grossMinor())));
        facts.add(Fact.of("净额", PayApprovalSupport.money(b.netMinor())));
        facts.addAll(extra);
        return facts;
    }
}
