package ai.neargo.shop.numbering.store;

import ai.neargo.shop.numbering.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import java.util.Collection;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Service-local storage; SQL contains only fixed, owner-selected identifiers and explicit tenant filters. */
public final class JdbcDocumentNumberRepository implements DocumentNumberService {
    private final DocumentOwner owner;
    private final JdbcTemplate jdbc;
    private final DataSourceTransactionManager manager;
    private final String sequenceTable, sequenceKey, mappingTable;
    public JdbcDocumentNumberRepository(DocumentOwner owner, DataSourceTransactionManager manager) {
        this.owner=Objects.requireNonNull(owner); this.manager=Objects.requireNonNull(manager);
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(manager.getDataSource()));
        sequenceTable=owner==DocumentOwner.CORE?"sys_sequence":"pay_document_sequence";
        sequenceKey=owner==DocumentOwner.CORE?"seq_name":"seq_key";
        mappingTable=owner==DocumentOwner.CORE?"core_document_number":"pay_document_number";
    }
    void requireOwner(DocumentType type) {
        if(type==null || type.owner()!=owner)throw new IllegalArgumentException("Document type belongs to another service");
    }
    static void requireOutsideTransaction() {
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Reserve all document numbers before entering the business transaction");
    }
    long[] claim(DocumentType type,LocalDate date,int size) {
        requireOwner(type); requireOutsideTransaction();
        if(size<1 || size>100000)throw new IllegalArgumentException("Invalid segment size");
        String key="doc:"+type.prefix()+":"+date.format(DateTimeFormatter.BASIC_ISO_DATE);
        var tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return Objects.requireNonNull(tx.execute(status->{
            jdbc.update("INSERT IGNORE INTO "+sequenceTable+"("+sequenceKey+",next_value) VALUES(?,0)",key);
            int changed=jdbc.update("UPDATE "+sequenceTable+" SET next_value=next_value+?,updated_at=CURRENT_TIMESTAMP WHERE "+sequenceKey+"=? AND next_value>=0 AND next_value<=?",size,key,Long.MAX_VALUE-size);
            if(changed!=1)throw new IllegalStateException("Document sequence exhausted or corrupt: "+type);
            long hi=Objects.requireNonNull(jdbc.queryForObject("SELECT next_value FROM "+sequenceTable+" WHERE "+sequenceKey+"=?",Long.class,key));
            return new long[]{hi-size+1,hi};
        }));
    }
    @Override public String bind(DocumentType type,DocumentSource source,ReservedDocumentNumber reserved) {
        requireOwner(type);Objects.requireNonNull(source);Objects.requireNonNull(reserved);
        if(type!=reserved.type())throw new IllegalArgumentException("Reservation type mismatch");
        requireBusinessTransaction();
        // A concurrent same-source request wins once; never overwrite its number or business date.
        jdbc.update("INSERT INTO "+mappingTable+"(tenant_no,document_type,source_system,source_resource_type,source_no,document_no,business_date) VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE document_no=document_no",
            source.tenantNo(),type.name(),source.sourceSystem(),source.resourceType(),source.sourceNo(),reserved.value(),java.sql.Date.valueOf(reserved.businessDate()));
        return find(type,source,true).orElseThrow(()->new IllegalStateException("Document number collision with a different source"));
    }
    void requireBusinessTransaction() {
        if(!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(Objects.requireNonNull(manager.getDataSource())))
            throw new IllegalStateException("Bind must join the owning service transaction");
    }
    @Override public Map<DocumentSource,String> findAll(DocumentType type,Collection<DocumentSource> sources) {
        requireOwner(type);Objects.requireNonNull(sources);
        var unique=new ArrayList<>(new LinkedHashSet<>(sources));
        if(unique.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("Null document source");
        var result=new LinkedHashMap<DocumentSource,String>();
        record Namespace(String tenant,String system,String resource) { }
        for(int offset=0;offset<unique.size();offset+=500) {
            var groups=new LinkedHashMap<Namespace,List<String>>();
            for(var source:unique.subList(offset,Math.min(offset+500,unique.size())))
                groups.computeIfAbsent(new Namespace(source.tenantNo(),source.sourceSystem(),source.resourceType()),key->new ArrayList<>()).add(source.sourceNo());
            var filters=new ArrayList<String>();var args=new ArrayList<Object>();args.add(type.name());
            for(var entry:groups.entrySet()) {
                var key=entry.getKey();
                filters.add("(tenant_no=? AND source_system=? AND source_resource_type=? AND source_no IN ("+String.join(",",java.util.Collections.nCopies(entry.getValue().size(),"?"))+"))");
                args.add(key.tenant());args.add(key.system());args.add(key.resource());args.addAll(entry.getValue());
            }
            jdbc.query("SELECT tenant_no,source_system,source_resource_type,source_no,document_no FROM "+mappingTable+" WHERE document_type=? AND ("+String.join(" OR ",filters)+")",
                rs->{result.put(new DocumentSource(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4)),rs.getString(5));},args.toArray());
        }
        return Map.copyOf(result);
    }
    @Override public Optional<String> findBySource(DocumentType type,DocumentSource source) {
        requireOwner(type); Objects.requireNonNull(source); return find(type,source,false);
    }
    private Optional<String> find(DocumentType type,DocumentSource source,boolean lock) {
        return jdbc.query("SELECT document_no FROM "+mappingTable+" WHERE tenant_no=? AND document_type=? AND source_system=? AND source_resource_type=? AND source_no=?"+(lock?" FOR UPDATE":""),
            (rs,n)->rs.getString(1),source.tenantNo(),type.name(),source.sourceSystem(),source.resourceType(),source.sourceNo()).stream().findFirst();
    }
    @Override public Optional<DocumentSource> resolve(String tenantNo,DocumentType type,String documentNo) {
        requireOwner(type);
        if(tenantNo==null || tenantNo.isBlank())throw new IllegalArgumentException("Tenant required");
        if(!DocumentNumberFormatter.isValid(type,documentNo))return Optional.empty();
        return jdbc.query("SELECT tenant_no,source_system,source_resource_type,source_no FROM "+mappingTable+" WHERE tenant_no=? AND document_type=? AND document_no=?",
            (rs,n)->new DocumentSource(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4)),tenantNo,type.name(),documentNo).stream().findFirst();
    }
}
