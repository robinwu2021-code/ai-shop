package ai.neargo.shop.trade.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.mapper.PosActivitySaleMapper;
import ai.neargo.shop.trade.mapper.PosActivitySaleMapper.Line;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Uses the canonical DDL in a disposable, explicitly configured MySQL 9 schema. */
@EnabledIfSystemProperty(named = "pos.sale.mysql.url",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:3308/pos_sale_[a-z0-9_]+\\?.*")
class PosSaleCanonicalMysqlTest {

    @Test
    void linksStableLinesAndReportsCommittedActivityWithPaymentTimeMembership() throws Exception {
        var dataSource = new DriverManagerDataSource(System.getProperty("pos.sale.mysql.url"),
                System.getProperty("pos.sale.mysql.user"),
                Files.readString(Path.of(System.getProperty("pos.sale.mysql.password-file"))).trim());
        var db = new JdbcTemplate(dataSource);
        var ddl = Files.readString(Path.of("../../docs/technical/design/schema/ddl/05-n_trade.sql"));
        for (String table : List.of("ord_order", "ord_sub_order", "ord_item", "ord_price_quote",
                "ord_price_quote_line", "ord_price_quote_allocation", "ord_payment",
                "ord_after_sale", "ord_after_sale_item")) {
            String prefix = "CREATE TABLE `" + table + "` (";
            int start = ddl.indexOf(prefix);
            int end = ddl.indexOf("\n\n", start);
            db.execute(ddl.substring(start, end));
        }
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(PosActivitySaleMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        var facts = new SqlSessionTemplate(factory.getObject()).getMapper(PosActivitySaleMapper.class);

        db.update("""
            INSERT INTO ord_order(order_no,entity_no,store_no,user_no,currency,pay_amount,admission_kind,status)
            VALUES ('O1','E1','S1','U1','TWD',280,'STORE_SALE','WAIT_PAY')
            """);
        db.update("""
            INSERT INTO ord_sub_order(sub_order_no,order_no,entity_no,store_no,admission_kind)
            VALUES ('S01','O1','E1','S1','STORE_SALE')
            """);
        db.update("INSERT INTO ord_item(id,sub_order_no,qty,paid_share_minor) VALUES (1,'S01',2,230),(2,'S01',1,50)");
        var order = new OrdOrder();
        order.setTenantNo("MAIN");
        order.setOrderNo("O1");
        order.setEntityNo("E1");
        order.setStoreNo("S1");
        order.setUserNo("U1");
        order.setCurrency("TWD");
        order.setGoodsAmount(300L);
        order.setDiscountAmount(20L);
        order.setPayAmount(280L);
        String version = "0123456789abcdef".repeat(4);
        PosSaleQuote.record(facts, order, "S01", "M1", 1, List.of(item(1, 2, 125, 230), item(2, 1, 50, 50)),
                List.of(new PosSaleQuote.Share(0, 0, "AP1", "ACT1", version, 250, 20)), new long[]{20, 0});

        assertThat(facts.canonicalFacts("MAIN", "S01", "O1")).isEqualTo(new PosActivitySaleMapper.Facts(1, "M1"));
        assertThat(db.queryForObject("""
            SELECT COUNT(*) FROM ord_item i JOIN ord_sub_order s ON s.tenant_no=i.tenant_no AND s.sub_order_no=i.sub_order_no
            JOIN ord_price_quote_line q ON q.tenant_no=i.tenant_no AND q.quote_no=s.quote_no AND q.source_line_no=i.line_no
            """, Long.class)).isEqualTo(2);
        assertThat(db.queryForObject("""
            SELECT CONCAT(rule_version,':',JSON_VALUE(evidence_snapshot,'$.activityVersion'))
            FROM ord_price_quote_allocation
            """, String.class)).isEqualTo(version.substring(0, 32) + ":" + version);
        assertThat(facts.canonicalCoverage("MAIN", "E1", "S1", 2000)).isEmpty();

        // Payment still honors the frozen amount after the checkout member's eligibility lapses.
        assertThat(facts.recordCanonicalMembership("MAIN", "S01", "O1", false)).isEqualTo(1);
        assertThat(facts.decideAllocations("MAIN", "S01", "O1", "COMMITTED")).isEqualTo(1);
        assertThat(facts.decideAllocations("MAIN", "S01", "O1", "CANCELLED")).isZero();
        db.update("UPDATE ord_order SET status='PAID',paid_at=1000 WHERE order_no='O1'");
        db.update("UPDATE ord_sub_order SET status='COMPLETED' WHERE sub_order_no='S01'");
        var endUtc = LocalDateTime.of(2026, 11, 28, 0, 0);
        assertThat(facts.canonicalCoverage("MAIN", "E1", "S1", 2000))
                .containsExactly(new PosActivitySaleMapper.Coverage("TWD", 1000L));
        assertThat(facts.canonicalLines("MAIN", "E1", "OTHER", "ACT1", 0, 2000, endUtc)).isEmpty();
        assertThat(facts.canonicalLines("MAIN", "E1", "S1", "OTHER", 0, 2000, endUtc)).isEmpty();
        var lines = facts.canonicalLines("MAIN", "E1", "S1", "ACT1", 0, 2000, endUtc);
        assertThat(lines).extracting(Line::memberAtPayment).containsExactly(false, false);
        assertThat(lines).extracting(Line::discount).containsExactly(20L, 0L);
        assertThat(lines).extracting(Line::paid).containsExactly(230L, 50L);
    }

    private static OrdItem item(long id, int quantity, long unitPrice, long paid) {
        var item = new OrdItem();
        item.setId(id);
        item.setGoodsNo("G1");
        item.setSkuNo("SKU1");
        item.setTitle("Item");
        item.setQty(quantity);
        item.setPrice(unitPrice);
        item.setAmount(unitPrice * quantity);
        item.setPaidShareMinor(paid);
        return item;
    }
}
