package ai.neargo.shop.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import ai.neargo.shop.platform.impl.OpsServiceImpl;
import ai.neargo.shop.platform.entity.MchEntityApply;
import ai.neargo.shop.platform.mapper.PlatformMappers.MerchantApplyMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class AssistedApplySearchTest {
    @Test void unifiedSearchAppliesSystemMarketAndStableNewestOrderBeforePagination() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),""),MchEntityApply.class);
        var mapper=mock(MerchantApplyMapper.class);
        var service=mock(OpsServiceImpl.class,CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service,"applyMapper",mapper);
        when(mapper.selectPage(any(Page.class),any())).thenAnswer(i->i.getArgument(0));
        service.searchApplies("APPROVED",null,1,40,true,"TW",true);
        var where=ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectPage(any(Page.class),where.capture());
        String sql=where.getValue().getSqlSegment();
        assertTrue(sql.contains("market_code ="));assertTrue(sql.contains("submitted_by IS NOT NULL"));
        assertTrue(sql.contains("created_at DESC,apply_no DESC"));
        assertTrue(((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?,?,?>)where.getValue()).getParamNameValuePairs().containsValue("TW"));
    }
    @Test void assistedScopeIsAppliedInSqlBeforePaginationAndLegacySearchKeepsAllSources() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),""),MchEntityApply.class);
        var mapper=mock(MerchantApplyMapper.class);
        var service=mock(OpsServiceImpl.class,CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service,"applyMapper",mapper);
        when(mapper.selectPage(any(Page.class),any())).thenAnswer(i->i.getArgument(0));
        service.searchApplies("APPROVED","Breakfast",2,10,true);
        var page=ArgumentCaptor.forClass(Page.class);var where=ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectPage(page.capture(),where.capture());
        assertEquals(2,page.getValue().getCurrent());assertEquals(10,page.getValue().getSize());
        assertTrue(where.getValue().getSqlSegment().contains("submitted_by IS NOT NULL"));
        clearInvocations(mapper);
        service.searchApplies(null,null,1,20);
        verify(mapper).selectPage(any(Page.class),where.capture());
        assertFalse(where.getValue().getSqlSegment().contains("submitted_by"));
    }
}
