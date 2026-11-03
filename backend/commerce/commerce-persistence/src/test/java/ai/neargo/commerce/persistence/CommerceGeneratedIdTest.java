package ai.neargo.commerce.persistence;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.persistence.catalog.*;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.time.LocalDateTime;

class CommerceGeneratedIdTest {
    @Test
    void typedInsertReturnsDatabaseGeneratedIdRatherThanSuppliedBusinessIdentity() {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var repo = new SysBusinessTypeRepository(db.ds);
            var uow = new JdbcCommerceUnitOfWork(db.ds, new DataSourceTransactionManager(db.ds));
            var row =
                    new SysBusinessTypeRecord(
                            900L,
                            "A",
                            LocalDateTime.of(2026, 11, 3, 0, 0),
                            LocalDateTime.of(2026, 11, 3, 0, 0),
                            null,
                            null,
                            0L,
                            0,
                            "RETAIL",
                            "Retail",
                            0,
                            0,
                            0L,
                            null);
            long generated = uow.required(() -> repo.insert(row));
            assertEquals(1L, generated);
            assertEquals("RETAIL", repo.findById("A", generated).orElseThrow().businessTypeCode());
            assertTrue(repo.findById("A", 900L).isEmpty());
        }
    }
}
