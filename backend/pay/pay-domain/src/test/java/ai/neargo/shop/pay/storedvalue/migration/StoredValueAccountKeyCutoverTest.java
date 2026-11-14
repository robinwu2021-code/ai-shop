package ai.neargo.shop.pay.storedvalue.migration;

import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.storedvalue.migration.StoredValueAccountKeyPlan.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StoredValueAccountKeyCutoverTest {
    record Fixture(StoredValueSchemaAuthorizationTest.Fixture source,StoredValueSchemaAuthorization auth,
        StoredValueAccountKeyInspector inspector,StoredValueSchemaDdl ddl,AtomicReference<List<Index>> keys,
        AtomicInteger executions,AtomicReference<String> failure){}
    Fixture fixture() throws Exception {
        var helper=new StoredValueSchemaAuthorizationTest();var f=helper.fixture();
        var keys=new AtomicReference<List<Index>>(new ArrayList<>(List.of(
            new Index("PRIMARY",true,List.of("id"),false),
            new Index("uk_sv_account",true,List.of("tenant_no","account_no"),false),
            new Index("uk_sv_owner",true,List.of("tenant_no","user_no","entity_no","store_no","currency"),false),
            new Index("uk_sv_customer_owner",true,List.of("tenant_no","customer_no","entity_no","store_no","currency"),false))));
        var inspector=mock(StoredValueAccountKeyInspector.class);
        when(inspector.inspect()).thenAnswer(i->StoredValueAccountKeyPlan.evaluate(new Snapshot(keys.get(),1,0,0,0,0)));
        var ddl=mock(StoredValueSchemaDdl.class);var executions=new AtomicInteger();var failure=new AtomicReference<String>();
        when(ddl.locked(any())).thenAnswer(call->{
            Function<Consumer<String>,Object> action=call.getArgument(0);
            return action.apply(sql->{
                executions.incrementAndGet();
                String failed=failure.getAndSet(null);
                if("BEFORE".equals(failed))throw new IllegalStateException("connection unavailable");
                var changed=new ArrayList<>(keys.get());
                if(sql.contains("ADD UNIQUE KEY"))changed.add(new Index("uk_sv_issuer_customer",true,List.of("tenant_no","issuer_no","customer_no","currency"),false));
                else changed.removeIf(k->k.name().equals("uk_sv_owner")||k.name().equals("uk_sv_customer_owner"));
                keys.set(changed);
                if("AFTER".equals(failed))throw new IllegalStateException("response lost after auto-commit");
            });
        });
        return new Fixture(f,helper.service(f,f.approval(),true),inspector,ddl,keys,executions,failure);
    }
    StoredValueAccountKeyCutover runner(Fixture f){return new StoredValueAccountKeyCutover(f.source().f().data().db(),f.auth(),f.inspector(),f.ddl());}
    @Test void eachSchemaStepHasDurableIntentAndReplayNeverRepeatsDdl() throws Exception {
        var f=fixture();var r=runner(f);
        assertThat(r.step("OPERATOR","GLOBAL1",f.source().proof()).state()).isEqualTo("APPLIED");
        assertThat(f.executions()).hasValue(1);
        assertThat(runner(f).step("OPERATOR","GLOBAL1",f.source().proof()).state()).isEqualTo("APPLIED");
        assertThat(runner(f).step("OPERATOR","GLOBAL1",f.source().proof()).state()).isEqualTo("KEYS_READY");
        assertThat(f.executions()).hasValue(2);
        assertThat(f.source().f().data().jdbc().queryForObject("SELECT COUNT(*) FROM pay_ent_command_dedup WHERE operation_kind='SV_SCHEMA_KEY_STEP' AND result_status='COMPLETED'",Long.class)).isEqualTo(2);
        assertThat(f.source().f().data().jdbc().queryForObject("SELECT write_mode FROM sv_issuer",String.class)).isEqualTo("FENCED");
    }
    @Test void lostResponseAfterDdlCommitIsRecoveredFromSchemaWithoutRepeatingDdl() throws Exception {
        var f=fixture();f.failure().set("AFTER");
        assertThatThrownBy(()->runner(f).step("OPERATOR","GLOBAL1",f.source().proof())).isInstanceOf(IllegalStateException.class);
        assertThat(f.source().f().data().jdbc().queryForObject("SELECT COUNT(*) FROM pay_ent_command_dedup WHERE operation_kind='SV_SCHEMA_KEY_STEP' AND result_status='PENDING'",Long.class)).isEqualTo(1);
        assertThat(runner(f).step("OPERATOR","GLOBAL1",f.source().proof()).state()).isEqualTo("RECOVERED");
        assertThat(f.executions()).hasValue(1);
    }
    @Test void failureBeforeDdlLeavesRetryableIntentAndTransactionsCannotEncloseDdl() throws Exception {
        var f=fixture();f.failure().set("BEFORE");
        assertThatThrownBy(()->runner(f).step("OPERATOR","GLOBAL1",f.source().proof())).isInstanceOf(IllegalStateException.class);
        assertThat(runner(f).step("OPERATOR","GLOBAL1",f.source().proof()).state()).isEqualTo("APPLIED");
        assertThat(f.executions()).hasValue(2);
        assertThatThrownBy(()->f.source().f().data().db().write(()->runner(f).step("OPERATOR","GLOBAL1",f.source().proof()))).isInstanceOf(BizException.class);
        assertThat(f.executions()).hasValue(2);
    }
    @Test void alteredSchemaCannotBeMistakenForACommittedPendingStep() throws Exception {
        var f=fixture();f.failure().set("BEFORE");
        assertThatThrownBy(()->runner(f).step("OPERATOR","GLOBAL1",f.source().proof())).isInstanceOf(IllegalStateException.class);
        var altered=new ArrayList<>(f.keys().get());altered.add(new Index("ix_extra",false,List.of("currency"),false));f.keys().set(altered);
        assertThatThrownBy(()->runner(f).step("OPERATOR","GLOBAL1",f.source().proof())).isInstanceOf(BizException.class);
        assertThat(f.executions()).hasValue(1);
    }

    @Test void approvalExpiringAfterCommittedDdlLeavesIntentAndCanBeExplicitlyRenewed() throws Exception{
        var f=fixture();var active=new AtomicReference<>(f.source().approval());
        var source=f.source();
        var authorization=new StoredValueSchemaAuthorization(source.f().data().db(),source.f().service(),source.execution(),true,active::get,java.time.Clock.systemUTC());
        var ddl=mock(StoredValueSchemaDdl.class);
        when(ddl.locked(any())).thenAnswer(call->{
            Function<Consumer<String>,Object> action=call.getArgument(0);
            return action.apply(sql->{
                f.executions().incrementAndGet();
                var changed=new ArrayList<>(f.keys().get());changed.add(new Index("uk_sv_issuer_customer",true,List.of("tenant_no","issuer_no","customer_no","currency"),false));f.keys().set(changed);
                var a=active.get();active.set(new StoredValueSchemaAuthorization.Approval(a.approvalNo(),a.operatorNo(),a.ledgerFingerprint(),a.inventoryHash(),java.time.Instant.now().minusSeconds(1),true,a.receipts()));
            });
        });
        var r=new StoredValueAccountKeyCutover(source.f().data().db(),authorization,f.inspector(),ddl);
        assertThatThrownBy(()->r.step("OPERATOR","GLOBAL1",source.proof())).isInstanceOf(BizException.class);
        assertThat(source.f().data().jdbc().queryForObject("SELECT COUNT(*) FROM pay_ent_command_dedup WHERE operation_kind='SV_SCHEMA_KEY_STEP' AND result_status='PENDING'",Long.class)).isEqualTo(1);
        active.set(source.approval());
        assertThat(r.step("OPERATOR","GLOBAL1",source.proof()).state()).isEqualTo("RECOVERED");
        assertThat(f.executions()).hasValue(1);
        assertThat(source.f().data().jdbc().queryForObject("SELECT write_mode FROM sv_issuer",String.class)).isEqualTo("FENCED");
    }
}
