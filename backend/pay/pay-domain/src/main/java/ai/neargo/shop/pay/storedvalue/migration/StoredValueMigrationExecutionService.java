package ai.neargo.shop.pay.storedvalue.migration;

import ai.neargo.shop.spi.storedvalue.StoredValueMigrationPlans.MaintenanceResult;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationPlans.Preparation;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness.Proof;






/** Stable business API; implementation keeps the original transaction and owner checks. */
public interface StoredValueMigrationExecutionService {
    MaintenanceResult pause(Preparation input,String manifestNo,String key,Proof proof);

    MaintenanceResult apply(Preparation input,String manifestNo,String pauseReceiptNo,String key,Proof proof);

    String guard(Proof proof,boolean verifyLocal);
}
