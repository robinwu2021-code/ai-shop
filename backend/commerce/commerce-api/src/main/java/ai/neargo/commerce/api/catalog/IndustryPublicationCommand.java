package ai.neargo.commerce.api.catalog;

import java.util.List;
import ai.neargo.commerce.api.common.*;

public record IndustryPublicationCommand(String operationNo, String typeCode, String freePlanVersionNo, String proPlanVersionNo, String chainPlanVersionNo, List<String> candidateSkuVersionNos, long expectedCatalogVersion) {}
