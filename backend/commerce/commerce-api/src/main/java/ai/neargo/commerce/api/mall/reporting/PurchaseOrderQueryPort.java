package ai.neargo.commerce.api.mall.reporting;

import java.util.List;
import ai.neargo.commerce.api.mall.reporting.PurchaseReporting.*;

/** Domain-owned STORE reporting. Attribution is trusted internal use only, tenant constrained. */
public interface PurchaseOrderQueryPort {
    List<Row> search(Query query);
    List<Row> findByOrderNos(String tenantNo, List<String> orderNos);
}
