package ai.neargo.shop.retail.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.retail.commerce.dto.RetailSelection;
import ai.neargo.shop.retail.commerce.mapper.impl.RetailSelectionMapperImpl;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

class JdbcRetailSelectionRepositoryTest {
    JdbcTemplate jdbc;
    RetailSelectionMapperImpl repository;

    @BeforeEach
    void database() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute(
                "CREATE TABLE rtl_channel_selection(entity_no VARCHAR(32),store_no"
                        + " VARCHAR(32),selection_no VARCHAR(32),revision_no VARCHAR(32),PRIMARY"
                        + " KEY(entity_no,store_no,selection_no))");
        jdbc.execute(
                "CREATE TABLE rtl_channel_selection_revision(entity_no VARCHAR(32),store_no"
                    + " VARCHAR(32),selection_no VARCHAR(32),revision_no VARCHAR(32),content_hash"
                    + " VARCHAR(64),payload_json TEXT,PRIMARY "
                    + "KEY(entity_no,store_no,selection_no,revision_no))");
        repository =
                new RetailSelectionMapperImpl(
                        jdbc, new DataSourceTransactionManager(ds), new ObjectMapper());
    }

    RetailSelection selection(String revision) {
        return new RetailSelection(
                "E",
                "S",
                "SEL",
                revision,
                "C",
                "CR",
                List.of(new RetailSelection.Entry("G", 7, "a".repeat(64), List.of("SKU"))));
    }

    @Test
    void publishKeepsHistoricalReferencesAndMovesOnlyHead() {
        var first = selection("R1");
        var second = selection("R2");
        repository.publish(first, null);
        repository.publish(second, "R1");
        assertThat(repository.current("E", "S", "SEL")).isEqualTo(second);
        assertThat(repository.revision("E", "S", "SEL", "R1")).isEqualTo(first);
        assertThat(repository.current("E", "FOREIGN", "SEL")).isNull();
    }

    @Test
    void losingCompareAndSwapRollsBackNewRevision() {
        repository.publish(selection("R1"), null);
        assertThatThrownBy(() -> repository.publish(selection("R2"), "WRONG"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(repository.revision("E", "S", "SEL", "R2")).isNull();
        assertThat(repository.current("E", "S", "SEL")).isEqualTo(selection("R1"));
    }

    @Test
    void existingRevisionCannotBeRewritten() {
        repository.publish(selection("R1"), null);
        assertThatThrownBy(() -> repository.publish(selection("R1"), "R1"))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(repository.revision("E", "S", "SEL", "R1")).isEqualTo(selection("R1"));
    }

    @Test
    void corruptStoredHashCannotBecomeOwnerProof() {
        repository.publish(selection("R1"), null);
        jdbc.update("UPDATE rtl_channel_selection_revision SET content_hash=?", "b".repeat(64));
        assertThatThrownBy(() -> repository.current("E", "S", "SEL"))
                .isInstanceOf(IllegalStateException.class);
    }
}
