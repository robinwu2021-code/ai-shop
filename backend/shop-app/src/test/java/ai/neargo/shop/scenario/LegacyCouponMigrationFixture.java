package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.*;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.migration.dto.MigrationStageViews.StageCommand;
import ai.neargo.shop.payclient.EntitlementMigrationAppService;
import ai.neargo.shop.promotion.migration.LegacyCouponSourceFenceService;
import ai.neargo.shop.promotion.migration.LegacyCouponSourceFenceService.Action;
import ai.neargo.shop.promotion.migration.LegacyCouponSourceFenceService.Command;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.LegacyCouponFrozenSnapshotPort.Query;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.*;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real frozen source, recognition and pay plan; not an already-imported asset fixture. */
final class LegacyCouponMigrationFixture {
    record Fixture(String id,String batch,CatalogScope scope,EntitlementContext ctx,SourceIdentity source,String planNo,String planHash) { }
    static Fixture prepare(JdbcTemplate jdbc,LegacyCouponSourceFenceService fences,EntitlementMigrationAppService migration,boolean alias) {
        return prepare(jdbc,fences,migration,alias,false,null);
    }
    static Fixture prepareHistory(JdbcTemplate jdbc,LegacyCouponSourceFenceService fences,EntitlementMigrationAppService migration) {
        return prepare(jdbc,fences,migration,false,true,null);
    }
    static Fixture prepareForEntity(JdbcTemplate jdbc,LegacyCouponSourceFenceService fences,EntitlementMigrationAppService migration,String entity) {
        return prepare(jdbc,fences,migration,false,false,entity);
    }
    static Fixture prepareLegacy(JdbcTemplate jdbc,LegacyCouponSourceFenceService fences,EntitlementMigrationAppService migration) {
        String id=UUID.randomUUID().toString(),entity="M"+id,template="LT"+id,user="U"+id,batch="B"+id;
        jdbc.update("INSERT INTO mch_entity(entity_no,owner_user_no,name,status,market,tenant_no,created_at,updated_at) VALUES(?,'OWNER','beauty','ACTIVE','CN','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",entity);
        jdbc.update("INSERT INTO usr_account(user_no,status,tenant_no,created_at,updated_at) VALUES(?,'NORMAL','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",user);
        long from=System.currentTimeMillis()-10000,until=from+3600000;
        jdbc.update("INSERT INTO mkt_coupon(coupon_no,entity_no,title,type,face_minor,funder,status,start_at,end_at,total_count,received_count,per_user_limit,budget_minor,tenant_no,created_at,updated_at) VALUES(?,?,'legacy old','FULL_CUT',100,'MERCHANT','ACTIVE',?,?,10,1,3,1000,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",template,entity,from,until);
        jdbc.update("INSERT INTO mkt_user_coupon(user_coupon_no,coupon_no,user_no,status,received_at,tenant_no,created_at,updated_at) VALUES(?,?,?,'UNUSED',?,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)","UC"+id,template,user,from);
        var ctx=new EntitlementContext("MAIN","OP1","C"+id,"a".repeat(64));
        var source=new SourceIdentity(SourceSystem.MKT_LEGACY,SourceKind.TEMPLATE,template);
        var drain=fences.transition(ctx,source,new Command(Action.BEGIN_DRAIN,batch,0,"legacy fixture","D"+id));
        var frozen=fences.transition(ctx,source,new Command(Action.FREEZE,batch,drain.revision(),"legacy fixture","F"+id));
        var scope=new CatalogScope("MAIN",entity,"OP1",true);
        var staged=migration.stageManifest(scope,new StageCommand(new Query(source,batch,frozen.frozenRevision(),frozen.watermark()),"legacy manifest"),"S"+id);
        var plan=migration.plan(scope,new ai.neargo.shop.pay.migration.dto.MigrationPlanViews.PlanCommand(staged.evidence().stageNo(),"legacy plan"),"P"+id);
        return new Fixture(id,batch,scope,ctx,source,plan.planNo(),plan.planHash());
    }
    private static Fixture prepare(JdbcTemplate jdbc,LegacyCouponSourceFenceService fences,EntitlementMigrationAppService migration,boolean alias,boolean history,String existingEntity) {
        String id=UUID.randomUUID().toString(),entity=existingEntity==null?"M"+id:existingEntity,template="T"+id,user="U"+id,batch="B"+id;
        if(existingEntity==null) jdbc.update("INSERT INTO mch_entity(entity_no,owner_user_no,name,status,market,tenant_no,created_at,updated_at) VALUES(?,'OWNER','beauty','ACTIVE','CN','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",entity);
        jdbc.update("INSERT INTO usr_account(user_no,status,tenant_no,created_at,updated_at) VALUES(?,'NORMAL','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",user);
        long from=System.currentTimeMillis()-10000,until=from+3600000;
        jdbc.update("INSERT INTO pmt_coupon(coupon_no,entity_no,title,benefit_mode,benefit_value,funder,status,issue_mode,redeem_mode,times_total,received_count,total_count,per_user_limit,budget_minor,start_at,end_at,tenant_no,created_at,updated_at) "
                +"VALUES(?,?,'old','CASH',100,'MERCHANT','ACTIVE','CENTER','ORDER',1,1,10,1,1000,?,?,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",template,entity,from,until);
        jdbc.update("INSERT INTO pmt_user_coupon(user_coupon_no,coupon_no,user_no,entity_no,status,times_used,received_at,expire_at,tenant_no,created_at,updated_at) VALUES(?,?,?,?,'UNUSED',0,?,?,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)","UC"+id,template,user,entity,from,until);
        if(history) {
            jdbc.update("INSERT INTO ord_order(order_no,user_no,status,currency,goods_amount,discount_amount,pay_amount,paid_at,tenant_no,created_at,updated_at) VALUES(?,?,'PAID','CNY',1100,100,1000,?,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)","OLD-O"+id,user,from+1000);
            jdbc.update("INSERT INTO ord_sub_order(sub_order_no,order_no,user_no,entity_no,status,goods_amount,discount_amount,pay_amount,tenant_no,created_at,updated_at) VALUES(?,?,?,?,'COMPLETED',1100,100,1000,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)","OLD-SO"+id,"OLD-O"+id,user,entity);
            jdbc.update("INSERT INTO pmt_apply(apply_no,promo_type,promo_no,user_no,entity_no,order_no,sub_order_no,amount_minor,applied_at,funder,redeem_mode,tenant_no,created_at,updated_at) VALUES(?,'COUPON',?,?,?,?,?,100,?,'MERCHANT','ORDER','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)","AP"+id,"UC"+id,user,entity,"OLD-O"+id,"OLD-SO"+id,from+1000);
            jdbc.update("UPDATE pmt_user_coupon SET status='USED',times_used=1,used_at=?,order_no=? WHERE user_coupon_no=?",from+1000,"OLD-O"+id,"UC"+id);
        }
        if(alias) {
            jdbc.update("INSERT INTO mkt_coupon(coupon_no,entity_no,title,type,face_minor,funder,start_at,end_at,total_count,received_count,per_user_limit,budget_minor,tenant_no,created_at,updated_at) VALUES(?,?,'old','FULL_CUT',100,'MERCHANT',?,?,10,1,1,1000,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",template,entity,from,until);
            jdbc.update("INSERT INTO mkt_user_coupon(user_coupon_no,coupon_no,user_no,status,received_at,tenant_no,created_at,updated_at) VALUES(?,?,?,'UNUSED',?,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)","UC"+id,template,user,from);
        }
        var ctx=new EntitlementContext("MAIN","OP1","C"+id,"a".repeat(64));
        var source=new SourceIdentity(SourceSystem.PMT,SourceKind.TEMPLATE,template);
        var drain=fences.transition(ctx,source,new Command(Action.BEGIN_DRAIN,batch,0,"commit fixture","D"+id));
        var frozen=fences.transition(ctx,source,new Command(Action.FREEZE,batch,drain.revision(),"commit fixture","F"+id));
        var scope=new CatalogScope("MAIN",entity,"OP1",true);
        var staged=migration.stageManifest(scope,new StageCommand(new Query(source,batch,frozen.frozenRevision(),frozen.watermark()),"full source"),"S"+id);
        if(alias) {
            var oldSource=new SourceIdentity(SourceSystem.MKT_LEGACY,SourceKind.TEMPLATE,template);
            var oldDrain=fences.transition(ctx,oldSource,new Command(Action.BEGIN_DRAIN,batch,0,"alias fixture","OD"+id));
            var oldFrozen=fences.transition(ctx,oldSource,new Command(Action.FREEZE,batch,oldDrain.revision(),"alias fixture","OF"+id));
            var shadow=migration.stageManifest(scope,new StageCommand(new Query(oldSource,batch,oldFrozen.frozenRevision(),oldFrozen.watermark()),"shadow source"),"OS"+id);
            var pair=new ai.neargo.shop.pay.migration.dto.MigrationAliasViews.Pair(staged.evidence().stageNo(),shadow.evidence().stageNo());
            var comparison=migration.compareAlias(scope,pair);
            assertThat(comparison.status()).as(comparison.toString()).isEqualTo("CANDIDATE_ALIAS");
            migration.recognizeAlias(scope,new ai.neargo.shop.pay.migration.dto.MigrationAliasViews.Recognize(pair,0,0,"same unused family"),"A"+id);
        }
        var plan=migration.plan(scope,new ai.neargo.shop.pay.migration.dto.MigrationPlanViews.PlanCommand(staged.evidence().stageNo(),"target plan"),"P"+id);
        return new Fixture(id,batch,scope,ctx,source,plan.planNo(),plan.planHash());
    }
}
