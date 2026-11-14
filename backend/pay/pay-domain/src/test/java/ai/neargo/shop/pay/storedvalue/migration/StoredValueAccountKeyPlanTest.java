package ai.neargo.shop.pay.storedvalue.migration;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import ai.neargo.shop.pay.storedvalue.migration.StoredValueAccountKeyPlan.*;

class StoredValueAccountKeyPlanTest {
    private Index index(String name,String... columns){return new Index(name,true,List.of(columns),false);}
    private List<Index> oldKeys(){return List.of(index("PRIMARY","id"),index("uk_sv_account","tenant_no","account_no"),
        index("uk_sv_owner","tenant_no","user_no","entity_no","store_no","currency"),
        index("uk_sv_customer_owner","tenant_no","customer_no","entity_no","store_no","currency"));}
    private Index issuer(){return index("uk_sv_issuer_customer","tenant_no","issuer_no","customer_no","currency");}
    private Snapshot snapshot(List<Index> indexes){return new Snapshot(indexes,2,0,0,0,0);}
    @Test void addsIssuerKeyBeforeSuggestingAnyLegacyDrop() {
        var plan=StoredValueAccountKeyPlan.evaluate(snapshot(oldKeys()));
        assertTrue(plan.ready());assertEquals("ADD_ISSUER_KEY",plan.nextStep());
        assertEquals(List.of("ALTER TABLE sv_account ADD UNIQUE KEY uk_sv_issuer_customer (tenant_no,issuer_no,customer_no,currency)"),plan.statements());
    }
    @Test void resumesAfterEachCommittedSchemaChangeAndKeepsAccountIdentityKey() {
        var keys=new ArrayList<>(oldKeys());keys.add(issuer());
        var plan=StoredValueAccountKeyPlan.evaluate(snapshot(keys));
        assertEquals("DROP_LEGACY_KEYS",plan.nextStep());
        assertEquals(List.of("ALTER TABLE sv_account DROP INDEX uk_sv_owner, DROP INDEX uk_sv_customer_owner"),plan.statements());
        keys.removeIf(k->k.name().equals("uk_sv_owner"));
        assertEquals(List.of("ALTER TABLE sv_account DROP INDEX uk_sv_customer_owner"),StoredValueAccountKeyPlan.evaluate(snapshot(keys)).statements());
        keys.removeIf(k->k.name().equals("uk_sv_customer_owner"));
        var complete=StoredValueAccountKeyPlan.evaluate(snapshot(keys));
        assertTrue(complete.ready());assertEquals("KEYS_READY",complete.nextStep());assertTrue(complete.statements().isEmpty());
        assertNotEquals(plan.schemaHash(),complete.schemaHash());
        Collections.reverse(keys);
        assertEquals(complete.schemaHash(),StoredValueAccountKeyPlan.evaluate(snapshot(keys)).schemaHash());
    }
    @Test void rejectsWrongNamedKeyNonuniquePrefixUnknownUniqueOrMissingIdentityProtection() {
        for(var bad:List.of(new Index("uk_sv_issuer_customer",false,issuer().columns(),false),
                new Index("uk_sv_issuer_customer",true,List.of("tenant_no","customer_no","issuer_no","currency"),false),
                new Index("uk_sv_issuer_customer",true,issuer().columns(),true),
                index("unreviewed_owner","tenant_no","user_no"))) {
            var keys=new ArrayList<>(oldKeys());keys.add(bad);
            var plan=StoredValueAccountKeyPlan.evaluate(snapshot(keys));
            assertFalse(plan.ready());assertTrue(plan.statements().isEmpty());
        }
        var keys=new ArrayList<>(oldKeys());keys.removeIf(k->k.name().equals("uk_sv_account"));
        assertFalse(StoredValueAccountKeyPlan.evaluate(snapshot(keys)).ready());
        keys=new ArrayList<>(oldKeys());keys.add(keys.getFirst());
        assertFalse(StoredValueAccountKeyPlan.evaluate(snapshot(keys)).ready());
        assertFalse(StoredValueAccountKeyPlan.evaluate(snapshot(List.of(index("PRIMARY","id"),index("uk_sv_account","tenant_no","account_no")))).ready());
    }
    @Test void anyUnmappedUnfencedDuplicateOrBrokenReferenceBlocksAllDdl() {
        for(var facts:List.of(new long[]{1,0,0,0},new long[]{0,1,0,0},new long[]{0,0,1,0},new long[]{0,0,0,1})) {
            var plan=StoredValueAccountKeyPlan.evaluate(new Snapshot(oldKeys(),2,facts[0],facts[1],facts[2],facts[3]));
            assertFalse(plan.ready());assertEquals("BLOCKED",plan.nextStep());assertTrue(plan.statements().isEmpty());
        }
        assertFalse(StoredValueAccountKeyPlan.evaluate(new Snapshot(oldKeys(),-1,0,0,0,0)).ready());
    }
}
