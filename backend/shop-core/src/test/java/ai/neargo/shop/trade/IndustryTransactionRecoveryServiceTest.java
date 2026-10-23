package ai.neargo.shop.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import ai.neargo.shop.spi.trade.IndustryOrderPort;
import ai.neargo.shop.trade.entity.OrdIndustryOrderSource;
import ai.neargo.shop.trade.mapper.TradeMappers.IndustryOrderSourceMapper;
import ai.neargo.shop.trade.order.entity.OrdCreateIntent;
import ai.neargo.shop.trade.order.mapper.CreateIntentMapper;
import ai.neargo.shop.trade.service.IndustryCheckoutService;
import ai.neargo.shop.trade.service.IndustryOrderService;
import ai.neargo.shop.trade.service.impl.IndustryTransactionRecoveryServiceImpl;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class IndustryTransactionRecoveryServiceTest {
    @BeforeAll
    static void initializeEntityMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),"industry-recovery-test"),
                OrdIndustryOrderSource.class);
    }

    private final IndustryOrderSourceMapper sources=mock(IndustryOrderSourceMapper.class);
    private final CreateIntentMapper intents=mock(CreateIntentMapper.class);
    private final IndustryOrderService orders=mock(IndustryOrderService.class);
    private final IndustryCheckoutService checkout=mock(IndustryCheckoutService.class);
    private final AbstractPlatformTransactionManager manager=new AbstractPlatformTransactionManager() {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction,TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    };
    private final IndustryTransactionRecoveryServiceImpl service=new IndustryTransactionRecoveryServiceImpl(
            sources,intents,orders,checkout,manager);

    private OrdIndustryOrderSource row(long id,String order) {
        var row=new OrdIndustryOrderSource(); row.setId(id); row.setVersion(0L); row.setTenantNo("MAIN");
        row.setEntityNo("E1"); row.setStoreNo("ST1"); row.setBuyerNo("U1"); row.setActorNo("U1");
        row.setOrderNo(order); row.setSubOrderNo("S"+order); row.setStatus("BOUND"); row.setPaymentState("UNKNOWN");
        row.setPaymentOutTradeNo(order+"-2"); row.setRequestHash("original-request");
        return row;
    }

    private void claims() {
        when(sources.update(isNull(),any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return 1;
        });
    }

    @Test void poisonAndPendingKeepOriginalReferencesAndCannotStarveNextRowOrInitiatePayment() {
        var first=row(1,"O1"); var second=row(2,"O2"); claims();
        when(sources.selectList(any())).thenReturn(List.of(first,second));
        when(checkout.queryPayment(any(),eq("O1"))).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            throw new IllegalStateException("owner timeout");
        });
        when(checkout.queryPayment(any(),eq("O2"))).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null; // original outcome remains pending; no replacement reference is created
        });
        var batch=service.recoverDue(1000,200);
        assertThat(batch.claimed()).isEqualTo(2); assertThat(batch.failed()).isEqualTo(1); assertThat(batch.pending()).isEqualTo(1);
        assertThat(first.getPaymentOutTradeNo()).isEqualTo("O1-2"); assertThat(second.getPaymentOutTradeNo()).isEqualTo("O2-2");
        verify(checkout).queryPayment(new IndustryOrderPort.Identity("MAIN","E1","ST1","U1","U1"),"O2");
        verify(checkout,never()).pay(any()); verify(orders,never()).place(any(),anyString());
        verify(orders,never()).cancel(any(),anyString());
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test void preparedRecoveryOnlyQueriesExactOriginalIntentKeyOutsideTransaction() {
        var row=row(1,"O1"); row.setStatus("PREPARED"); claims();
        when(sources.selectList(any())).thenReturn(List.of(row));
        var intent=new OrdCreateIntent(); intent.setIdemKey("original-key"); intent.setRequestHash("original-request");
        when(intents.selectOne(any())).thenReturn(intent);
        when(orders.query(any(),eq("original-key"))).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new IndustryOrderPort.Result("O1","SO1","Q1","RELEASED",null,null,1,2,List.of());
        });
        assertThat(service.recoverDue(1000,200).complete()).isEqualTo(1);
        verifyNoInteractions(checkout); verify(orders,never()).place(any(),anyString());
        verify(orders,never()).cancel(any(),anyString());
    }

    @Test void failedClaimCannotCallOwnerAndBatchIsHardBounded() {
        when(sources.selectList(any())).thenReturn(List.of(row(1,"O1"),row(2,"O2")));
        when(sources.update(isNull(),any())).thenReturn(0);
        assertThat(service.recoverDue(1000,1).claimed()).isZero();
        verify(sources,times(1)).update(isNull(),any()); verifyNoInteractions(checkout,orders,intents);
    }
}
