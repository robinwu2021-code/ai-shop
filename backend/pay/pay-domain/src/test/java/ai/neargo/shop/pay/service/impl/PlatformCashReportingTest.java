package ai.neargo.shop.pay.service.impl;

import ai.neargo.shop.pay.service.PlatformCashReporting;
import ai.neargo.shop.pay.service.PlatformCollectionService.*;
import ai.neargo.shop.pay.service.PlatformRefundService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PlatformCashReportingTest {
    private JdbcTemplate db;
    private PlatformCashReporting service;
    private JdbcDataSource raw;
    private final List<String> sql=new ArrayList<>();
    private final JsonMapper json=JsonMapper.builder().build();
    @BeforeEach void setup() {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        raw=ds;
        var observed=new org.springframework.jdbc.datasource.DelegatingDataSource(ds) {
            @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                var connection=super.getConnection();
                return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,args)->{
                    if(method.getName().equals("prepareStatement"))sql.add((String)args[0]);
                    try{return method.invoke(connection,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
                });
            }
        };
        db=new JdbcTemplate(observed);service=new PlatformCashReportingImpl(observed,new DataSourceTransactionManager(observed));
        db.execute("CREATE TABLE stl_payment(payment_no VARCHAR(64) PRIMARY KEY,tenant_no VARCHAR(32),order_no VARCHAR(64),origin_payment_no VARCHAR(64),direction VARCHAR(16),status VARCHAR(16),pay_scene VARCHAR(32),deleted INT DEFAULT 0,amount_minor BIGINT,currency VARCHAR(8),succeeded_at BIGINT,entity_no VARCHAR(64),pay_channel VARCHAR(32),pay_method VARCHAR(32),payment_request_json VARCHAR(16000),refund_request_json VARCHAR(16000),platform_manual_proof VARCHAR(16000),platform_receipt_json VARCHAR(16000),platform_close_fence VARCHAR(128),platform_bank_ref_key VARCHAR(64),platform_dispatch_at BIGINT,trade_no VARCHAR(128),out_trade_no VARCHAR(128),refund_capacity_state VARCHAR(16),refund_query_state VARCHAR(16),refund_verified_minor BIGINT,refund_verified_at BIGINT,refund_verified_provider_no VARCHAR(128))");
    }
    private Request receipt(String id,String currency,long amount,long at,String scene,boolean closed) {
        var proofKey=new Key("MAIN","O"+id,"A"+id);
        var key=proofKey;
        var terms=new OfflineInstructions("P",1,"PLATFORM",currency,"account-key","V",100000,Map.of());
        if("MALL_UNMATCHED".equals(scene)) {
            var original=new Request(key,"BUYER","PLATFORM","OFFLINE","BANK_TRANSFER",amount,currency,900000,0,null,terms);
            key=new Key("MAIN",key.orderNo(),"U"+PlatformCashAuthority.bankReferenceKey(original,"bank-"+id).substring(0,60));
        }
        var request=new Request(key,"BUYER","PLATFORM","OFFLINE","BANK_TRANSFER",amount,currency,900000,0,null,terms);
        var proof=new OfflineProof(proofKey,"operator","PLATFORM",amount,currency,at,"bank-"+id,"private-evidence","NORMAL",at+1000);
        String no=PlatformCollectionServiceImpl.paymentNo(key);
        db.update("INSERT INTO stl_payment(payment_no,tenant_no,order_no,direction,status,pay_scene,amount_minor,currency,succeeded_at,entity_no,pay_channel,pay_method,payment_request_json,platform_manual_proof,platform_close_fence,platform_dispatch_at,trade_no,out_trade_no,platform_bank_ref_key) VALUES(?,'MAIN',?,'PAY','SUCCESS',?,?,?,?,?,'OFFLINE','BANK_TRANSFER',?,?,?,?,?,?,?)",no,key.orderNo(),scene,amount,currency,at,"PLATFORM",json.writeValueAsString(new PlatformCollectionServiceImpl.Frozen(scene,request)),json.writeValueAsString(proof),closed?"CLOSED":null,1L,proof.bankReference(),"external-"+id,PlatformCashAuthority.bankReferenceKey(request,proof.bankReference()));
        return request;
    }
    private String refund(Request original,String id,long amount,long at) {
        var key=new PlatformRefundService.Key("MAIN",original.key().orderNo(),id);
        String no=PlatformRefundServiceImpl.paymentNo(key);
        var request=new PlatformRefundService.Request(key,"STORE",original.key().attemptNo(),PlatformCollectionServiceImpl.paymentNo(original.key()),"PLATFORM",original.amountMinor(),amount,original.currency(),"return");
        var proof=new PlatformRefundService.OfflinePayout(key,"operator",amount,original.currency(),at,"private-payee","payout-"+id,"private-evidence",at+1000);
        db.update("INSERT INTO stl_payment(payment_no,tenant_no,order_no,origin_payment_no,direction,status,pay_scene,amount_minor,currency,succeeded_at,entity_no,pay_channel,refund_request_json,platform_manual_proof,trade_no,platform_bank_ref_key,refund_capacity_state,refund_query_state,refund_verified_minor,refund_verified_at,refund_verified_provider_no) VALUES(?,'MAIN',?,?,'REFUND','SUCCESS','PLATFORM_MALL',?,?,?,'PLATFORM','OFFLINE',?,?,?,?,'CONSUMED','SUCCEEDED',?,?,?)",no,key.orderNo(),request.originalPaymentNo(),amount,original.currency(),at,json.writeValueAsString(new PlatformRefundServiceImpl.Frozen("PLATFORM_MALL",request,"external","provider","OFFLINE")),json.writeValueAsString(proof),proof.bankReference(),PlatformRefundServiceImpl.bankKey("MAIN",proof.bankReference()),amount,at,proof.bankReference());
        return no;
    }
    private List<PlatformCashReporting.Fact> scan(String currency) {
        var facts=new ArrayList<PlatformCashReporting.Fact>();service.scan(new PlatformCashReporting.Query("MAIN",1000,2000000,currency),facts::addAll);return facts;
    }
    @Test void confirmsActualDatesCurrenciesAndDisjointCashRefunds() {
        var normal=receipt("one","TWD",100,1000,"PLATFORM_MALL",false);
        var unmatched=receipt("two","USD",200,2000,"MALL_UNMATCHED",false);
        var closed=receipt("three","TWD",300,3000,"PLATFORM_MALL",true);
        String ordinary=refund(normal,"ordinary",20,5000),cash=refund(unmatched,"cash-one",200,6000),late=refund(closed,"late-one",300,7000);
        assertThat(scan(null)).hasSize(6).allMatch(PlatformCashReporting.Fact::verified);
        assertThat(scan("TWD")).hasSize(4);
        assertThat(scan(null)).filteredOn(f->f.paymentNo().equals(ordinary)).singleElement().satisfies(f->{assertThat(f.occurredAt()).isEqualTo(5000);assertThat(f.amountMinor()).isEqualTo(20);assertThat(f.classification()).isEqualTo("NORMAL");});
        assertThat(scan(null)).filteredOn(f->f.paymentNo().equals(cash)).singleElement().extracting(PlatformCashReporting.Fact::classification).isEqualTo("UNMATCHED");
        assertThat(scan(null)).filteredOn(f->f.paymentNo().equals(late)).singleElement().extracting(PlatformCashReporting.Fact::classification).isEqualTo("CLOSED_ORDER");
    }
    @Test void malformedAndOverRefundedSuccessCannotBecomeVerifiedMoney() {
        var original=receipt("one","TWD",100,1000,"PLATFORM_MALL",false);
        String a=refund(original,"a",60,2000),b=refund(original,"b",60,3000);
        assertThat(scan(null)).filteredOn(f->"REFUND".equals(f.direction())).allMatch(f->!f.verified()&&f.diagnostic()!=null);
        db.update("UPDATE stl_payment SET status='PENDING' WHERE payment_no=?",b);
        db.update("UPDATE stl_payment SET refund_verified_minor=1 WHERE payment_no=?",a);
        assertThat(scan(null)).filteredOn(f->f.paymentNo().equals(a)).singleElement().satisfies(f->{assertThat(f.verified()).isFalse();assertThat(f.diagnostic()).doesNotContain("private");});
        db.update("UPDATE stl_payment SET platform_manual_proof='malformed' WHERE direction='PAY'");
        assertThat(scan(null)).allMatch(f->!f.verified());
    }
    @Test void keysetsBeyondOneBatchInOneReadOnlyRepeatableReadTransaction() {
        for(int i=0;i<503;i++)receipt("x"+i,"TWD",1,1000,"PLATFORM_MALL",false);
        var facts=new ArrayList<PlatformCashReporting.Fact>();var transactions=new HashSet<Object>();
        service.scan(new PlatformCashReporting.Query("MAIN",1000,2000,null),batch->{
            assertThat(batch).hasSizeLessThanOrEqualTo(500);
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isEqualTo(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
            transactions.add(TransactionSynchronizationManager.getResource(db.getDataSource()));facts.addAll(batch);
        });
        assertThat(facts).hasSize(503).allMatch(PlatformCashReporting.Fact::verified);
        assertThat(facts.stream().map(PlatformCashReporting.Fact::paymentNo).distinct()).hasSize(503);
        assertThat(transactions).hasSize(1);
    }
    @Test void onlineProofUsesActualRefundDateAndRejectsTampering() {
        var key=new Key("MAIN","ONLINE","A");
        var request=new Request(key,"BUYER","PLATFORM","LINE_PAY","WALLET",900,"TWD",900000,100,null);
        String no=PlatformCollectionServiceImpl.paymentNo(key);
        var proof=new ai.neargo.shop.pay.channel.PayGateway.QueryResult(true,true,true,900,"provider","TWD",1234L,"PLATFORM","external","SUCCESS",1L);
        db.update("INSERT INTO stl_payment(payment_no,tenant_no,order_no,direction,status,pay_scene,amount_minor,currency,succeeded_at,entity_no,pay_channel,pay_method,payment_request_json,platform_receipt_json,trade_no,out_trade_no) VALUES(?,'MAIN','ONLINE','PAY','SUCCESS','PLATFORM_MALL',900,'TWD',1234,'PLATFORM','LINE_PAY','WALLET',?,?,'provider','external')",no,json.writeValueAsString(new PlatformCollectionServiceImpl.Frozen("PLATFORM_MALL",request)),json.writeValueAsString(proof));
        var refundKey=new PlatformRefundService.Key("MAIN","ONLINE","cash-shared");
        var refund=new PlatformRefundService.Request(refundKey,"STORE","A",no,"PLATFORM",900,90,"TWD","return");
        String refundNo=PlatformRefundServiceImpl.paymentNo(refundKey);
        var refundProof=new ai.neargo.shop.spi.pay.RefundQueryResult(ai.neargo.shop.spi.pay.RefundQueryResult.Status.SUCCEEDED,"SUCCESS","refund-external",90,"refund-provider","TWD",9000,"external","provider",900L,2000L,"PLATFORM");
        db.update("INSERT INTO stl_payment(payment_no,tenant_no,order_no,origin_payment_no,direction,status,pay_scene,amount_minor,currency,succeeded_at,entity_no,pay_channel,refund_request_json,platform_receipt_json,trade_no,out_trade_no,refund_capacity_state,refund_query_state,refund_verified_minor,refund_verified_at,refund_verified_provider_no) VALUES(?,'MAIN','ONLINE',?,'REFUND','SUCCESS','PLATFORM_MALL',90,'TWD',2000,'PLATFORM','LINE_PAY',?,?,'refund-provider','refund-external','CONSUMED','SUCCEEDED',90,9000,'refund-provider')",refundNo,no,json.writeValueAsString(new PlatformRefundServiceImpl.Frozen("PLATFORM_MALL",refund,"external","provider","LINE_PAY")),json.writeValueAsString(refundProof));
        sql.clear();
        assertThat(scan(null)).hasSize(2).allMatch(PlatformCashReporting.Fact::verified);
        assertThat(scan(null)).filteredOn(f->f.direction().equals("REFUND")).singleElement().extracting(PlatformCashReporting.Fact::occurredAt).isEqualTo(2000L);
        assertThat(sql).allMatch(statement->statement.startsWith("SELECT ") && !statement.contains("FOR UPDATE") && !statement.contains("mall_") && !statement.contains("mch_"));
        db.update("UPDATE stl_payment SET trade_no='tampered' WHERE payment_no=?",no);
        assertThat(scan(null)).allMatch(f->!f.verified());
    }
    @Test void fullHistoryDetectsOverRefundAndOverflowEvenOutsideOccurrenceWindow() {
        var original=receipt("capacity","TWD",Long.MAX_VALUE,1000,"PLATFORM_MALL",false);
        refund(original,"large",Long.MAX_VALUE,2000);refund(original,"outside",1,3000000);
        assertThat(scan(null)).hasSize(2).allMatch(f->!f.verified() && f.diagnostic().equals("PLATFORM_CASH_REFUND_CAPACITY_INVALID"));
    }
    @Test void laterBatchesKeepTheSameSnapshotWhenAnotherConnectionChangesAnUnvisitedRow() {
        for(int i=0;i<502;i++)receipt("snapshot"+i,"TWD",1,1000,"PLATFORM_MALL",false);
        String last=db.queryForObject("SELECT MAX(payment_no) FROM stl_payment",String.class);
        var facts=new ArrayList<PlatformCashReporting.Fact>();
        service.scan(new PlatformCashReporting.Query("MAIN",1000,2000,null),batch->{
            facts.addAll(batch);
            if(facts.size()==500)try(var connection=raw.getConnection();var statement=connection.prepareStatement("UPDATE stl_payment SET amount_minor=2 WHERE payment_no=?")) {
                statement.setString(1,last);statement.executeUpdate();
            }catch(java.sql.SQLException e){throw new RuntimeException(e);}
        });
        assertThat(facts).hasSize(502).allMatch(f->f.verified() && f.amountMinor()==1);
        assertThat(db.queryForObject("SELECT amount_minor FROM stl_payment WHERE payment_no=?",Long.class,last)).isEqualTo(2);
    }
    @Test void callbackFailureAndInvalidQueriesPropagate() {
        receipt("one","TWD",100,1000,"PLATFORM_MALL",false);
        assertThatThrownBy(()->service.scan(new PlatformCashReporting.Query("MAIN",0,2000,null),batch->{throw new IllegalStateException("CALLBACK_FAILED");})).hasMessage("CALLBACK_FAILED");
        assertThatThrownBy(()->service.scan(new PlatformCashReporting.Query("OTHER",0,2000,null),batch->{})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.scan(new PlatformCashReporting.Query("MAIN",0,367L*86400000,null),batch->{})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.scan(new PlatformCashReporting.Query("MAIN",0,1,"bad"),batch->{})).isInstanceOf(IllegalArgumentException.class);
    }
}
