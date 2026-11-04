package ai.neargo.shop.commercebridge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.neargo.commerce.api.common.StoreRef;
import ai.neargo.commerce.api.common.SubjectKind;
import ai.neargo.commerce.api.port.PrivateEvidencePort;
import ai.neargo.shop.media.MediaStore;
import ai.neargo.shop.media.MediaUploadService;
import ai.neargo.shop.media.SysMediaAsset;
import ai.neargo.shop.media.SysMediaAssetMapper;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/** Private evidence lives in the host media ledger, bound to entity, store and uploader. */
class MallOfflineEvidenceAdapterTest {
    static final PrivateEvidencePort.Scope SCOPE =
            new PrivateEvidencePort.Scope(
                    new StoreRef("MAIN", "S0003"), "E0001", "order", SubjectKind.ACCOUNT, "USR:buyer",
                    "BUYER_RECEIPT");
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};

    final MediaStore store = mock(MediaStore.class);
    final MediaUploadService uploads = mock(MediaUploadService.class);
    final SysMediaAssetMapper assets = mock(SysMediaAssetMapper.class);
    final ShopMallPrivateEvidenceAdapter adapter =
            new ShopMallPrivateEvidenceAdapter(
                    store, uploads, assets,
                    Clock.fixed(Instant.parse("2026-11-04T08:00:00Z"), ZoneOffset.UTC));

    static SysMediaAsset row(String key, String uploadedBy, String status) {
        var a = new SysMediaAsset();
        a.setAssetKey(key);
        a.setEntityNo("E0001");
        a.setStoreNo("S0003");
        a.setBizType("MALLPAY");
        a.setUploadedBy(uploadedBy);
        a.setStatus(status);
        return a;
    }

    @Test
    void keysArePrivateStoreScopedAndSanitized() {
        assertEquals("E0001/S0003/mallpay/202611/ev12.jpg", adapter.newKey(SCOPE, "ev:1/2"));
        var foreign =
                new PrivateEvidencePort.Scope(
                        new StoreRef("MAIN", "../x"), "E0001", "o", SubjectKind.ACCOUNT, "u", "p");
        assertThrows(IllegalArgumentException.class, () -> adapter.newKey(foreign, "1"));
    }

    @Test
    void uploadGoesThroughTheMediaLedgerAsPrivateMallpay() throws Exception {
        String key = adapter.newKey(SCOPE, "ev1");
        adapter.store(
                SCOPE,
                key,
                new PrivateEvidencePort.Upload(() -> new ByteArrayInputStream(JPEG), JPEG.length, "image/jpeg"));
        verify(uploads)
                .store(eq(key), any(), eq((long) JPEG.length), eq("image/jpeg"), eq("MALLPAY"),
                        eq("E0001"), eq("S0003"), eq("USR:buyer"));
    }

    @Test
    void nonImageBytesForeignKeysAndExistingRowsAreRefused() throws Exception {
        String key = adapter.newKey(SCOPE, "ev1");
        var text = new PrivateEvidencePort.Upload(() -> new ByteArrayInputStream(new byte[13]), 13, "image/jpeg");
        assertThrows(IllegalArgumentException.class, () -> adapter.store(SCOPE, key, text));
        assertThrows(
                IllegalArgumentException.class,
                () -> adapter.store(SCOPE, "E0002/S0003/mallpay/202611/x.jpg", text));
        when(assets.selectOne(any())).thenReturn(row(key, "USR:buyer", SysMediaAsset.PENDING));
        assertThrows(
                IllegalStateException.class,
                () ->
                        adapter.store(
                                SCOPE,
                                key,
                                new PrivateEvidencePort.Upload(
                                        () -> new ByteArrayInputStream(JPEG), JPEG.length, "image/jpeg")));
        verify(uploads, never()).store(any(), any(), anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void storedRequiresActiveRowOfTheSameUploaderAndRealBytes() {
        String key = adapter.newKey(SCOPE, "ev1");
        when(store.exists(key)).thenReturn(true);
        when(assets.selectOne(any())).thenReturn(row(key, "USR:buyer", SysMediaAsset.PENDING));
        assertFalse(adapter.stored(SCOPE, key));
        when(assets.selectOne(any())).thenReturn(row(key, "USR:other", SysMediaAsset.ACTIVE));
        assertFalse(adapter.stored(SCOPE, key));
        when(assets.selectOne(any())).thenReturn(row(key, "USR:buyer", SysMediaAsset.ACTIVE));
        assertTrue(adapter.stored(SCOPE, key));
        when(store.exists(key)).thenReturn(false);
        assertFalse(adapter.stored(SCOPE, key));
        assertThrows(
                IllegalStateException.class,
                () -> adapter.signedRead(SCOPE, "E0001/S0004/mallpay/202611/ev1.jpg"));
    }

    @Test
    void gcReferenceColumnAppearsOnlyWhenTheCommerceTableExists() {
        var jdbc = mock(JdbcTemplate.class);
        var refs = new ShopMallEvidenceMediaRefs(jdbc);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);
        assertEquals(List.of(), refs.columns());
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        assertEquals(List.of(ShopMallEvidenceMediaRefs.EVIDENCE), refs.columns());
    }
}
