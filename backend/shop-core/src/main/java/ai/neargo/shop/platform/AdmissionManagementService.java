package ai.neargo.shop.platform;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.platform.dto.OpsVOs.MerchantApplyVO;
import ai.neargo.shop.spi.user.SelfOperatedAdmissionPort;
import java.util.ArrayList;
import java.util.Comparator;
import org.springframework.stereotype.Service;

/** One read list; the application and platform-owned write workflows remain separate. */
@Service
public class AdmissionManagementService {
    private final OpsService applications;
    private final SelfOperatedAdmissionPort selfOperated;
    private final MarketPhonePolicyService markets;

    public AdmissionManagementService(OpsService applications, SelfOperatedAdmissionPort selfOperated,
                                      MarketPhonePolicyService markets) {
        this.applications = applications;
        this.selfOperated = selfOperated;
        this.markets = markets;
    }

    public record Entry(String key, String type, String name, String ownerUserNo, String status,
                        long createdAt, MerchantApplyVO application,
                        SelfOperatedAdmissionPort.Entry selfOperated) {}

    public PageData<Entry> search(String type, String status, String keyword, long page, long size,
                                  boolean canAudit, boolean canSubmit, boolean canSelfOperate) {
        String market = markets.instancePolicy().market();
        long window = page * size;
        var rows = new ArrayList<Entry>();
        long total = 0;
        if ((canAudit || canSubmit) && !"SELF_OPERATED".equals(type)) {
            var result = applications.searchApplies(
                    status == null || status.isBlank() ? "PENDING,REVIEWING,APPROVED,REJECTED" : status,
                    keyword, 1, window, !canAudit || "ON_BEHALF".equals(type), market, true);
            total += result.total();
            result.records().forEach(a -> rows.add(new Entry("APPLY:" + a.applyNo(), "ON_BEHALF",
                    a.name(), a.ownerUserNo(), a.status(), a.createdAt(), a, null)));
        }
        if (canSelfOperate && !"ON_BEHALF".equals(type)) {
            var result = selfOperated.search(market, status, keyword, window);
            total += result.total();
            result.records().forEach(e -> rows.add(new Entry("SELF:" + e.merchantNo(), "SELF_OPERATED",
                    e.name(), e.ownerUserNo(), e.status(), e.createdAt(), null, e)));
        }
        rows.sort(Comparator.comparingLong(Entry::createdAt).reversed()
                .thenComparing(Entry::key, Comparator.reverseOrder()));
        long from = (page - 1) * size;
        var records = rows.stream().skip(from).limit(size).toList();
        return PageData.of(records, total, page, size);
    }
}
