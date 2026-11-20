package ai.neargo.shop.merchantbridge;

import org.springframework.beans.factory.annotation.Value;

import ai.neargo.commerce.api.common.StoreRef;
import ai.neargo.commerce.api.subscription.StoreRelinkFacade;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.user.StoreRelinkHostFactsPort;
import ai.neargo.shop.spi.user.StoreRelinkIndustryFactsPort;
import ai.neargo.shop.merchant.service.MerchantPlanService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Host-owned current facts. Pay proves and reserves its own facts in a separate transaction. */
@Component
public class StoreRelinkLocalPolicy {
    public record Check(Map<String,Object> store, List<String> blockers, int revokedStaff) {}
    private final JdbcTemplate jdbc;
    private final StoreRelinkHostFactsPort history;
    private final ObjectProvider<StoreRelinkFacade> subscriptions;
    private final List<StoreRelinkIndustryFactsPort> industries;
    private final MerchantPlanService plans;
    @Value("${inventory.enabled:false}")
    private boolean inventoryEnabled;
    @Value("${shop.inventory.enabled:false}")
    private boolean inventoryBridgeEnabled;
    public StoreRelinkLocalPolicy(JdbcTemplate jdbc, StoreRelinkHostFactsPort history,
            ObjectProvider<StoreRelinkFacade> subscriptions,List<StoreRelinkIndustryFactsPort> industries,MerchantPlanService plans) {
        this.jdbc=jdbc;this.history=history;this.subscriptions=subscriptions;this.industries=industries;this.plans=plans;
    }
    public Check inspect(String tenant,String user,String source,String store,String target,String ownClaim) {
        var rows=jdbc.queryForList("SELECT * FROM mch_store WHERE tenant_no=? AND store_no=? AND entity_no=? AND deleted=0",tenant,store,source);
        if(rows.size()!=1) throw BizException.of(ErrorCode.NOT_FOUND);
        var st=rows.getFirst();
        if(!owner(tenant,user,source)) throw BizException.of(ErrorCode.FORBIDDEN);
        var blockers=new ArrayList<String>();
        // The independent inventory database has no ownership-reservation protocol yet.
        if(inventoryEnabled || inventoryBridgeEnabled) blockers.add("INVENTORY_HISTORY_UNKNOWN");
        var old=jdbc.queryForMap("SELECT * FROM mch_entity WHERE tenant_no=? AND entity_no=? AND deleted=0",tenant,source);
        if(!"PENDING_LICENSE".equals(old.get("status"))) blockers.add("SOURCE_NOT_PLACEHOLDER");
        if(!Objects.equals(old.get("ownership_change_no"),ownClaim) || !Objects.equals(st.get("ownership_change_no"),ownClaim)) blockers.add("CLAIM_IN_PROGRESS");
        if(!owner(tenant,user,target)) blockers.add("TARGET_NOT_OWNED");
        else {
            var next=jdbc.queryForList("SELECT * FROM mch_entity WHERE tenant_no=? AND entity_no=? AND deleted=0",tenant,target);
            if(next.size()!=1 || !"ACTIVE".equals(next.getFirst().get("status"))) blockers.add("TARGET_NOT_ACTIVE");
            else {
                if(next.getFirst().get("ownership_change_no")!=null) blockers.add("CLAIM_IN_PROGRESS");
                if(!Objects.equals(old.get("market"),next.getFirst().get("market"))) blockers.add("TARGET_MARKET_MISMATCH");
                int current=jdbc.queryForObject("SELECT COUNT(*) FROM mch_store WHERE tenant_no=? AND entity_no=? AND status='ACTIVE' AND deleted=0",Integer.class,tenant,target);
                if("ACTIVE".equals(st.get("status")) && current>=plans.current(target).storeQuota()) blockers.add("TARGET_STORE_QUOTA_EXCEEDED");
            }
        }
        var facts=history.inspect(tenant,source,store);
        if(facts.hasTrades()) blockers.add("HAS_TRADES");
        if(facts.pendingApply()) blockers.add("APPLY_PENDING");
        for(var industry:industries) {
            var owned=industry.inspect(tenant,source,store);
            if(!owned.complete()) blockers.add("INDUSTRY_HISTORY_UNKNOWN");
            if(owned.hasHistory()) blockers.add("HAS_TRADES");
        }
        // Industry deployments without a verified ownership fence cannot participate in migration.
        if(st.get("business_type_code")==null || !"RETAIL".equals(st.get("business_type_code"))
                && industries.stream().noneMatch(i->i.businessTypes().contains(st.get("business_type_code"))))
            blockers.add("INDUSTRY_HISTORY_UNKNOWN");
        var commerce=subscriptions.getIfAvailable();
        if(commerce==null) blockers.add("SUBSCRIPTION_UNKNOWN");
        else {
            var sub=commerce.inspect(new StoreRef(tenant,store));
            if(!sub.complete()) blockers.add("SUBSCRIPTION_UNKNOWN");
            if(sub.hasPaidHistory()) blockers.add("PAID_PLAN");
            if(!"LEGACY".equals(st.get("commerce_model")) && st.get("commerce_model")!=null
                    && !sub.accountPresent())
                blockers.add("SUBSCRIPTION_UNKNOWN");
        }
        if(jdbc.queryForObject("SELECT COUNT(*) FROM mch_entity_plan WHERE tenant_no=? AND entity_no=? AND (plan_code<>'FREE' OR expire_at IS NOT NULL OR downgraded_at IS NOT NULL OR trial_used=1)",Integer.class,tenant,source)>0)
            blockers.add("PAID_PLAN");
        // Existing brand/financial membership is outside placeholder-only ownership migration.
        if(jdbc.queryForObject("SELECT COUNT(*) FROM mch_brand_store_binding WHERE tenant_no=? AND store_no=?",Integer.class,tenant,store)>0
                || jdbc.queryForObject("SELECT COUNT(*) FROM mch_brand_change WHERE tenant_no=? AND store_no=?",Integer.class,tenant,store)>0
                || jdbc.queryForObject("SELECT COUNT(*) FROM mch_brand_financial_change WHERE tenant_no=? AND store_no=?",Integer.class,tenant,store)>0)
            blockers.add("ORGANIZATION_HISTORY");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM mch_service_area WHERE tenant_no=? AND store_no=?",Integer.class,tenant,store)>0
                || jdbc.queryForObject("SELECT COUNT(*) FROM mch_fulfillment_channel WHERE tenant_no=? AND store_no=?",Integer.class,tenant,store)>0
                || jdbc.queryForObject("SELECT COUNT(*) FROM mch_entity_community WHERE tenant_no=? AND entity_no=?",Integer.class,tenant,source)>0)
            blockers.add("STORE_CONFIGURATION_HISTORY");
        int staff=jdbc.queryForObject("SELECT COUNT(DISTINCT r.mch_account_no) FROM mch_store_role r WHERE r.tenant_no=? AND r.store_no=? AND r.deleted=0 AND NOT EXISTS(SELECT 1 FROM mch_account a WHERE a.tenant_no=r.tenant_no AND a.mch_account_no=r.mch_account_no AND a.entity_no=? AND a.is_owner=1 AND a.deleted=0)",Integer.class,tenant,store,source);
        return new Check(st,List.copyOf(blockers),staff);
    }
    public boolean owner(String tenant,String user,String entity) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM mch_account WHERE tenant_no=? AND user_no=? AND entity_no=? AND is_owner=1 AND status='ACTIVE' AND deleted=0",Integer.class,tenant,user,entity)>0;
    }
}
