package ai.neargo.shop.pay.storedvalue.migration;

import ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness.Proof;





/** Stable business API; implementation keeps the original transaction and owner checks. */
public interface StoredValueMigrationResumeService {
    public record Result(String receiptNo,String state,long issuerCount,long accountCount){}

    Result resume(String operator,String approvalNo,Proof proof);

    Result resume(String operator,String approvalNo,Proof proof,String expectedApprovalHash);
}
