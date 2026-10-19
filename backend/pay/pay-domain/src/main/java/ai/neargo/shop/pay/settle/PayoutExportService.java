package ai.neargo.shop.pay.settle;

import java.util.Collection;

/** Short export transaction verifies current bill readiness again before exposing a bank payment list. */
public interface PayoutExportService {
    int markExported(Collection<String> payoutNos,String operator);
}
