package ai.neargo.shop.retail.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.retail.commerce.dto.RetailSelection;
import ai.neargo.shop.retail.commerce.dto.RetailSelectionContracts;
import ai.neargo.shop.retail.commerce.dto.RetailSelectionManagementContracts;
import ai.neargo.shop.retail.commerce.mapper.impl.RetailSelectionMapperImpl;
import ai.neargo.shop.retail.commerce.service.RetailSelectionManagementService;
import ai.neargo.shop.retail.commerce.service.RetailSelectionService;
import ai.neargo.shop.retail.commerce.service.impl.RetailSelectionManagementServiceImpl;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

class RetailSelectionManagementServiceTest {
    JdbcTemplate jdbc;
    RetailSelectionMapperImpl repository;
    RetailSelectionManagementService management;
    final RetailSelectionService publication = mock(RetailSelectionService.class);
    final RetailSelection published =
            new RetailSelection(
                    "E",
                    "S",
                    "SEL",
                    "R1",
                    "C",
                    "CR",
                    List.of(new RetailSelection.Entry("G", 7, "a".repeat(64), List.of("SKU"))));

    @BeforeEach
    void database() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        var manager = new DataSourceTransactionManager(ds);
        var json = new ObjectMapper();
        jdbc.execute(
                "CREATE TABLE rtl_channel_selection(entity_no VARCHAR(32),store_no"
                        + " VARCHAR(32),selection_no VARCHAR(32),revision_no VARCHAR(32),PRIMARY"
                        + " KEY(entity_no,store_no,selection_no))");
        jdbc.execute(
                "CREATE TABLE rtl_channel_selection_revision(entity_no VARCHAR(32),store_no"
                    + " VARCHAR(32),selection_no VARCHAR(32),revision_no VARCHAR(32),content_hash"
                    + " VARCHAR(64),payload_json TEXT,PRIMARY "
                    + "KEY(entity_no,store_no,selection_no,revision_no))");
        jdbc.execute(
                "CREATE TABLE rtl_channel_selection_draft(entity_no VARCHAR(32),store_no"
                        + " VARCHAR(32),selection_no VARCHAR(32),version BIGINT,payload_json"
                        + " TEXT,PRIMARY KEY(entity_no,store_no,selection_no))");
        jdbc.execute(
                "CREATE TABLE rtl_channel_selection_command(entity_no VARCHAR(32),store_no"
                        + " VARCHAR(32),action_key VARCHAR(32),key_hash VARCHAR(64),actor_no"
                        + " VARCHAR(32),request_hash VARCHAR(64),result_json TEXT,PRIMARY"
                        + " KEY(entity_no,store_no,action_key,key_hash))");
        repository = new RetailSelectionMapperImpl(jdbc, manager, json);
        management =
                new RetailSelectionManagementServiceImpl(
                        jdbc, manager, json, repository, publication);
        when(publication.prepareWithEvidence(any()))
                .thenAnswer(
                        call -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            return new RetailSelectionContracts.Prepared(
                                    published, Instant.parse("2026-10-29T01:00:00Z"));
                        });
    }

    RetailSelectionManagementContracts.SaveRequest save(long version) {
        return new RetailSelectionManagementContracts.SaveRequest(
                version,
                null,
                "C",
                "CR",
                List.of(new RetailSelectionContracts.Pick("G", List.of("SKU"))));
    }

    RetailSelectionManagementContracts.View create() {
        return management.save("E", "S", "SEL", "ACTOR", save(0), "SAVE1");
    }

    @Test
    void draftNeverPublishesUntilOriginalPreparationSucceeds() {
        var draft = create();
        assertThat(draft.version()).isEqualTo(1);
        assertThat(draft.published()).isNull();
        assertThat(repository.current("E", "S", "SEL")).isNull();
        verifyNoInteractions(publication);
        var result =
                management.publish(
                        "E",
                        "S",
                        "SEL",
                        "ACTOR",
                        new RetailSelectionManagementContracts.PublishRequest(1L),
                        "PUB1");
        assertThat(result.version()).isEqualTo(2);
        assertThat(result.published()).isEqualTo(published);
        assertThat(repository.current("E", "S", "SEL")).isEqualTo(published);
    }

    @Test
    void savedIdempotencyReturnsFrozenResponseAfterDraftChanges() {
        var first = create();
        management.save("E", "S", "SEL", "ACTOR", save(1), "SAVE2");
        assertThat(management.save("E", "S", "SEL", "ACTOR", save(0), "SAVE1")).isEqualTo(first);
        assertThat(management.detail("E", "S", "SEL").version()).isEqualTo(2);
    }

    @Test
    void sameKeyCannotBeReboundToDifferentActorOrRequest() {
        create();
        assertThatThrownBy(() -> management.save("E", "S", "SEL", "OTHER", save(0), "SAVE1"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> management.save("E", "S", "SEL", "ACTOR", save(1), "SAVE1"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void publishReplayDoesNotReadCurrentOwnersAgain() {
        create();
        var request = new RetailSelectionManagementContracts.PublishRequest(1L);
        var first = management.publish("E", "S", "SEL", "ACTOR", request, "PUB1");
        when(publication.prepareWithEvidence(any()))
                .thenThrow(new IllegalStateException("owner unavailable"));
        assertThat(management.publish("E", "S", "SEL", "ACTOR", request, "PUB1")).isEqualTo(first);
        verify(publication, times(1)).prepareWithEvidence(any());
    }

    @Test
    void staleVersionAndForeignStoreCannotMutateDraft() {
        create();
        assertThatThrownBy(() -> management.save("E", "S", "SEL", "ACTOR", save(9), "SAVE2"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> management.detail("E", "FOREIGN", "SEL"))
                .isInstanceOf(BizException.class);
        assertThat(management.list("OTHER", "S", 0, 20)).isEmpty();
        assertThat(management.detail("E", "S", "SEL").version()).isEqualTo(1);
    }

    @Test
    void failedOriginalPreparationLeavesNoHeadOrSuccessfulPublishJournal() {
        create();
        when(publication.prepareWithEvidence(any()))
                .thenThrow(new IllegalStateException("missing original source"));
        assertThatThrownBy(
                        () ->
                                management.publish(
                                        "E",
                                        "S",
                                        "SEL",
                                        "ACTOR",
                                        new RetailSelectionManagementContracts.PublishRequest(1L),
                                        "PUB1"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(repository.current("E", "S", "SEL")).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM rtl_channel_selection_command WHERE "
                                        + "action_key='PUBLISH'",
                                Long.class))
                .isZero();
        assertThat(management.detail("E", "S", "SEL").version()).isEqualTo(1);
    }

    @Test
    void expiredPreparedEvidenceRollsBackDraftVersionAndRequestJournal() {
        create();
        doThrow(new IllegalStateException("expired original evidence"))
                .when(publication)
                .assertFresh(any());
        assertThatThrownBy(
                        () ->
                                management.publish(
                                        "E",
                                        "S",
                                        "SEL",
                                        "ACTOR",
                                        new RetailSelectionManagementContracts.PublishRequest(1L),
                                        "PUB1"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(repository.current("E", "S", "SEL")).isNull();
        assertThat(management.detail("E", "S", "SEL").version()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM rtl_channel_selection_command WHERE "
                                        + "action_key='PUBLISH'",
                                Long.class))
                .isZero();
    }
}
