package ai.neargo.shop.promotion.cost;

import ai.neargo.shop.promotion.cost.BrandPromotionCostModels.GenerateStatement;
import ai.neargo.shop.promotion.cost.BrandPromotionCostModels.RevisionCommand;
import ai.neargo.shop.promotion.cost.BrandPromotionCostModels.RuleView;
import ai.neargo.shop.promotion.cost.BrandPromotionCostModels.SaveRule;
import ai.neargo.shop.promotion.cost.BrandPromotionCostModels.StatementView;
import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.spi.entitlement.EntitlementUseFactsPort.UseFact;
import java.util.List;

/** Frozen marketing funding terms and entity-scoped reconciliation; confirmation never initiates payment. */
public interface BrandPromotionCostService {
    RuleView saveRule(ActivityActor actor, SaveRule request, String key);
    RuleView publishRule(ActivityActor actor, String ruleVersionNo, long expectedRevision, String key);
    List<RuleView> listRules(ActivityActor actor);
    void validate(ActivityActor actor, String ruleVersionNo, List<String> storeNos);
    void freeze(ActivityActor actor, String ruleVersionNo, String resourceType, String resourceNo,
                String resourceVersion, List<String> storeNos);
    /** Captures the original version inside the order transaction; it is not evidence of payment. */
    void captureActivity(String tenantNo, String applyNo, String activityVersionNo);
    /** Captures native owner-confirmed allocations inside the Core coupon application transaction. */
    void captureCoupon(String tenantNo, String operationNo, UseFact fact);
    StatementView generate(ActivityActor actor, String expenseEntityNo, GenerateStatement request, String key);
    List<StatementView> list(ActivityActor actor, String expenseEntityNo);
    StatementView get(ActivityActor actor, String expenseEntityNo, String statementNo);
    StatementView confirm(ActivityActor actor, String expenseEntityNo, String statementNo,
                          RevisionCommand request, String key);
    StatementView voidStatement(ActivityActor actor, String expenseEntityNo, String statementNo,
                                RevisionCommand request, String key);
}
