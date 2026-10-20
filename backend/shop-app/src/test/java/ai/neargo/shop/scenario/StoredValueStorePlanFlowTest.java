package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.storedvalue.StoredValuePlanRecharges;
import ai.neargo.shop.spi.storedvalue.StoredValuePort;
import ai.neargo.shop.pay.storedvalue.service.StoredValuePlanRechargeService;
import ai.neargo.shop.pay.storedvalue.service.StoredValueRechargeService;
import ai.neargo.shop.pay.storedvalue.service.StoredValueMerchantQueryService;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.storedvalue.service.StoredValuePlanService;
import ai.neargo.shop.pay.storedvalue.service.StoredValueCreditService;
import ai.neargo.shop.pay.storedvalue.service.StoredValueQueryService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.BalanceQuery;
import ai.neargo.shop.spi.storedvalue.StoredValuePlans.Create;
import ai.neargo.shop.spi.storedvalue.StoredValuePlans.Status;
import ai.neargo.shop.support.StoredValueCreditFixture;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"shop.pay.stored-value.enabled=true",
        "shop.pay.entitlement.cursor-key=store-plan-cursor-key-at-least-thirty-two-characters",
        "spring.datasource.url=jdbc:h2:mem:sv-store-plans;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class StoredValueStorePlanFlowTest {
    @Autowired StoredValuePlanService plans;
    @Autowired StoredValueCreditService credits;
    @Autowired StoredValuePlanRechargeService direct;
    @Autowired StoredValueRechargeService receipts;
    @Autowired StoredValueQueryService accounts;
    @Autowired StoredValueMerchantQueryService reads;
    @Autowired JdbcTemplate jdbc;
    @Autowired ai.neargo.shop.user.mapper.UserMappers.UserMapper users;
    @Autowired ObjectMapper json;

    @Test void multipleStorePlansHaveImmutableAmountsAndIdempotentStatusCommands() {
        String entity = id(), store = id();
        var scope = new CatalogScope("MAIN", entity, "OWNER", false);
        var c = new Create(store, "充50送10", "CNY", 5000, 1000);
        String key = id(); var first = plans.create(scope, c, key);
        assertThat(plans.create(scope, c, key)).isEqualTo(first);
        plans.create(scope, new Create(store, "充100送30", "CNY", 10000, 3000), id());
        assertThat(plans.list(scope, store, null, null, 20).records()).hasSize(2);
        assertThatThrownBy(() -> plans.create(scope, new Create(store, "充50送10", "CNY", 6000, 0), key))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> plans.get(scope, id(), first.planNo())).isInstanceOf(BizException.class);
        String statusKey = id();
        var stopped = plans.status(scope, first.planNo(), new Status(store, "INACTIVE", first.revision()), statusKey);
        assertThat(stopped.status()).isEqualTo("INACTIVE");
        assertThat(plans.status(scope, first.planNo(), new Status(store, "INACTIVE", first.revision()), statusKey))
                .isEqualTo(stopped);
        assertThatThrownBy(() -> plans.status(scope, first.planNo(), new Status(store, "ACTIVE", first.revision()), id()))
                .isInstanceOf(BizException.class);
        assertThat(plans.get(scope, store, first.planNo()).totalAmountMinor()).isEqualTo(6000);
    }

    @Test void statusChangeExposesAuditTimeAndPersistsActualModifier() {
        String entity=id(),store=id();
        var owner=new CatalogScope("MAIN",entity,"CREATOR",false);
        var created=plans.create(owner,new Create(store,"审计测试","CNY",5000,1000),id());
        var initial=json.readTree(json.writeValueAsString(created));
        assertThat(initial.path("updatedAt").isMissingNode()).isFalse();
        var editor=new CatalogScope("MAIN",entity,"EDITOR",false);
        String key=id();
        var stopped=plans.status(editor,created.planNo(),new Status(store,"INACTIVE",created.revision()),key);
        var changed=json.readTree(json.writeValueAsString(stopped));
        assertThat(changed.path("updatedAt").asText()).isNotBlank();
        assertThat(stopped.createdAt()).isEqualTo(created.createdAt());
        assertThat(jdbc.queryForObject("SELECT created_by FROM sv_recharge_plan WHERE plan_no=?",String.class,created.planNo())).isEqualTo("CREATOR");
        assertThat(jdbc.queryForObject("SELECT updated_by FROM sv_recharge_plan WHERE plan_no=?",String.class,created.planNo())).isEqualTo("EDITOR");
        assertThat(plans.status(editor,created.planNo(),new Status(store,"INACTIVE",created.revision()),key)).isEqualTo(stopped);
    }

    @Test void sameCustomerCreditsAccumulateOnlyInsideTheOriginalStore() {
        String user = id(), entity = id(), a = id(), b = id();
        var context = new EntitlementContext("MAIN", "WORKER", id(), "0".repeat(64));
        credits.credit(context, StoredValueCreditFixture.received(jdbc, json, user, entity, a, 5000, 1000));
        credits.credit(context, StoredValueCreditFixture.received(jdbc, json, user, entity, a, 10000, 3000));
        credits.credit(context, StoredValueCreditFixture.received(jdbc, json, user, entity, b, 20000, 5000));
        var first = accounts.balance(context, new BalanceQuery(user, entity, "CNY", a));
        var second = accounts.balance(context, new BalanceQuery(user, entity, "CNY", b));
        assertThat(first.availableMinor()).isEqualTo(19000);
        assertThat(second.availableMinor()).isEqualTo(25000);
        assertThat(first.accountNo()).isNotEqualTo(second.accountNo());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_account WHERE user_no=?", Integer.class, user))
                .isEqualTo(2);
    }

    @Test void directRechargeFreezesPlanAndPostsExactlyOnceAfterReceipt() {
        String entity=id(), store=id(), user=id();
        var scope=new CatalogScope("MAIN",entity,"OWNER",false);
        var plan=plans.create(scope,new Create(store,"独立充100送30","CNY",10000,3000),id());
        var ctx=new EntitlementContext("MAIN","OWNER",id(),"0".repeat(64));
        var command=new StoredValuePlanRecharges.Create(user,store,"测试门店",plan.planNo(),plan.versionNo(),"e".repeat(64));
        var first=direct.create(ctx,entity,command);
        assertThat(direct.create(ctx,entity,command).rechargeNo()).isEqualTo(first.rechargeNo());
        assertThatThrownBy(()->direct.create(ctx,entity,new StoredValuePlanRecharges.Create(user,id(),"其他门店",plan.planNo(),plan.versionNo(),"e".repeat(64))))
                .isInstanceOf(BizException.class);
        var submitCtx=new EntitlementContext("MAIN","OWNER",id(),"0".repeat(64));
        var submitted=receipts.submit(submitCtx,entity,new StoredValuePort.SubmitRecharge(first.rechargeNo(),first.revision(),10000,
                StoredValuePort.CollectionMethod.CASH,"PRIVATE-RECEIPT-"+id(),"d".repeat(64)));
        var received=receipts.receive(new EntitlementContext("MAIN","OWNER",id(),"0".repeat(64)),entity,
                new StoredValuePort.ReceiveRecharge(first.rechargeNo(),submitted.revision(),"d".repeat(64),"已核实"));
        assertThat(received.status().name()).isEqualTo("RECEIVED");
        assertThat(direct.recover(ctx,entity,first.rechargeNo())).isTrue();
        assertThat(direct.recover(ctx,entity,first.rechargeNo())).isTrue();
        assertThat(accounts.balance(ctx,new BalanceQuery(user,entity,"CNY",store)).availableMinor()).isEqualTo(13000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_voucher WHERE biz_no=?",Integer.class,first.rechargeNo())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT activity_no FROM sv_recharge WHERE recharge_no=?",String.class,first.rechargeNo())).isNull();
    }

    @Test void storeDirectoryAndHistoryUseCommittedVouchersAndFrozenBalances() {
        String user=id(),entity=id(),store=id();
        var context=new EntitlementContext("MAIN","OWNER",id(),"0".repeat(64));
        var scope=new CatalogScope("MAIN",entity,"OWNER",false);
        var c=StoredValueCreditFixture.received(jdbc,json,user,entity,store,5000,1000);
        credits.credit(context,c);credits.credit(context,c);
        var directory=reads.directory(scope,store,null,"",null,20);
        assertThat(directory.records()).hasSize(1);
        var account=directory.records().getFirst();
        assertThat(account.availableMinor()).isEqualTo(6000);
        var history=reads.records(scope,store,"ALL",null,null,account.accountRef(),null,20);
        assertThat(history.records()).hasSize(1);
        assertThat(history.records().getFirst().signedAmountMinor()).isEqualTo(6000);
        assertThat(history.records().getFirst().balanceAfterMinor()).isEqualTo(6000);
        assertThat(reads.records(scope,store,"REDEMPTION",null,null,null,null,20).records()).isEmpty();
        assertThatThrownBy(()->reads.account(scope,id(),account.accountRef())).isInstanceOf(BizException.class);
    }

    @Test void unknownCreateCanBeResolvedAndCancelledWithoutLateMoneyCreation() {
        String entity=id(),store=id(),user=id();var scope=new CatalogScope("MAIN",entity,"OWNER",false);
        var plan=plans.create(scope,new Create(store,"恢复测试","CNY",5000,1000),id());
        var ctx=new EntitlementContext("MAIN","OWNER",id(),"0".repeat(64));
        var replay=new StoredValuePlanRecharges.Replay(store,plan.planNo(),plan.versionNo(),"e".repeat(64));
        assertThat(direct.replay(ctx,entity,replay).found()).isFalse();
        assertThat(direct.cancelCreation(ctx,entity,replay).status().name()).isEqualTo("CANCELLED");
        var cancelled=direct.create(ctx,entity,new StoredValuePlanRecharges.Create(user,store,"本店",plan.planNo(),plan.versionNo(),"e".repeat(64)));
        assertThat(cancelled.status().name()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_recharge WHERE recharge_no=?",Integer.class,cancelled.rechargeNo())).isZero();
    }

    @Test void customerSearchIsBoundedToStoreAccountCandidatesAndMasksNames() {
        String entity=id(),store=id(),inside=id(),outside=id();
        jdbc.update("INSERT INTO usr_account(user_no,nickname,phone,tenant_no,created_at,updated_at) VALUES (?,?,?,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",inside,"同名顾客","13812345678");
        jdbc.update("INSERT INTO usr_account(user_no,nickname,phone,tenant_no,created_at,updated_at) VALUES (?,?,?,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",outside,"同名外店","13912345678");
        var ownership=org.mockito.Mockito.mock(ai.neargo.shop.spi.user.MerchantScopeFactsPort.class);
        org.mockito.Mockito.when(ownership.storesOwned("MAIN",entity,java.util.Set.of(store))).thenReturn(true);
        var port=new ai.neargo.shop.user.port.StoredValueCustomerProfilePortImpl(users,ownership);
        assertThat(port.search("MAIN",entity,store,"同名",java.util.List.of(inside))).containsExactly(inside);
        assertThat(port.search("MAIN",entity,store,"5678",java.util.List.of(inside))).containsExactly(inside);
        var profile=port.profiles("MAIN",entity,store,java.util.List.of(inside)).get(inside);
        assertThat(profile.maskedName()).isEqualTo("同**");
        assertThat(profile.maskedPhone()).isEqualTo("138****5678");
        assertThat(port.search("MAIN",entity,store,"同名",java.util.List.of())).isEmpty();
    }

    private static String id() { return UUID.randomUUID().toString(); }
}
