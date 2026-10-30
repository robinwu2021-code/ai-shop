package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStore.SessionData;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.media.MediaPurgeService;
import ai.neargo.shop.media.MediaReconcileJob;
import ai.neargo.shop.media.MediaScanner;
import ai.neargo.shop.media.MediaStore;
import ai.neargo.shop.media.MediaUploadService;
import ai.neargo.shop.media.MediaUsageService;
import ai.neargo.shop.media.SysMediaAssetMapper;
import ai.neargo.shop.spi.media.ReceiptEvidencePort;
import ai.neargo.shop.spi.media.ReceiptEvidencePort.ReceiptEvidenceQuery;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.ObjectMapper;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;

import javax.imageio.ImageIO;

/**
 * Real private bytes and owner rows, with cleanup limited to this test class's own UUID directory.
 */
@EnabledIfSystemProperty(named = "checkout.test.native", matches = "true")
class ReceiptEvidenceFlowTest extends CheckoutFlowFixture {
    private static final Path DIRECTORY =
            Path.of(
                    System.getProperty("java.io.tmpdir"),
                    "task57-receipt-media-" + UUID.randomUUID());
    @Autowired private MediaUploadService uploads;
    @MockitoSpyBean private MediaStore bytes;
    @Autowired private MediaScanner scanner;
    @Autowired private MediaPurgeService purge;
    @Autowired private MediaUsageService usage;
    @Autowired private SysMediaAssetMapper assets;
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @Autowired private ReceiptEvidencePort evidence;

    @AfterEach
    void resetByteObserver() {
        Mockito.reset(bytes);
    }

    @DynamicPropertySource
    static void privateStorage(DynamicPropertyRegistry properties) {
        properties.add("shop.upload.dir", DIRECTORY::toString);
    }

    @AfterAll
    static void cleanupOwnedDirectory() throws Exception {
        if (Files.exists(DIRECTORY)) {
            try (var paths = Files.walk(DIRECTORY)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test
    void unboundReceiptEvidenceSurvivesScannerAndExplicitOrFilteredPurge() throws Exception {
        String key = upload();
        jdbc.update(
                "UPDATE sys_media_asset SET created_at=? WHERE asset_key=?",
                LocalDateTime.now().minusDays(30),
                key);
        scanner.scan();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM sys_media_asset WHERE asset_key=?",
                                String.class,
                                key))
                .isEqualTo("ACTIVE");
        assertThat(bytes.exists(key)).isTrue();
        // Even an old erroneous queued/reclaimable state may never make this dedicated purpose
        // deletable.
        jdbc.update("UPDATE sys_media_asset SET status='RECLAIMABLE' WHERE asset_key=?", key);
        assertThatThrownBy(() -> purge.submit(List.of(key), null, null, "test-operator", "test"))
                .isInstanceOf(BizException.class);
        var filter = new MediaPurgeService.Filter(entity, store, true, null);
        assertThat(purge.preview(filter).sample()).doesNotContain(key);
        assertThat(bytes.exists(key)).isTrue();
    }

    @Test
    void alreadyQueuedReceiptCannotBeDeletedOrReceiveAnOrdinaryPreviewCredential()
            throws Exception {
        String key = upload();
        String batch = "task57-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update(
                "INSERT INTO"
                    + " sys_media_purge_batch(batch_no,operator,operator_name,status,total_count,"
                        + "total_bytes,purged_count,failed_count,created_at,updated_at)"
                    + " VALUES (?,'test-operator','test','QUEUED',1,1,0,0,NOW(),NOW())",
                batch);
        jdbc.update(
                "UPDATE sys_media_asset SET status='RECLAIMABLE',purge_batch_no=? WHERE"
                        + " asset_key=?",
                batch,
                key);
        assertThat(usage.batch(batch).items())
                .singleElement()
                .satisfies(item -> assertThat(item.thumbUrl()).isNull());
        purge.runQueued();
        assertThat(bytes.exists(key)).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM sys_media_asset WHERE asset_key=?",
                                String.class,
                                key))
                .isNotEqualTo("PURGED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT purged_count FROM sys_media_purge_batch WHERE batch_no=?",
                                Integer.class,
                                batch))
                .isZero();
    }

    @Test
    void
            pendingReconciliationRetainsAbsentBytesAndCompletesExistingBytesWithoutDeletingEitherOwnerRecord()
                    throws Exception {
        String existing = upload();
        String absent = entity + "/" + store + "/offline_receipt/202610/absent.png";
        jdbc.update(
                "UPDATE sys_media_asset SET status='PENDING',created_at=? WHERE asset_key=?",
                LocalDateTime.now().minusDays(1),
                existing);
        jdbc.update(
                "INSERT INTO"
                    + " sys_media_asset(asset_key,entity_no,store_no,biz_type,bytes,status,"
                        + "uploaded_by,created_at,updated_at)"
                    + " VALUES (?,?,?,'OFFLINE_RECEIPT',1,'PENDING',?,?,NOW())",
                absent,
                entity,
                store,
                actor,
                LocalDateTime.now().minusDays(1));
        new MediaReconcileJob(assets, bytes, null, 0).run(null);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM sys_media_asset WHERE asset_key=?",
                                String.class,
                                existing))
                .isEqualTo("ACTIVE");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM sys_media_asset WHERE asset_key=?",
                                String.class,
                                absent))
                .isEqualTo("PENDING");
        assertThat(bytes.exists(existing)).isTrue();
    }

    @Test
    void dedicatedUploadUsesCurrentCashierAccountAndActualStoreAndRemainsPrivate()
            throws Exception {
        var pool = tokens.of(Realm.MERCHANT);
        String token = pool.issue(new SessionData(LoginUser.merchant(actor, "receipt owner")));
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        try {
            var image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
            var output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            var file =
                    new MockMultipartFile("file", "receipt.png", "image/png", output.toByteArray());
            var response =
                    json.readTree(
                            mvc.perform(
                                            multipart("/biz/upload/image")
                                                    .file(file)
                                                    .header("Authorization", "Bearer " + token)
                                                    .header("X-Store-No", store)
                                                    .param("bizType", "OFFLINE_RECEIPT")
                                                    .param("entityNo", "foreign-owner")
                                                    .param("uploadedBy", "foreign-account"))
                                    .andReturn()
                                    .getResponse()
                                    .getContentAsString());
            assertThat(response.path("code").asInt()).isZero();
            String key = response.path("data").path("evidenceRef").asString();
            assertThat(key).startsWith(entity + "/" + store + "/offline_receipt/");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT uploaded_by FROM sys_media_asset WHERE asset_key=?",
                                    String.class,
                                    key))
                    .isEqualTo(actor);
            assertThat(bytes.exists(key)).isTrue();
            assertThat(mvc.perform(get("/uploads/" + key)).andReturn().getResponse().getStatus())
                    .isEqualTo(404);
            assertThat(
                            mvc.perform(get(response.path("data").path("url").asString()))
                                    .andReturn()
                                    .getResponse()
                                    .getStatus())
                    .isEqualTo(404);
            jdbc.update("UPDATE mch_account SET is_owner=0 WHERE mch_account_no=?", actor);
            var revoked =
                    json.readTree(
                            mvc.perform(
                                            multipart("/biz/upload/image")
                                                    .file(file)
                                                    .header("Authorization", "Bearer " + token)
                                                    .header("X-Store-No", store)
                                                    .param("bizType", "OFFLINE_RECEIPT"))
                                    .andReturn()
                                    .getResponse()
                                    .getContentAsString());
            assertThat(revoked.path("code").asInt()).isNotZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_media_asset WHERE entity_no=?"
                                            + " AND biz_type='OFFLINE_RECEIPT'",
                                    Long.class,
                                    entity))
                    .isEqualTo(1L);
        } finally {
            pool.revoke(token);
        }
    }

    @Test
    void authoritativeEvidenceUsesUtcActualOwnerAndBytesAndNeverAcceptsCallerTransaction()
            throws Exception {
        var originalZone = TimeZone.getDefault();
        String key;
        Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            key = upload();
        } finally {
            TimeZone.setDefault(originalZone);
        }
        Mockito.doAnswer(
                        call -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            return call.callRealMethod();
                        })
                .when(bytes)
                .exists(ArgumentMatchers.anyString());
        var query = new ReceiptEvidenceQuery("MAIN", entity, store, actor, key);
        var first = evidence.verify(query);
        assertThat(first.uploadedAt()).isBetween(before, Instant.now());
        assertThat(first.uploadedByAccountNo()).isEqualTo(actor);
        assertThat(first.factHash()).hasSize(64);
        jdbc.update(
                "UPDATE sys_media_asset SET updated_at=? WHERE asset_key=?",
                LocalDateTime.now(),
                key);
        assertThat(evidence.verify(query)).isEqualTo(first);
        assertThatThrownBy(
                        () ->
                                evidence.verify(
                                        new ReceiptEvidenceQuery(
                                                "MAIN", entity, store, buyer, key)))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(
                        () ->
                                evidence.verify(
                                        new ReceiptEvidenceQuery(
                                                "MAIN", entity, "foreign", actor, key)))
                .isInstanceOf(BizException.class);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        assertThatThrownBy(() -> tx.execute(status -> evidence.verify(query)))
                .isInstanceOf(IllegalStateException.class);
        jdbc.update("UPDATE sys_media_asset SET status='PENDING' WHERE asset_key=?", key);
        assertThatThrownBy(() -> evidence.verify(query)).isInstanceOf(BizException.class);
        jdbc.update(
                "UPDATE sys_media_asset SET status='ACTIVE',biz_type='GOODS' WHERE asset_key=?",
                key);
        assertThatThrownBy(() -> evidence.verify(query)).isInstanceOf(BizException.class);
        jdbc.update("UPDATE sys_media_asset SET biz_type='OFFLINE_RECEIPT' WHERE asset_key=?", key);
        Files.delete(DIRECTORY.resolve(key));
        assertThatThrownBy(() -> evidence.verify(query)).isInstanceOf(BizException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM sys_media_asset WHERE asset_key=?",
                                Long.class,
                                key))
                .isEqualTo(1L);
    }

    @Test
    void consumerAndGenericUploadsCannotCreateDedicatedPrivateCashEvidence() throws Exception {
        String consumer =
                tokens.of(Realm.CONSUMER)
                        .issue(new SessionData(LoginUser.consumer(buyer, "buyer")));
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        try {
            var image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
            var output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            var file =
                    new MockMultipartFile("file", "receipt.png", "image/png", output.toByteArray());
            var denied =
                    json.readTree(
                            mvc.perform(
                                            multipart("/biz/upload/image")
                                                    .file(file)
                                                    .header("Authorization", "Bearer " + consumer)
                                                    .header("X-Store-No", store)
                                                    .param("bizType", "OFFLINE_RECEIPT"))
                                    .andReturn()
                                    .getResponse()
                                    .getContentAsString());
            assertThat(denied.path("code").asInt()).isNotZero();
            var generic =
                    json.readTree(
                            mvc.perform(
                                            multipart("/mp/upload/image")
                                                    .file(file)
                                                    .header("Authorization", "Bearer " + consumer)
                                                    .param("scene", "aftersale")
                                                    .param("bizType", "OFFLINE_RECEIPT")
                                                    .param("entityNo", entity)
                                                    .param("storeNo", store)
                                                    .param("uploadedBy", actor))
                                    .andReturn()
                                    .getResponse()
                                    .getContentAsString());
            assertThat(generic.path("code").asInt()).isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT biz_type FROM sys_media_asset WHERE uploaded_by=?",
                                    String.class,
                                    buyer))
                    .isEqualTo("EVIDENCE");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_media_asset WHERE entity_no=?"
                                            + " AND biz_type='OFFLINE_RECEIPT'",
                                    Long.class,
                                    entity))
                    .isZero();
        } finally {
            tokens.of(Realm.CONSUMER).revoke(consumer);
        }
    }

    private String upload() throws Exception {
        String key = entity + "/" + store + "/offline_receipt/202610/" + UUID.randomUUID() + ".png";
        var image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        byte[] content = output.toByteArray();
        uploads.store(
                key,
                () -> new ByteArrayInputStream(content),
                content.length,
                "image/png",
                "OFFLINE_RECEIPT",
                entity,
                store,
                actor);
        return key;
    }
}
