package ai.neargo.shop.trade.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.trade.entity.OrdOfflineRecord;
import ai.neargo.shop.trade.mapper.OfflinePaymentMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Exercises the canonical receipt journal against exact DDL, without a legacy receipt table. */
@EnabledIfSystemProperty(named = "offline.payment.mysql.url",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:3308/offline_payment_[a-z0-9_]+\\?.*")
class OfflinePaymentCanonicalMysqlTest {

    @Test
    void scopesReplayKeepsBusinessReferenceAndWritesOneReversal() throws Exception {
        var ds = new DriverManagerDataSource(System.getProperty("offline.payment.mysql.url"),
                System.getProperty("offline.payment.mysql.user"),
                Files.readString(Path.of(System.getProperty("offline.payment.mysql.password-file"))).trim());
        var jdbc = new JdbcTemplate(ds);
        String doc = Files.readString(Path.of("../../docs/technical/design/schema/ddl/05-n_trade.sql"));
        int from = doc.indexOf("CREATE TABLE `ord_payment` (");
        jdbc.execute(doc.substring(from, doc.indexOf("\n\n", from)));
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(OfflinePaymentMapper.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "offline-payment"), OrdOfflineRecord.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(configuration);
        var payments = new SqlSessionTemplate(factory.getObject()).getMapper(OfflinePaymentMapper.class);

        var record = new OrdOfflineRecord();
        record.setTenantNo("MAIN");
        record.setEntityNo("E1");
        record.setStoreNo("S1");
        record.setRecordNo("R1");
        record.setCurrency("TWD");
        record.setMethod("CASH");
        record.setBizNo("B".repeat(64));
        record.setOrderNo("SUB1");
        record.setAmountMinor(250L);
        record.setRequestHash("c".repeat(64));
        record.setOfflineHistory("[{\"action\":\"CREATE\"}]");
        record.setVersion(1L);
        record.setReceivedAt(LocalDateTime.of(2026, 11, 28, 9, 0));
        record.setCreatedAt(record.getReceivedAt());
        assertThat(payments.insert(record, "ORDER1", "k".repeat(64))).isEqualTo(1);
        assertThatThrownBy(() -> payments.insert(record, "ORDER1", "k".repeat(64)))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(payments.findKey("MAIN", "E1", "S1", "k".repeat(64)).getRequestHash())
                .isEqualTo(record.getRequestHash());
        assertThat(payments.findKey("MAIN", "E1", "S2", "k".repeat(64))).isNull();
        assertThat(jdbc.queryForMap("SELECT order_no,sub_order_no,biz_no FROM ord_payment WHERE record_no='R1'"))
                .containsEntry("order_no", "ORDER1").containsEntry("sub_order_no", "SUB1")
                .containsEntry("biz_no", record.getBizNo());
        assertThat(payments.list(Wrappers.<OrdOfflineRecord>lambdaQuery()
                .eq(OrdOfflineRecord::getTenantNo, "MAIN").eq(OrdOfflineRecord::getBizNo, record.getBizNo())))
                .extracting(OrdOfflineRecord::getRecordNo).containsExactly("R1");
        assertThat(payments.sumRecordedOfOrder("MAIN", "E1", "S1", "SUB1", "TWD")).isEqualTo(250);

        record.setStatus(OrdOfflineRecord.REFUNDED);
        record.setCorrected(false);
        record.setOfflineHistory("[{\"action\":\"CREATE\"},{\"action\":\"REFUND\"}]");
        assertThat(payments.update(record)).isEqualTo(1);
        assertThat(payments.update(record)).isZero();
        assertThat(payments.insertReversal(record, "R2", record.getReceivedAt(), "STAFF1")).isEqualTo(1);
        assertThat(payments.lock("MAIN", "E1", "S1", "R1").getOfflineHistory()).contains("REFUND");
        assertThat(payments.sumRecordedOfOrder("MAIN", "E1", "S1", "SUB1", "TWD")).isZero();
        assertThat(jdbc.queryForObject("SELECT amount_minor FROM ord_payment WHERE corrects_record_no='R1'",
                Long.class)).isEqualTo(-250);
    }
}
