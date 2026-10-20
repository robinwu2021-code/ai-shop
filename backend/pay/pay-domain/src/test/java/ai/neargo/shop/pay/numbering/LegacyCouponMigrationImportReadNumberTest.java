package ai.neargo.shop.pay.numbering;

import ai.neargo.shop.numbering.DocumentType;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.mapper.entitlement.EntMigrationImportMapper;
import ai.neargo.shop.pay.migration.entity.EntMigrationImport;
import ai.neargo.shop.pay.migration.service.impl.LegacyCouponMigrationImportServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LegacyCouponMigrationImportReadNumberTest {
    @Test void shortImportNumberFindsReceiptWhileLegacyPlanNumberKeepsItsOriginalQuery() throws Exception {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "migration-number-read"), EntMigrationImport.class);
        var mapper = mock(EntMigrationImportMapper.class);
        var documents = mock(PayOperationsDocuments.class);
        String shortNo = "MGI20261020000001";
        when(documents.internal(DocumentType.ENTITLEMENT_MIGRATION_IMPORT,"MAIN",shortNo)).thenReturn("MI-receipt");
        when(documents.internal(DocumentType.ENTITLEMENT_MIGRATION_IMPORT,"MAIN","MP-plan")).thenReturn("MP-plan");
        when(documents.find(DocumentType.ENTITLEMENT_MIGRATION_IMPORT,"MAIN","MI-receipt")).thenReturn(shortNo);
        var row = new EntMigrationImport();row.setTenantNo("MAIN");row.setEntityNo("M1");row.setImportNo("MI-receipt");
        row.setPlanNo("MP-plan");row.setSourceDecisionNo("D1");row.setPlanHash("a".repeat(64));row.setStatus("IMPORTED");
        row.setAssetCount(1L);row.setArchivalCount(0L);row.setImportedAt(java.time.LocalDateTime.now());
        var sql = new java.util.ArrayList<String>();
        when(mapper.selectOne(any(Wrapper.class))).thenAnswer(call -> {
            Wrapper<?> query=call.getArgument(0);sql.add(query.getSqlSegment());return row;
        });
        var service = new LegacyCouponMigrationImportServiceImpl(null,null,null,null,null,mapper,new ObjectMapper());
        var field = LegacyCouponMigrationImportServiceImpl.class.getDeclaredField("documents");field.setAccessible(true);field.set(service,documents);
        var scope = new CatalogScope("MAIN","M1","OPS",true);
        assertThat(service.get(scope,shortNo).documentNo()).isEqualTo(shortNo);
        assertThat(service.get(scope,"MP-plan").importNo()).isEqualTo("MI-receipt");
        assertThat(sql.get(0)).contains("tenant_no", "entity_no", "import_no").doesNotContain("plan_no");
        assertThat(sql.get(1)).contains("tenant_no", "entity_no", "plan_no").doesNotContain("import_no");
    }
}
