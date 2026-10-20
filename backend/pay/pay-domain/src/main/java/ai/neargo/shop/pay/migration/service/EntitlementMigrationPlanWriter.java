package ai.neargo.shop.pay.migration.service;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.service.EntitlementCommandDeduplicator;
import ai.neargo.shop.pay.mapper.entitlement.EntMigrationAliasMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntMigrationPlanMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntMigrationStageMapper;
import ai.neargo.shop.pay.migration.dto.MigrationPlanViews.PlanCommand;
import ai.neargo.shop.pay.migration.dto.MigrationPlanViews.PlanView;
import ai.neargo.shop.pay.migration.entity.EntMigrationAlias;
import ai.neargo.shop.pay.migration.entity.EntMigrationPlan;
import ai.neargo.shop.pay.migration.entity.EntMigrationStage;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Dedup -> sorted local source evidences -> alias -> plan locks. No remote or financial calls. */
@Component
public class EntitlementMigrationPlanWriter {
    public static final String OPERATION="MIGRATION_PLAN";
    private final EntMigrationPlanMapper plans;
    private final EntMigrationStageMapper stages;
    private final EntMigrationAliasMapper aliases;
    private final EntitlementCommandDeduplicator commands;
    private final ObjectMapper json;
    public EntitlementMigrationPlanWriter(EntMigrationPlanMapper plans,EntMigrationStageMapper stages,
            EntMigrationAliasMapper aliases,EntitlementCommandDeduplicator commands,ObjectMapper json) {
        this.plans=plans;this.stages=stages;this.aliases=aliases;this.commands=commands;this.json=json;
    }
    @Transactional("payTxManager")
    public PlanView claim(CatalogScope scope,PlanCommand command,String key,EntMigrationPlan candidate,
                          List<EntMigrationStage> expected,EntMigrationAlias alias) {
        return commands.execute(scope,OPERATION,key,command,candidate.getPlanNo(),PlanView.class,()->{
            for(var original:expected.stream().sorted(Comparator.comparing(EntMigrationStage::getStageNo)).toList()) {
                var locked=stages.lockStage(scope.tenantNo(),original.getStageNo());
                if(locked==null || !json.writeValueAsString(original).equals(json.writeValueAsString(locked))) conflict();
            }
            if(alias!=null) {
                var locked=aliases.lockAlias(scope.tenantNo(),alias.getAliasNo());
                if(locked==null || !json.writeValueAsString(alias).equals(json.writeValueAsString(locked))) conflict();
            }
            plans.claim(candidate);
            var row=plans.lock(scope.tenantNo(),candidate.getPlanNo());
            if(row==null || !candidate.getCommandHash().equals(row.getCommandHash())
                    || !scope.entityNo().equals(row.getEntityNo())) conflict();
            return EntitlementMigrationPlanService.view(row);
        });
    }
    private static void conflict() { throw BizException.of(ErrorCode.CONFLICT); }
}
