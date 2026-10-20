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
    @Autowired ai.neargo.shop.spi.user.PersonPort people;

    @Test void offlineConfirmationUsesFrozenAmountAndReplaysWithoutAnotherRecharge() {
        String entity=id(),store=id(),user=id();
        var plan=plans.create(new CatalogScope("MAIN",entity,"OWNER",false),new Create(store,"弹框充100送30","CNY",10000,3000),id());
        var ctx=new EntitlementContext("MAIN","OWNER",id(),"0".repeat(64));
        var command=new StoredValuePlanRecharges.Create(user,store,"测试门店",plan.planNo(),plan.versionNo(),"f".repeat(64));
        var first=direct.confirmOffline(ctx,entity,command);
        assertThat(first.status().name()).isEqualTo("CREDITED");
        assertThat(ai.neargo.shop.numbering.DocumentNumberFormatter.isValid(
            ai.neargo.shop.numbering.DocumentType.SV_RECHARGE,first.documentNo())).isTrue();
        assertThat(direct.query(ctx,entity,first.rechargeNo()).documentNo()).isEqualTo(first.documentNo());
        assertThat(accounts.balance(ctx,new BalanceQuery(user,entity,"CNY",store)).availableMinor()).isEqualTo(13000);
        var repeated=direct.confirmOffline(ctx,entity,command);
        assertThat(repeated.rechargeNo()).isEqualTo(first.rechargeNo());
        assertThat(repeated.documentNo()).isEqualTo(first.documentNo());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_document_number WHERE source_no=?",Integer.class,first.rechargeNo())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT collection_method FROM sv_recharge WHERE recharge_no=?",String.class,first.rechargeNo())).isEqualTo("MERCHANT_OFFLINE");
        assertThat(jdbc.queryForObject("SELECT actual_amount_minor FROM sv_recharge WHERE recharge_no=?",Long.class,first.rechargeNo())).isEqualTo(10000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_recharge WHERE user_no=?",Integer.class,user)).isEqualTo(1);
        direct.recover(ctx,entity,first.rechargeNo());
        assertThat(accounts.balance(ctx,new BalanceQuery(user,entity,"CNY",store)).availableMinor()).isEqualTo(13000);
    }

    @Test void offlinePostingFailureRollsBackTheWholeCommand() {
        String entity=id(),store=id(),user=id();
        var ctx=new EntitlementContext("MAIN","OWNER",id(),"0".repeat(64));
        credits.credit(ctx,StoredValueCreditFixture.received(jdbc,json,user,entity,store,Long.MAX_VALUE-5000,0));
        var plan=plans.create(new CatalogScope("MAIN",entity,"OWNER",false),new Create(store,"同步入账边界","CNY",10000,3000),id());
        var command=new StoredValuePlanRecharges.Create(user,store,"测试门店",plan.planNo(),plan.versionNo(),"d".repeat(64));
        assertThatThrownBy(()->direct.confirmOffline(ctx,entity,command)).isInstanceOf(BizException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_recharge WHERE user_no=? AND source_kind='PLAN'",Integer.class,user)).isZero();
        String failedNo=ai.neargo.shop.pay.storedvalue.policy.StoredValueFacts.no("PR",java.util.List.of(ctx.tenantNo(),entity,ctx.actorNo(),ctx.requestId()),json);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_document_number WHERE source_no=?",Integer.class,failedNo)).isZero();
        assertThat(accounts.balance(ctx,new BalanceQuery(user,entity,"CNY",store)).availableMinor()).isEqualTo(Long.MAX_VALUE-5000);
    }

    @Test void unregisteredCustomerCreditsWithoutFakeUserAndClaimsSameAccountAfterRegistration() {
        String entity=id(),store=id(),person=id(),user=id();
        var plan=plans.create(new CatalogScope("MAIN",entity,"OWNER",false),new Create(store,"线下会员","CNY",5000,1000),id());
        var ctx=new EntitlementContext("MAIN","OWNER",id(),"0".repeat(64));
        var command=new StoredValuePlanRecharges.Create(null,store,"测试门店",plan.planNo(),plan.versionNo(),"a".repeat(64),person);
        var recharge=direct.confirmOffline(ctx,entity,command);direct.recover(ctx,entity,recharge.rechargeNo());
        String account=jdbc.queryForObject("SELECT account_no FROM sv_account WHERE customer_no=?",String.class,person);
        assertThat(jdbc.queryForObject("SELECT user_no FROM sv_account WHERE account_no=?",String.class,account)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usr_account WHERE user_no=?",Integer.class,person)).isZero();
        direct.bindCustomer(ctx,person,user);direct.bindCustomer(ctx,person,user);
        var balance=accounts.balance(ctx,new BalanceQuery(user,entity,"CNY",store));
        assertThat(balance.accountNo()).isEqualTo(account);assertThat(balance.availableMinor()).isEqualTo(6000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_account WHERE customer_no=?",Integer.class,person)).isEqualTo(1);
        assertThatThrownBy(()->direct.bindCustomer(ctx,person,id())).isInstanceOf(BizException.class);
    }

    @Test void registeredLegacyAccountIsReusedAfterCanonicalIdentityBinding() {
        String entity=id(),store=id(),user=id(),person=id();
        var ctx=new EntitlementContext("MAIN","OWNER",id(),"0".repeat(64));
        credits.credit(ctx,StoredValueCreditFixture.received(jdbc,json,user,entity,store,5000,1000));
        var before=accounts.balance(ctx,new BalanceQuery(user,entity,"CNY",store));
        direct.bindCustomer(ctx,person,user);
        var plan=plans.create(new CatalogScope("MAIN",entity,"OWNER",false),new Create(store,"历史账户续充","CNY",10000,3000),id());
        var command=new StoredValuePlanRecharges.Create(user,store,"本店",plan.planNo(),plan.versionNo(),"b".repeat(64),person);
        var recharge=direct.confirmOffline(new EntitlementContext("MAIN","OWNER",id(),"0".repeat(64)),entity,command);
        direct.recover(ctx,entity,recharge.rechargeNo());
        var after=accounts.balance(ctx,new BalanceQuery(user,entity,"CNY",store));
        assertThat(after.accountNo()).isEqualTo(before.accountNo());
        assertThat(after.availableMinor()).isEqualTo(19000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_account WHERE user_no=?",Integer.class,user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT customer_no FROM sv_account WHERE account_no=?",String.class,before.accountNo())).isEqualTo(person);
    }

    @Test void merchantDirectoryPagesByLastRechargeEvenWhenConsumptionIsNewer() {
        String entity=id(),store=id(),older=id(),newer=id();
        var ctx=new EntitlementContext("MAIN","OWNER",id(),"0".repeat(64));
        credits.credit(ctx,StoredValueCreditFixture.received(jdbc,json,older,entity,store,5000,1000));
        credits.credit(ctx,StoredValueCreditFixture.received(jdbc,json,newer,entity,store,10000,3000));
        jdbc.update("UPDATE sv_user_store_index SET last_recharge_at='2026-10-19 10:00:00',last_activity_at='2026-10-20 18:00:00' WHERE user_no=?",older);
        jdbc.update("UPDATE sv_user_store_index SET last_recharge_at='2026-10-20 10:00:00',last_activity_at='2026-10-20 10:00:00' WHERE user_no=?",newer);
        var scope=new CatalogScope("MAIN",entity,"OWNER",false);
        var first=reads.directory(scope,store,null,"",null,1);
        assertThat(first.records().getFirst().userNo()).isEqualTo(newer);
        assertThat(first.nextCursor()).isNotBlank();
        var second=reads.directory(scope,store,null,"",first.nextCursor(),1);
        assertThat(second.records().getFirst().userNo()).isEqualTo(older);
        assertThat(second.nextCursor()).isNull();
    }

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
        var port=new ai.neargo.shop.user.port.StoredValueCustomerProfilePortImpl(users,ownership,people);
        assertThat(port.search("MAIN",entity,store,"同名",java.util.List.of(inside))).containsExactly(inside);
        assertThat(port.search("MAIN",entity,store,"5678",java.util.List.of(inside))).containsExactly(inside);
        var profile=port.profiles("MAIN",entity,store,java.util.List.of(inside)).get(inside);
        assertThat(profile.maskedName()).isEqualTo("同**");
        assertThat(profile.maskedPhone()).isEqualTo("138****5678");
        assertThat(port.search("MAIN",entity,store,"同名",java.util.List.of())).isEmpty();
    }

    private static String id() { return UUID.randomUUID().toString(); }
}
