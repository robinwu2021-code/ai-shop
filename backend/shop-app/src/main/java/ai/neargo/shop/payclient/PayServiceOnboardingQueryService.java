package ai.neargo.shop.payclient;

import ai.neargo.shop.common.PageData;
import java.util.List;

/** Read-only source facts; profile review and channel onboarding remain separate. */
public interface PayServiceOnboardingQueryService {
    record Row(String entityNo, String entityName, String status, String merchantRef, String linkStatus,
               String profileStatus, String reviewReason, List<PaySourceLinkApi.Onboarding> onboardings,
               String onboardingOpsUrl) {}

    PageData<Row> list(String keyword, long page, long size);
}
