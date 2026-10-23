package ai.neargo.shop.pay.storedvalue.migration;

import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.*;
import ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository;
import java.util.*;
import org.springframework.stereotype.Service;

/** Read-only, ledger-wide MySQL preflight. Not an atomic maintenance approval and never executes the proposed DDL. */
@Service
public class StoredValueAccountKeyInspector {
    private final IssuerRepository db;
    public StoredValueAccountKeyInspector(IssuerRepository db){this.db=db;}
    public StoredValueAccountKeyPlan.Plan inspect() {
        return db.write(()->{
            var rows=db.rows("SELECT INDEX_NAME AS index_name, NON_UNIQUE AS non_unique, SEQ_IN_INDEX AS seq_in_index, COLUMN_NAME AS column_name, SUB_PART AS sub_part, EXPRESSION AS expression, INDEX_TYPE AS index_type FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sv_account' ORDER BY INDEX_NAME,SEQ_IN_INDEX");
            var indexes=new ArrayList<StoredValueAccountKeyPlan.Index>();var groups=new LinkedHashMap<String,List<Map<String,Object>>>();
            for(var row:rows)groups.computeIfAbsent(text(row,"index_name"),k->new ArrayList<>()).add(row);
            for(var group:groups.entrySet()) {
                var columns=new ArrayList<String>();boolean unique=true,partial=false;int expected=1;
                for(var row:group.getValue()) {
                    unique &= number(row,"non_unique")==0;
                    String column=text(row,"column_name");
                    partial |= column==null||row.get("sub_part")!=null||row.get("expression")!=null||number(row,"seq_in_index")!=expected++||!"BTREE".equals(text(row,"index_type"));
                    columns.add(Objects.toString(column,"<expression>"));
                }
                indexes.add(new StoredValueAccountKeyPlan.Index(group.getKey(),unique,columns,partial));
            }
            // No tenant/deleted filter: uniqueness is global to the table, including soft-deleted financial facts.
            var counts=db.one("""
                SELECT COUNT(*) AS accounts,
                  COALESCE(SUM(CASE WHEN a.tenant_no IS NULL OR TRIM(a.tenant_no)='' OR a.issuer_no IS NULL OR TRIM(a.issuer_no)=''
                    OR a.customer_no IS NULL OR TRIM(a.customer_no)='' OR a.currency IS NULL OR TRIM(a.currency)=''
                    OR a.issuer_version_no IS NULL OR TRIM(a.issuer_version_no)='' THEN 1 ELSE 0 END),0) AS invalid_dimensions,
                  COALESCE(SUM(CASE WHEN i.issuer_no IS NULL OR i.deleted<>0 OR v.issuer_version_no IS NULL OR v.deleted<>0
                    OR v.issuer_no<>a.issuer_no OR v.currency<>a.currency
                    OR (i.scope_type='STORE' AND (a.store_no IS NULL OR i.scope_no<>a.store_no)) THEN 1 ELSE 0 END),0) AS broken_references
                FROM sv_account a
                LEFT JOIN sv_issuer i ON i.tenant_no=a.tenant_no AND i.issuer_no=a.issuer_no
                LEFT JOIN sv_issuer_version v ON v.tenant_no=a.tenant_no AND v.issuer_version_no=a.issuer_version_no
                """);
            long duplicateGroups=number(db.one("SELECT COUNT(*) AS n FROM (SELECT tenant_no,issuer_no,customer_no,currency FROM sv_account GROUP BY tenant_no,issuer_no,customer_no,currency HAVING COUNT(*)>1) duplicate_keys"),"n");
            long unfenced=number(db.one("SELECT COUNT(*) AS n FROM sv_issuer WHERE deleted=0 AND (write_mode IS NULL OR write_mode<>'FENCED')"),"n");
            return StoredValueAccountKeyPlan.evaluate(new StoredValueAccountKeyPlan.Snapshot(indexes,number(counts,"accounts"),number(counts,"invalid_dimensions"),number(counts,"broken_references"),duplicateGroups,unfenced));
        });
    }
}
