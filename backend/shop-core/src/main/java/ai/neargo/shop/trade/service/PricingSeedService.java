package ai.neargo.shop.trade.service;

import java.util.List;
import ai.neargo.shop.trade.dto.PricingConfigCommands.Context;

/** Controlled seed import creates drafts only; no stores, capability grants, legal profiles, or active revisions. */
public interface PricingSeedService {
    ImportReport importDrafts(Context context, String seed, String registryVersion, ImportMode mode);

    /** HTTP callers select only the supported seed format; registry choice stays inside the service. */
    ImportReport importDrafts(Context context, String seed, ImportMode mode);

    enum ImportMode { DRY_RUN, DRAFT }
    record TemplateRef(String sourceTemplateNo, String templateNo, String industryCode) { }
    record ImportReport(ImportMode mode, String seedVersion, String seedHash, int ruleCount, int templateCount,
            int taxDraftCount, List<TemplateRef> templates) {
        public ImportReport { templates = List.copyOf(templates); }
    }
}
