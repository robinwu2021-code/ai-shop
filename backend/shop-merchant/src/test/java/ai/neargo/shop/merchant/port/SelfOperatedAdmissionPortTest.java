package ai.neargo.shop.merchant.port;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.common.data.scope.DataScopeSpec;
import java.util.Set;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SelfOperatedAdmissionPortTest {
    @Test void filtersPlatformOwnershipRegionAndArchiveBeforeBoundedPageAndProjectsActualFields() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),""),MchEntity.class);
        var mapper=mock(MchEntityMapper.class);
        var entity=new MchEntity();entity.setEntityNo("M1");entity.setName("Platform");
        entity.setOwnerUserNo("OWNER");entity.setStatus("ACTIVE");entity.setServiceScope("CITY");
        entity.setCreatedAt(LocalDateTime.of(2026,11,13,10,0));
        when(mapper.selectPage(any(Page.class),any())).thenAnswer(i->{
            Page<MchEntity> p=i.getArgument(0);p.setRecords(List.of(entity));p.setTotal(77);return p;
        });
        var result=new SelfOperatedAdmissionPortImpl(mapper).search("TW","ACTIVE","Platform",40);
        assertEquals(77,result.total());assertEquals("M1",result.records().getFirst().merchantNo());
        assertEquals("OWNER",result.records().getFirst().ownerUserNo());
        var where=ArgumentCaptor.forClass(Wrapper.class);var page=ArgumentCaptor.forClass(Page.class);
        verify(mapper).selectPage(page.capture(),where.capture());assertEquals(40,page.getValue().getSize());
        String sql=where.getValue().getSqlSegment();
        assertTrue(sql.contains("self_operated ="));assertTrue(sql.contains("market ="));
        assertTrue(sql.contains("archived_at IS NULL"));assertTrue(sql.contains("status ="));
        assertTrue(sql.contains("created_at DESC,entity_no DESC"));
    }
    @Test void keepsCallerDataScopeEnabledAtTheMapperBoundary() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),""),MchEntity.class);
        var mapper=mock(MchEntityMapper.class);
        var scope=DataScopeSpec.of("MERCHANT",Set.of("VISIBLE_ENTITY"));
        DataScopeContext.set(scope);
        try {
            when(mapper.selectPage(any(Page.class),any())).thenAnswer(i->{
                assertFalse(DataScopeContext.isSkipped(),"Operator search must preserve row-level filtering");
                assertSame(scope,DataScopeContext.current());
                return i.getArgument(0);
            });
            var result=new SelfOperatedAdmissionPortImpl(mapper).search("TW",null,null,20);
            assertTrue(result.records().isEmpty());
        } finally {DataScopeContext.clear();}
    }
}
