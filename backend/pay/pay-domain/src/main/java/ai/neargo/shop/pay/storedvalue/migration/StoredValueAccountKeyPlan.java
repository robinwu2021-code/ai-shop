package ai.neargo.shop.pay.storedvalue.migration;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Ledger-wide structural preflight only. Statements are proposals, never authorization or automatic DDL. */
public final class StoredValueAccountKeyPlan {
    private StoredValueAccountKeyPlan() {}
    public record Index(String name,boolean unique,List<String> columns,boolean partialOrExpression) {
        public Index { columns=List.copyOf(columns); }
    }
    /** Counts include all tenants and deleted rows, because MySQL unique keys do too. */
    public record Snapshot(List<Index> indexes,long accounts,long invalidDimensions,long brokenReferences,long duplicateGroups,long unfencedIssuers) {
        public Snapshot { indexes=List.copyOf(indexes); }
    }
    public record Plan(boolean ready,String nextStep,String schemaHash,String targetSchemaHash,long accounts,List<String> issues,List<String> statements) {
        public Plan { issues=List.copyOf(issues);statements=List.copyOf(statements); }
    }
    private static final String NEW="uk_sv_issuer_customer",OWNER="uk_sv_owner",CUSTOMER="uk_sv_customer_owner";
    private static final Map<String,List<String>> EXPECTED=Map.of(
        "PRIMARY",List.of("id"),"uk_sv_account",List.of("tenant_no","account_no"),
        OWNER,List.of("tenant_no","user_no","entity_no","store_no","currency"),
        CUSTOMER,List.of("tenant_no","customer_no","entity_no","store_no","currency"),
        NEW,List.of("tenant_no","issuer_no","customer_no","currency"));
    public static Plan evaluate(Snapshot snapshot) {
        var issues=new ArrayList<String>();var indexes=new TreeMap<String,Index>();
        for(var index:snapshot.indexes()) {
            if(index.name()==null||indexes.putIfAbsent(index.name(),index)!=null) {issues.add("DUPLICATE_OR_INVALID_INDEX");continue;}
            var columns=EXPECTED.get(index.name());
            if(columns!=null&&(!index.unique()||index.partialOrExpression()||!columns.equals(index.columns())))issues.add("INDEX_DEFINITION_MISMATCH:"+index.name());
            else if(columns==null&&index.unique())issues.add("UNREVIEWED_UNIQUE_INDEX:"+index.name());
        }
        if(!indexes.containsKey("PRIMARY")||!indexes.containsKey("uk_sv_account"))issues.add("ACCOUNT_IDENTITY_KEY_MISSING");
        if(!indexes.containsKey(NEW)&&(!indexes.containsKey(OWNER)||!indexes.containsKey(CUSTOMER)))issues.add("LEGACY_PROTECTION_INCOMPLETE");
        if(snapshot.accounts()<0||snapshot.invalidDimensions()<0||snapshot.brokenReferences()<0||snapshot.duplicateGroups()<0||snapshot.unfencedIssuers()<0)issues.add("INVALID_COUNTS");
        if(snapshot.invalidDimensions()>0)issues.add("ACCOUNT_DIMENSIONS_INCOMPLETE");
        if(snapshot.brokenReferences()>0)issues.add("ACCOUNT_ISSUER_REFERENCE_INVALID");
        if(snapshot.duplicateGroups()>0)issues.add("ISSUER_CUSTOMER_DUPLICATE");
        if(snapshot.unfencedIssuers()>0)issues.add("LEDGER_WRITERS_NOT_FENCED");
        String hash=schemaHash(indexes.values());
        if(!issues.isEmpty())return new Plan(false,"BLOCKED",hash,hash,snapshot.accounts(),issues,List.of());
        if(!indexes.containsKey(NEW)){
            var target=new TreeMap<>(indexes);target.put(NEW,new Index(NEW,true,EXPECTED.get(NEW),false));
            return new Plan(true,"ADD_ISSUER_KEY",hash,schemaHash(target.values()),snapshot.accounts(),List.of(),
                List.of("ALTER TABLE sv_account ADD UNIQUE KEY uk_sv_issuer_customer (tenant_no,issuer_no,customer_no,currency)"));
        }
        var drops=new ArrayList<String>();
        if(indexes.containsKey(OWNER))drops.add("DROP INDEX "+OWNER);
        if(indexes.containsKey(CUSTOMER))drops.add("DROP INDEX "+CUSTOMER);
        var target=new TreeMap<>(indexes);target.remove(OWNER);target.remove(CUSTOMER);
        return new Plan(true,drops.isEmpty()?"KEYS_READY":"DROP_LEGACY_KEYS",hash,schemaHash(target.values()),snapshot.accounts(),List.of(),
            drops.isEmpty()?List.of():List.of("ALTER TABLE sv_account "+String.join(", ",drops)));
    }
    private static String schemaHash(Collection<Index> indexes){return hash(indexes.stream().sorted(Comparator.comparing(Index::name)).map(i->i.name().length()+":"+i.name()+":"+i.unique()+":"+i.partialOrExpression()+":"+String.join(",",i.columns())+";").reduce("",String::concat));}
    private static String hash(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
