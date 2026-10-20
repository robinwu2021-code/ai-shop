package ai.neargo.shop.numbering.store;

import static org.assertj.core.api.Assertions.*;
import ai.neargo.shop.numbering.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class DocumentNumberStoreTest {
    JdbcTemplate jdbc;
    DataSourceTransactionManager manager;
    JdbcDocumentNumberRepository repository;
    SegmentDocumentNumberGenerator generator;
    LocalDate date=LocalDate.of(2026,10,20);
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        manager=new DataSourceTransactionManager(ds); jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE pay_document_sequence(seq_key VARCHAR(32) PRIMARY KEY,next_value BIGINT NOT NULL,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE pay_document_number(document_no VARCHAR(32) PRIMARY KEY,tenant_no VARCHAR(64),document_type VARCHAR(40),source_system VARCHAR(32),source_resource_type VARCHAR(64),source_no VARCHAR(128),business_date DATE,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,UNIQUE(tenant_no,document_type,source_system,source_resource_type,source_no))");
        repository=new JdbcDocumentNumberRepository(DocumentOwner.PAY,manager);
        generator=new SegmentDocumentNumberGenerator(repository,new DocumentNumberProperties(ZoneId.of("Asia/Shanghai"),3));
    }
    @Test void twoInstancesAndRestartNeverReuseNumbers() throws Exception {
        var second=new SegmentDocumentNumberGenerator(repository,new DocumentNumberProperties(ZoneId.of("Asia/Shanghai"),3));
        Set<String> numbers=ConcurrentHashMap.newKeySet();
        try(var pool=Executors.newFixedThreadPool(4)) {
            List<Future<?>> tasks=new ArrayList<>();
            for(int i=0;i<80;i++){var g=i%2==0?generator:second;tasks.add(pool.submit(()->assertThat(numbers.add(g.reserve(DocumentType.SV_RECHARGE,date).value())).isTrue()));}
            for(var task:tasks)task.get(10,TimeUnit.SECONDS);
        }
        assertThat(numbers).hasSize(80);
        var restarted=new SegmentDocumentNumberGenerator(repository,new DocumentNumberProperties(ZoneId.of("Asia/Shanghai"),3));
        assertThat(numbers).doesNotContain(restarted.reserve(DocumentType.SV_RECHARGE,date).value());
    }
    @Test void bindingRollsBackButReservedSequenceDoesNotAndReadDoesNotAllocate() {
        var source=new DocumentSource("MAIN","PAY","SV_RECHARGE","internal-long-source");
        assertThat(repository.findBySource(DocumentType.SV_RECHARGE,source)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_document_sequence",Integer.class)).isZero();
        var first=generator.reserve(DocumentType.SV_RECHARGE,date);
        new TransactionTemplate(manager).executeWithoutResult(tx->{repository.bind(DocumentType.SV_RECHARGE,source,first);tx.setRollbackOnly();});
        assertThat(repository.findBySource(DocumentType.SV_RECHARGE,source)).isEmpty();
        var next=generator.reserve(DocumentType.SV_RECHARGE,date);
        assertThat(next.value()).isNotEqualTo(first.value());
        new TransactionTemplate(manager).executeWithoutResult(tx->repository.bind(DocumentType.SV_RECHARGE,source,next));
        assertThat(repository.findBySource(DocumentType.SV_RECHARGE,source)).contains(next.value());
        assertThat(repository.resolve("OTHER",DocumentType.SV_RECHARGE,next.value())).isEmpty();
        assertThat(repository.resolve("MAIN",DocumentType.SV_RECHARGE,next.value())).contains(source);
    }
    @Test void transactionGuardAppliesEvenWhenCacheHasNumbersAndBindingRequiresOwnerTransaction() {
        generator.reserve(DocumentType.SV_RECHARGE,date);
        new TransactionTemplate(manager).executeWithoutResult(tx->
            assertThatThrownBy(()->generator.reserve(DocumentType.SV_RECHARGE,date)).isInstanceOf(IllegalStateException.class));
        assertThatThrownBy(()->generator.reserve(DocumentType.MARKETING_ACTIVITY,date)).isInstanceOf(IllegalArgumentException.class);
        var n=generator.reserve(DocumentType.SV_RECHARGE,date);
        assertThatThrownBy(()->repository.bind(DocumentType.SV_RECHARGE,new DocumentSource("T","PAY","R","S"),n)).isInstanceOf(IllegalStateException.class);
    }
    @Test void sameSourceConcurrentBindReturnsWinnerWithoutChangingItsDate() throws Exception {
        var source=new DocumentSource("MAIN","PAY","SV_RECHARGE","same-source");
        var one=generator.reserve(DocumentType.SV_RECHARGE,date);
        var two=generator.reserve(DocumentType.SV_RECHARGE,date.plusDays(1));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->new TransactionTemplate(manager).execute(tx->repository.bind(DocumentType.SV_RECHARGE,source,one)));
            var b=pool.submit(()->new TransactionTemplate(manager).execute(tx->repository.bind(DocumentType.SV_RECHARGE,source,two)));
            assertThat(a.get(10,TimeUnit.SECONDS)).isEqualTo(b.get(10,TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_document_number",Integer.class)).isEqualTo(1);
    }
    @Test void coreUsesItsOwnSequenceNamespaceAndRefusesPayTypes() {
        jdbc.execute("CREATE TABLE sys_sequence(seq_name VARCHAR(32) PRIMARY KEY,next_value BIGINT NOT NULL,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        var core=new SegmentDocumentNumberGenerator(new JdbcDocumentNumberRepository(DocumentOwner.CORE,manager),
            new DocumentNumberProperties(ZoneId.of("Asia/Shanghai"),2));
        assertThat(core.reserve(DocumentType.MARKETING_ACTIVITY,date).value()).isEqualTo("PT20261020000001");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_sequence",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_document_sequence",Integer.class)).isZero();
        assertThatThrownBy(()->core.reserve(DocumentType.SV_RECHARGE,date)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void singlePhysicalConnectionRejectsNestedReservationBeforeAcquiringAnotherConnection() {
        var ds=new org.springframework.jdbc.datasource.SingleConnectionDataSource(
            "jdbc:h2:mem:single-"+UUID.randomUUID()+";MODE=MySQL","sa","",true);
        var tm=new DataSourceTransactionManager(ds);
        var single=new SegmentDocumentNumberGenerator(new JdbcDocumentNumberRepository(DocumentOwner.PAY,tm),
            new DocumentNumberProperties(ZoneId.of("Asia/Shanghai"),1));
        try {
            new TransactionTemplate(tm).executeWithoutResult(tx->
                assertThatThrownBy(()->single.reserve(DocumentType.SV_RECHARGE,date)).isInstanceOf(IllegalStateException.class));
        } finally { ds.destroy(); }
    }
    @Test void businessZoneBatchLimitsAndSequenceOverflowAreExplicit() {
        assertThat(generator.reserve(DocumentType.SV_RECHARGE,Instant.parse("2026-10-19T16:00:00Z")).businessDate()).isEqualTo(date);
        assertThat(generator.reserve(DocumentType.SV_RECHARGE,date,5)).hasSize(5);
        assertThatThrownBy(()->generator.reserve(DocumentType.SV_RECHARGE,date,10001)).isInstanceOf(IllegalArgumentException.class);
        jdbc.update("INSERT INTO pay_document_sequence(seq_key,next_value) VALUES(?,?)","doc:SV:20261020",Long.MAX_VALUE-1);
        assertThatThrownBy(()->generator.reserve(DocumentType.SV_VOUCHER,date)).isInstanceOf(IllegalStateException.class);
    }
}
