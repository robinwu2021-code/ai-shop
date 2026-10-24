package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.storedvalue.issuer.StoredValueIssuerService;
import ai.neargo.shop.pay.storedvalue.service.*;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.*;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

/** Real Pay receipt/posting transactions, using an isolated database; no external collection. */
@SpringBootTest(properties={"shop.pay.stored-value.enabled=true","shop.pay.stored-value.issuer-mode=true",
    "shop.pay.entitlement.cursor-key=issuer-ledger-cursor-key-at-least-thirty-two-characters",
    "spring.datasource.url=jdbc:h2:mem:issuer-ledger;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class StoredValueIssuerLedgerFlowTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired StoredValueIssuerService issuers;
    @Autowired StoredValuePlanService plans;
    @Autowired StoredValuePlanRechargeService direct;
    @Autowired StoredValueRechargeService receipts;
    @Autowired StoredValueRechargeWriter writer;
    @Autowired StoredValueRedemptionPostingService redemption;
    @Autowired StoredValueQueryService queries;
    @BeforeEach void generatedSchemaIsReady() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema='public' AND table_name='sv_issuer'", Long.class))
                .as("Generated schema includes the original issuer table")
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema='public' AND table_name='sv_release' "
                + "AND column_name='legacy_activity_version_no'", Long.class))
                .as("Generated schema includes the V550 release provenance column")
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema='public' AND table_name='sv_release_tier' "
                + "AND column_name='legacy_tier_key'", Long.class))
                .as("Generated schema includes the V550 tier provenance column")
                .isEqualTo(1L);
    }
    record Fixture(String entity,String store,String issuer,EntitlementContext actor,StoredValuePlanRecharges.Create command) { }
    Fixture fixture() {
        String entity=id(),store=id(),user=id();
        var route=new Route(null,entity,store,"MERCHANT_OFFLINE","OFFLINE");
        var scope=new AuthorizedScope("MAIN","OWNER","TEST-SPACE","STORE",store,1,entity,Set.of(entity),List.of(route));
        var issuer=issuers.configure(scope,new Config("单店发行",entity,"STORE_LOCAL","INTERNAL_RECON","LEGACY_SNAPSHOT","CNY","Asia/Shanghai",List.of(route),0),id());
        // Test-only activation, never applied to an online issuer.
        jdbc.update("UPDATE sv_issuer SET status='ACTIVE',write_mode='ISSUER' WHERE issuer_no=?",issuer.issuerNo());
        var plan=plans.create(new CatalogScope("MAIN",entity,"OWNER",false),new StoredValuePlans.Create(store,"充50送10","CNY",5000,1000),id());
        return new Fixture(entity,store,issuer.issuerNo(),ctx(),new StoredValuePlanRecharges.Create(user,store,"本店",plan.planNo(),plan.versionNo(),"a".repeat(64)));
    }
    @Test void synchronousRechargeFreezesIssuerAndCommitsQuotaWithLedgerOnce() {
        var f=fixture();var first=direct.confirmOffline(f.actor(),f.entity(),f.command());
        assertThat(first.status().name()).isEqualTo("CREDITED");
        assertThat(jdbc.queryForObject("SELECT issuer_no FROM sv_recharge WHERE recharge_no=?",String.class,first.rechargeNo())).isEqualTo(f.issuer());
        assertThat(jdbc.queryForObject("SELECT issuer_no FROM sv_account WHERE store_no=?",String.class,f.store())).isEqualTo(f.issuer());
        assertThat(jdbc.queryForObject("SELECT outstanding_minor FROM sv_issue_quota WHERE store_no=?",Long.class,f.store())).isEqualTo(6000);
        assertThat(jdbc.queryForObject("SELECT reserved_minor FROM sv_issue_quota WHERE store_no=?",Long.class,f.store())).isZero();
        direct.confirmOffline(f.actor(),f.entity(),f.command());direct.recover(f.actor(),f.entity(),first.rechargeNo());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_voucher WHERE biz_no=?",Long.class,first.rechargeNo())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_release WHERE issuer_no=?",Long.class,f.issuer())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_release_tier t JOIN sv_release r ON r.release_no=t.release_no WHERE r.issuer_no=?",Long.class,f.issuer())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT outstanding_minor FROM sv_issue_quota WHERE store_no=?",Long.class,f.store())).isEqualTo(6000);
        assertThat(jdbc.queryForObject("SELECT issuer_no FROM sv_lot WHERE recharge_no=?",String.class,first.rechargeNo())).isEqualTo(f.issuer());
        assertThat(jdbc.queryForObject("SELECT has_recharge FROM sv_customer_issuer_index WHERE issuer_no=?",Integer.class,f.issuer())).isEqualTo(1);
    }
    @Test void receivedOriginalRecoversAfterNewIssuanceIsStopped() {
        var f=fixture();var created=direct.create(f.actor(),f.entity(),f.command());
        assertThat(jdbc.queryForObject("SELECT reserved_minor FROM sv_issue_quota WHERE store_no=?",Long.class,f.store())).isEqualTo(6000);
        var submitted=receipts.submit(ctx(),f.entity(),new StoredValuePort.SubmitRecharge(created.rechargeNo(),created.revision(),5000,StoredValuePort.CollectionMethod.MERCHANT_OFFLINE,id(),"b".repeat(64)));
        receipts.receive(ctx(),f.entity(),new StoredValuePort.ReceiveRecharge(created.rechargeNo(),submitted.revision(),"b".repeat(64),"测试收款事实"));
        jdbc.update("UPDATE sv_issuer SET status='ISSUE_STOPPED' WHERE issuer_no=?",f.issuer());
        direct.recover(ctx(),f.entity(),created.rechargeNo());direct.recover(ctx(),f.entity(),created.rechargeNo());
        assertThat(jdbc.queryForObject("SELECT status FROM sv_recharge WHERE recharge_no=?",String.class,created.rechargeNo())).isEqualTo("CREDITED");
        assertThat(jdbc.queryForObject("SELECT outstanding_minor FROM sv_issue_quota WHERE store_no=?",Long.class,f.store())).isEqualTo(6000);
        assertThat(jdbc.queryForObject("SELECT reserved_minor FROM sv_issue_quota WHERE store_no=?",Long.class,f.store())).isZero();
    }
    @Test void missingMappedQuotaFailsClosedInsteadOfStartingAtZero() {
        var f=fixture();direct.confirmOffline(f.actor(),f.entity(),f.command());
        jdbc.update("DELETE FROM sv_issue_quota WHERE store_no=?",f.store());
        assertThatThrownBy(()->direct.confirmOffline(ctx(),f.entity(),f.command())).isInstanceOf(BizException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_recharge WHERE store_no=?",Long.class,f.store())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_issue_quota WHERE store_no=?",Long.class,f.store())).isZero();
    }
    ai.neargo.shop.pay.entitlement.dto.UseSnapshots.Prepared prepared(Fixture f,long amount) {
        var account=jdbc.queryForMap("SELECT * FROM sv_account WHERE store_no=?",f.store());
        var source=new ai.neargo.shop.spi.entitlement.SourceRef("CORE","SERVICE_USE",id(),null,null,null,0);
        var item=new ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseItem(id(),id(),"护理",1,amount);
        var items=List.of(item);var expires=java.time.Instant.now().plusSeconds(600);String auth=id(),hash="c".repeat(64);
        var c=new ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseCommand(id(),source,f.command().userNo(),f.entity(),f.store(),"CNY",
                ai.neargo.shop.spi.entitlement.EntitlementUsePort.AssetKind.STORED_VALUE,null,account.get("account_no").toString(),java.time.LocalDate.now(),null,amount,items,auth,null,((Number)account.get("version")).longValue(),null,null);
        var fact=new ai.neargo.shop.spi.entitlement.EntitlementUseSourcePort.UseSourceFact("MAIN",source,c.userNo(),f.entity(),f.store(),"CNY",items,hash,"本店",null,java.time.LocalDate.now());
        var authorization=new ai.neargo.shop.spi.entitlement.SourceDecisionPort.AuthorizationDecision(auth,ai.neargo.shop.spi.entitlement.SourceDecisionPort.AuthorizationState.AUTHORIZED,
                source,c.userNo(),f.entity(),f.store(),"CNY",amount,hash,hash,expires);
        return new ai.neargo.shop.pay.entitlement.dto.UseSnapshots.Prepared(c,fact,null,authorization,hash,java.time.LocalDate.now(),0,((Number)account.get("version")).longValue(),List.of());
    }
    void finish(Fixture f,ai.neargo.shop.pay.entitlement.dto.UseSnapshots.Prepared p,boolean commit,String decisionNo) {
        var c=p.command();var d=new ai.neargo.shop.spi.entitlement.DecisionRef(decisionNo,java.time.Instant.now(),0,commit?
                ai.neargo.shop.spi.entitlement.DecisionRef.DecisionType.USE_COMMIT:ai.neargo.shop.spi.entitlement.DecisionRef.DecisionType.USE_CANCEL);
        var truth=new ai.neargo.shop.spi.entitlement.SourceDecisionPort.SourceDecision(c.sourceRef(),commit?
                ai.neargo.shop.spi.entitlement.SourceDecisionPort.State.COMMITTED:ai.neargo.shop.spi.entitlement.SourceDecisionPort.State.CANCELLED,
                d,c.userNo(),c.entityNo(),c.storeNo(),"CNY",c.amountMinor(),"c".repeat(64));
        redemption.finish(ctx(),c.operationNo(),c.sourceRef(),d,commit,truth);
    }
    @Test void holdAndReleaseKeepOutstandingWhileCommittedUseDebitsOnce() {
        var f=fixture();direct.confirmOffline(f.actor(),f.entity(),f.command());
        var release=prepared(f,1000);redemption.prepare(ctx(),release);
        assertThat(jdbc.queryForObject("SELECT outstanding_minor FROM sv_issue_quota WHERE store_no=?",Long.class,f.store())).isEqualTo(6000);
        finish(f,release,false,id());
        assertThat(jdbc.queryForObject("SELECT outstanding_minor FROM sv_issue_quota WHERE store_no=?",Long.class,f.store())).isEqualTo(6000);
        var use=prepared(f,1000);redemption.prepare(ctx(),use);String decision=id();finish(f,use,true,decision);finish(f,use,true,decision);
        assertThat(jdbc.queryForObject("SELECT outstanding_minor FROM sv_issue_quota WHERE store_no=?",Long.class,f.store())).isEqualTo(5000);
        assertThat(jdbc.queryForObject("SELECT principal_minor+bonus_minor FROM sv_account WHERE store_no=?",Long.class,f.store())).isEqualTo(5000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_voucher WHERE biz_type='CONSUME' AND biz_no=?",Long.class,use.command().operationNo())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT issuer_no FROM sv_voucher WHERE biz_type='CONSUME' AND biz_no=?",String.class,use.command().operationNo())).isEqualTo(f.issuer());
    }
    @Test void cancelledOriginalReleasesReservedQuotaWithoutMoneyOrAnotherOriginal() {
        var f=fixture();var created=direct.create(f.actor(),f.entity(),f.command());
        var cancelled=receipts.cancel(ctx(),f.entity(),new StoredValuePort.CancelRecharge(created.rechargeNo(),created.revision(),"顾客取消"));
        assertThat(cancelled.status().name()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT reserved_minor FROM sv_issue_quota WHERE store_no=?",Long.class,f.store())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_account WHERE store_no=?",Long.class,f.store())).isZero();
        assertThat(direct.create(f.actor(),f.entity(),f.command()).rechargeNo()).isEqualTo(created.rechargeNo());
    }
    @Test void activatedStoreRejectsAnUnmappedOriginalBeforeRecordingCollection() {
        var f=fixture();var created=direct.create(f.actor(),f.entity(),f.command());
        jdbc.update("UPDATE sv_recharge SET issuer_no=NULL WHERE recharge_no=?",created.rechargeNo());
        assertThatThrownBy(()->receipts.submit(ctx(),f.entity(),new StoredValuePort.SubmitRecharge(created.rechargeNo(),created.revision(),5000,
                StoredValuePort.CollectionMethod.MERCHANT_OFFLINE,id(),"d".repeat(64)))).isInstanceOf(BizException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM sv_recharge WHERE recharge_no=?",String.class,created.rechargeNo())).isEqualTo("WAIT_COLLECTION");
    }
    @Test void insufficientOutstandingRollsBackTheWholeConsumptionAndKeepsHold() {
        var f=fixture();direct.confirmOffline(f.actor(),f.entity(),f.command());
        var use=prepared(f,1000);redemption.prepare(ctx(),use);
        jdbc.update("UPDATE sv_issue_quota SET outstanding_minor=1 WHERE store_no=?",f.store());
        assertThatThrownBy(()->finish(f,use,true,id())).isInstanceOf(BizException.class);
        assertThat(jdbc.queryForObject("SELECT principal_minor+bonus_minor FROM sv_account WHERE store_no=?",Long.class,f.store())).isEqualTo(6000);
        assertThat(jdbc.queryForObject("SELECT held_principal_minor+held_bonus_minor FROM sv_account WHERE store_no=?",Long.class,f.store())).isEqualTo(1000);
        assertThat(jdbc.queryForObject("SELECT status FROM ent_use_intent WHERE operation_no=?",String.class,use.command().operationNo())).isEqualTo("HELD");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_voucher WHERE biz_type='CONSUME' AND biz_no=?",Long.class,use.command().operationNo())).isZero();
    }
    @Test void unadaptedActivityCreationCannotBypassSwitchedStoreIssuer() {
        var f=fixture();var at=java.time.Instant.now();
        var source=new ai.neargo.shop.spi.entitlement.SourceRef("CORE","RECHARGE",id(),null,null,null,0);
        var permit=new ai.neargo.shop.spi.promotion.ActivityParticipationPort.ParticipationPermit(id(),source,"A1","AV1","T1",f.command().userNo(),f.entity(),f.store(),"CNY",5000L,1000L,List.of(),
                new ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope("STORES",List.of(f.store())),"b".repeat(64),at);
        var participation=new ai.neargo.shop.spi.promotion.ActivityParticipationPort.ReserveActivity(source,"A1","AV1","T1",f.command().userNo(),f.entity(),f.store(),"CNY",1);
        String rechargeNo="RC"+source.grantKey().substring(0,60);
        var fact=new RechargeSourcePort.SourceFact(f.actor(),source,rechargeNo,participation,permit,"本店",at,at.plusSeconds(600));
        assertThatThrownBy(()->writer.create(f.actor(),f.entity(),new StoredValuePort.CreateRecharge(source,permit),fact)).isInstanceOf(BizException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_recharge WHERE recharge_no=?",Long.class,rechargeNo)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_ent_command_dedup WHERE resource_no=?",Long.class,rechargeNo)).isZero();
    }
    @Test void persistentWriteFencePausesOriginalAndAccountWritesUntilReleased() {
        var f=fixture();var created=direct.create(f.actor(),f.entity(),f.command());
        var command=new StoredValuePort.SubmitRecharge(created.rechargeNo(),created.revision(),5000,StoredValuePort.CollectionMethod.MERCHANT_OFFLINE,id(),"d".repeat(64));var submitter=ctx();
        jdbc.update("UPDATE sv_issuer SET write_mode='FENCED' WHERE issuer_no=?",f.issuer());
        assertThatThrownBy(()->receipts.submit(submitter,f.entity(),command)).isInstanceOf(BizException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM sv_recharge WHERE recharge_no=?",String.class,created.rechargeNo())).isEqualTo("WAIT_COLLECTION");
        jdbc.update("UPDATE sv_issuer SET write_mode='ISSUER' WHERE issuer_no=?",f.issuer());
        assertThat(receipts.submit(submitter,f.entity(),command).status().name()).isEqualTo("SUBMITTED");
        var g=fixture();direct.confirmOffline(g.actor(),g.entity(),g.command());var use=prepared(g,1000);
        jdbc.update("UPDATE sv_issuer SET write_mode='FENCED' WHERE issuer_no=?",g.issuer());
        assertThatThrownBy(()->redemption.prepare(ctx(),use)).isInstanceOf(BizException.class);
        assertThat(jdbc.queryForObject("SELECT held_principal_minor+held_bonus_minor FROM sv_account WHERE store_no=?",Long.class,g.store())).isZero();
        jdbc.update("UPDATE sv_issuer SET write_mode='ISSUER' WHERE issuer_no=?",g.issuer());
        assertThat(redemption.prepare(ctx(),use).status().name()).isEqualTo("HELD");
    }
    static String id(){return UUID.randomUUID().toString();}
    static EntitlementContext ctx(){return new EntitlementContext("MAIN","OWNER",id(),"0".repeat(64));}
    record CanonicalWallet(String account,String customer) { }
    CanonicalWallet oldCanonicalWallet(Fixture f) {
        direct.create(f.actor(),f.entity(),f.command()); // Reserves the original before a verified legacy wallet is mapped.
        String account="historic-"+id(),customer="person-"+id();
        jdbc.update("INSERT INTO sv_customer_identity(tenant_no,customer_no,user_no) VALUES('MAIN',?,?)",customer,f.command().userNo());
        jdbc.update("INSERT INTO sv_account(tenant_no,account_no,customer_no,user_no,entity_no,store_no,currency,status,issuer_no,issuer_version_no,created_at,updated_at,created_by,updated_by) SELECT 'MAIN',?,?,NULL,?,?,'CNY','ACTIVE',issuer_no,current_issuer_version_no,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'LEGACY','LEGACY' FROM sv_issuer WHERE issuer_no=?",account,customer,f.entity(),f.store(),f.issuer());
        return new CanonicalWallet(account,customer);
    }
    @Test void canonicalAliasCreditsAndRedeemsTheOriginalWalletWithoutCreatingOrBindingAnother() {
        var f=fixture();var wallet=oldCanonicalWallet(f);
        var credited=direct.confirmOffline(f.actor(),f.entity(),f.command());
        assertThat(credited.status().name()).isEqualTo("CREDITED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_account WHERE issuer_no=?",Long.class,f.issuer())).isEqualTo(1);
        var balance=queries.balance(ctx(),new StoredValuePort.BalanceQuery(f.command().userNo(),f.entity(),"CNY",f.store()));
        assertThat(balance.accountNo()).isEqualTo(wallet.account());assertThat(balance.availableMinor()).isEqualTo(6000);
        var use=prepared(f,1000);redemption.prepare(ctx(),use);finish(f,use,true,id());
        assertThat(jdbc.queryForObject("SELECT principal_minor+bonus_minor FROM sv_account WHERE account_no=?",Long.class,wallet.account())).isEqualTo(5000);
        assertThat(jdbc.queryForObject("SELECT user_no FROM sv_account WHERE account_no=?",String.class,wallet.account())).isNull();
        assertThat(jdbc.queryForObject("SELECT outstanding_minor FROM sv_issue_quota WHERE store_no=?",Long.class,f.store())).isEqualTo(5000);
    }
    @Test void issuerBalanceFindsTheVerifiedCanonicalWalletBeforeAnyNewCredit() {
        var f=fixture();var wallet=oldCanonicalWallet(f);
        var balance=queries.balance(ctx(),new StoredValuePort.BalanceQuery(f.command().userNo(),f.entity(),"CNY",f.store()));
        assertThat(balance.accountNo()).isEqualTo(wallet.account());assertThat(balance.balanceMinor()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_account WHERE issuer_no=?",Long.class,f.issuer())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT user_no FROM sv_account WHERE account_no=?",String.class,wallet.account())).isNull();
    }

    @Test void mappedWalletAndLegacyAliasCollisionBlockCreditInsteadOfChoosingOne() {
        var f=fixture();oldCanonicalWallet(f);
        jdbc.update("INSERT INTO sv_account(tenant_no,account_no,customer_no,user_no,entity_no,store_no,currency,status,created_at,updated_at,created_by,updated_by) VALUES('MAIN',?,?,?,?,?,'CNY','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'LEGACY','LEGACY')","shadow-"+id(),f.command().userNo(),f.command().userNo(),f.entity(),f.store());
        assertThatThrownBy(()->direct.confirmOffline(f.actor(),f.entity(),f.command())).isInstanceOf(BizException.class);
        assertThat(jdbc.queryForObject("SELECT SUM(principal_minor+bonus_minor) FROM sv_account WHERE store_no=?",Long.class,f.store())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_account WHERE store_no=?",Long.class,f.store())).isEqualTo(2);
    }

    @Test void walletWithMissingFrozenIssuerVersionCannotBeReadOrCreditedAsValid() {
        var f=fixture();var wallet=oldCanonicalWallet(f);
        jdbc.update("UPDATE sv_account SET issuer_version_no='MISSING' WHERE account_no=?",wallet.account());
        assertThatThrownBy(()->queries.balance(ctx(),new StoredValuePort.BalanceQuery(f.command().userNo(),f.entity(),"CNY",f.store()))).isInstanceOf(BizException.class);
        assertThatThrownBy(()->direct.confirmOffline(f.actor(),f.entity(),f.command())).isInstanceOf(BizException.class);
        assertThat(jdbc.queryForObject("SELECT principal_minor+bonus_minor FROM sv_account WHERE account_no=?",Long.class,wallet.account())).isZero();
    }

    @Test void unregisteredCustomerReusesOneIssuerWalletWithoutCreatingAnIdentityBinding() {
        var f=fixture();String customer="person-"+id();
        var c=new StoredValuePlanRecharges.Create(null,f.store(),"本店",f.command().planNo(),f.command().versionNo(),f.command().referenceHash(),customer);
        direct.confirmOffline(f.actor(),f.entity(),c);direct.confirmOffline(ctx(),f.entity(),c);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_account WHERE issuer_no=? AND customer_no=?",Long.class,f.issuer(),customer)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT principal_minor+bonus_minor FROM sv_account WHERE issuer_no=? AND customer_no=?",Long.class,f.issuer(),customer)).isEqualTo(12000);
        assertThat(jdbc.queryForObject("SELECT user_no FROM sv_account WHERE issuer_no=? AND customer_no=?",String.class,f.issuer(),customer)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_customer_identity WHERE customer_no=?",Long.class,customer)).isZero();
    }


    @Test void adaptedSingleStoreActivityFreezesOneReleaseAndTierAcrossOriginalReplay() {
        var f=fixture();var at=java.time.Instant.now();String customer=id();
        var source=new ai.neargo.shop.spi.entitlement.SourceRef("CORE","RECHARGE",id(),null,null,null,0);
        var scope=new ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope("STORES",List.of(f.store()));
        String activity=id(),version=id(),tier=id();
        var permit=new ai.neargo.shop.spi.promotion.ActivityParticipationPort.ParticipationPermit(id(),source,activity,version,tier,f.command().userNo(),f.entity(),f.store(),"CNY",5000L,1000L,List.of(),scope,"b".repeat(64),at);
        var participation=new ai.neargo.shop.spi.promotion.ActivityParticipationPort.ReserveActivity(source,activity,version,tier,f.command().userNo(),f.entity(),f.store(),"CNY",1);
        String rechargeNo="RC"+source.grantKey().substring(0,60);
        var fact=new RechargeSourcePort.SourceFact(f.actor(),source,rechargeNo,participation,permit,"Store",at,at.plusSeconds(600),customer);
        var created=writer.create(f.actor(),f.entity(),new StoredValuePort.CreateRecharge(source,permit),fact);
        var replay=writer.create(f.actor(),f.entity(),new StoredValuePort.CreateRecharge(source,permit),fact);
        assertThat(replay.rechargeNo()).isEqualTo(created.rechargeNo());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_release WHERE legacy_activity_version_no=?",Long.class,version)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_release_tier t JOIN sv_release r ON r.release_no=t.release_no WHERE r.legacy_activity_version_no=?",Long.class,version)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT customer_no FROM sv_recharge WHERE recharge_no=?",String.class,rechargeNo)).isEqualTo(customer);
        assertThat(jdbc.queryForObject("SELECT reserved_minor FROM sv_issue_quota WHERE store_no=?",Long.class,f.store())).isEqualTo(6000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_account WHERE store_no=?",Long.class,f.store())).isZero();
        var submitted=writer.submit(new EntitlementContext("MAIN","OPERATOR",id(),"0".repeat(64)),f.entity(),new StoredValuePort.SubmitRecharge(rechargeNo,created.revision(),5000,StoredValuePort.CollectionMethod.CASH,id(),"a".repeat(64)));
        assertThat(submitted.status().name()).isEqualTo("SUBMITTED");
        var cancelled=writer.cancel(new EntitlementContext("MAIN","OPERATOR",id(),"0".repeat(64)),f.entity(),new StoredValuePort.CancelRecharge(rechargeNo,submitted.revision(),"test"),fact);
        assertThat(cancelled.status().name()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT reserved_minor FROM sv_issue_quota WHERE store_no=?",Long.class,f.store())).isZero();
    }

    @Test void simultaneousSameOriginalCreditsOneWalletAndOneQuotaCommit() throws Exception {
        var f=fixture();var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
        try{
            var first=pool.submit(()->{start.await();return direct.confirmOffline(f.actor(),f.entity(),f.command());});
            var second=pool.submit(()->{start.await();return direct.confirmOffline(f.actor(),f.entity(),f.command());});
            start.countDown();var a=first.get(20,java.util.concurrent.TimeUnit.SECONDS);var b=second.get(20,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(a.rechargeNo()).isEqualTo(b.rechargeNo());assertThat(a.status().name()).isEqualTo("CREDITED");assertThat(b.status().name()).isEqualTo("CREDITED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_account WHERE store_no=?",Long.class,f.store())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_voucher WHERE biz_no=?",Long.class,a.rechargeNo())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT outstanding_minor FROM sv_issue_quota WHERE store_no=?",Long.class,f.store())).isEqualTo(6000);
            assertThat(jdbc.queryForObject("SELECT reserved_minor FROM sv_issue_quota WHERE store_no=?",Long.class,f.store())).isZero();
        }finally{pool.shutdownNow();}
    }
}
