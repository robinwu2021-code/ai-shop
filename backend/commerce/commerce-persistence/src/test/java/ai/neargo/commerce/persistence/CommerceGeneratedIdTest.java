package ai.neargo.commerce.persistence;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.persistence.catalog.*;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class CommerceGeneratedIdTest {
    @Test
    void typedInsertReturnsDatabaseGeneratedIdRatherThanSuppliedBusinessIdentity() {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var repo = new SysBusinessTypeRepository(db.ds);
            var uow = new JdbcCommerceUnitOfWork(db.ds, new DataSourceTransactionManager(db.ds));
            var row = new SysBusinessTypeRecord(900L, "A", LocalDateTime.of(2026, 11, 3, 0, 0), LocalDateTime.of(2026, 11, 3, 0, 0), null, null, 0L, 0, "RETAIL", "Retail", 0, 0, 0L, null);
            Object generated = uow.required(() -> assertDoesNotThrow(() -> repo.getClass().getMethod("insert", SysBusinessTypeRecord.class).invoke(repo, row)));
            assertEquals(1L, generated);
            assertEquals("RETAIL", repo.findById("A", ((Number) generated).longValue()).orElseThrow().businessTypeCode());
            assertTrue(repo.findById("A", 900L).isEmpty());
        }
    }
}
