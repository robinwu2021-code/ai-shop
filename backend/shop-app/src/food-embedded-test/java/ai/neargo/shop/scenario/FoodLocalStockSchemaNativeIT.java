package ai.neargo.shop.scenario;

import ai.neargo.shop.food.config.FoodSchemaReady;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Uses the dedicated persistent local MySQL without assuming unrelated business rows are absent. */
class FoodLocalStockSchemaNativeIT extends FoodEmbeddedStartupIT {
    @Override
    @Test
    void nativeMigrationsAndRealHostStartTogether() {
        var ready = context.getBean(FoodSchemaReady.class);
        assertThat(ready.validated()).isTrue();
        assertThat(Integer.parseInt(ready.version())).isGreaterThanOrEqualTo(33);
        assertThat(ready.missingCapabilities()).noneMatch(value -> value.startsWith("fnb_stock_"));
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema=DATABASE() AND table_name IN "
                + "('fnb_stock_item','fnb_stock_balance','fnb_stock_reservation',"
                + "'fnb_stock_reservation_item','fnb_stock_command','fnb_stock_movement')",
                Integer.class)).isEqualTo(6);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_flyway_history "
                + "WHERE version='33' AND success=1", Integer.class)).isEqualTo(1);
        String tag = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String entity = "T" + tag, item = "I" + tag;
        var manager = context.getBean("transactionManager", org.springframework.transaction.PlatformTransactionManager.class);
        new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(tx -> {
            sql.update("INSERT INTO fnb_stock_item "
                    + "(tenant_no,entity_no,created_at,created_by,updated_at,updated_by,item_no,item_kind,name,base_unit,tracking_mode,status) "
                    + "VALUES ('MAIN',?,NOW(3),'TEST',NOW(3),'TEST',?,'MATERIAL','test','EA','TRACKED','ACTIVE')",
                    entity, item);
            String balanceSql = "INSERT INTO fnb_stock_balance "
                    + "(tenant_no,entity_no,store_no,created_at,created_by,updated_at,updated_by,balance_no,item_no,on_hand,reserved_qty) "
                    + "VALUES ('MAIN',?,?,NOW(3),'TEST',NOW(3),'TEST',?,?,?,?)";
            sql.update(balanceSql, entity, "S1", "B1" + tag, item, 5, 1);
            sql.update(balanceSql, entity, "S2", "B2" + tag, item, 2, 0);
            assertThatThrownBy(() -> sql.update(balanceSql, entity, "S1", "B3" + tag, item, 3, 0))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> sql.update(balanceSql, entity, "S3", "B4" + tag, item, 1, 2))
                    .isInstanceOf(RuntimeException.class);
            var balances = context.getBean(ai.neargo.shop.food.mapper.FoodMappers.StockBalanceMapper.class);
            ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() -> {
                var first = balances.lockBalance(entity, "S1", item);
                assertThat(first).isNotNull();
                assertThat(first.getVersion()).isZero();
                assertThat(balances.lockBalance(entity, "S3", item)).isNull();
                assertThat(balances.lockBalance("OTHER", "S1", item)).isNull();
                assertThat(balances.replaceLockedBalance(entity, "S1", item, 0L,
                        new java.math.BigDecimal("4.75"), new java.math.BigDecimal("1"), "TEST")).isEqualTo(1);
                assertThat(balances.replaceLockedBalance(entity, "S1", item, 0L,
                        new java.math.BigDecimal("4"), new java.math.BigDecimal("1"), "TEST")).isZero();
                assertThat(balances.replaceLockedBalance(entity, "S3", item, 1L,
                        new java.math.BigDecimal("4"), new java.math.BigDecimal("1"), "TEST")).isZero();
                var changed = balances.lockBalance(entity, "S1", item);
                assertThat(changed.getVersion()).isEqualTo(1L);
                assertThat(changed.getOnHand()).isEqualByComparingTo("4.75");
                return null;
            });
            tx.setRollbackOnly();
        });
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_stock_item WHERE entity_no=?", Integer.class, entity))
                .isZero();
    }
}
