package ai.neargo.shop.commercebridge;

import ai.neargo.commerce.api.port.PrivateEvidencePort;
import ai.neargo.shop.media.ImageProbe;
import ai.neargo.shop.media.MediaStore;
import ai.neargo.shop.media.MediaUploadService;
import ai.neargo.shop.media.SysMediaAsset;
import ai.neargo.shop.media.SysMediaAssetMapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Offline payment evidence on the host's existing private media store.
 *
 * <p>Key layout {@code {entity}/{store}/mallpay/{yyyyMM}/{no}.jpg}: the third segment is not
 * {@code goods}, so COS keeps it private and only signed reads work. Bytes are written through
 * {@link MediaUploadService} (PENDING row, bytes, ACTIVE) so storage accounting, reconciliation and
 * GC see them; {@link ShopMallEvidenceMediaRefs} keeps every reserved key referenced.
 */
public final class ShopMallPrivateEvidenceAdapter implements PrivateEvidencePort {
    static final String BIZ_TYPE = "MALLPAY";
    private static final DateTimeFormatter MONTH =
            DateTimeFormatter.ofPattern("yyyyMM").withZone(ZoneOffset.UTC);
    private static final Duration READ_TTL = Duration.ofMinutes(5);

    private final MediaStore store;
    private final MediaUploadService uploads;
    private final SysMediaAssetMapper assets;
    private final Clock clock;

    public ShopMallPrivateEvidenceAdapter(
            MediaStore store, MediaUploadService uploads, SysMediaAssetMapper assets, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.uploads = Objects.requireNonNull(uploads);
        this.assets = Objects.requireNonNull(assets);
        this.clock = Objects.requireNonNull(clock);
    }

    private static String segment(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("EVIDENCE_SCOPE_INVALID");
        return value;
    }

    private static String prefix(Scope scope) {
        return segment(scope.entityNo()) + "/" + segment(scope.store().storeNo()) + "/mallpay/";
    }

    @Override
    public String newKey(Scope scope, String uniqueNo) {
        String no = uniqueNo == null ? "" : uniqueNo.replaceAll("[^A-Za-z0-9_-]", "");
        return prefix(scope) + MONTH.format(clock.instant()) + "/" + segment(no) + ".jpg";
    }

    private boolean owns(Scope scope, String key) {
        return key != null && !key.contains("..") && key.startsWith(prefix(scope));
    }

    private SysMediaAsset asset(String key) {
        return assets.selectOne(
                new LambdaQueryWrapper<SysMediaAsset>().eq(SysMediaAsset::getAssetKey, key));
    }

    private boolean active(Scope scope, String key) {
        if (!owns(scope, key)) return false;
        var row = asset(key);
        return row != null
                && SysMediaAsset.ACTIVE.equals(row.getStatus())
                && BIZ_TYPE.equals(row.getBizType())
                && scope.entityNo().equals(row.getEntityNo())
                && scope.store().storeNo().equals(row.getStoreNo())
                && scope.submitterNo().equals(row.getUploadedBy());
    }

    @Override
    public boolean stored(Scope scope, String key) {
        return active(scope, key) && store.exists(key);
    }

    @Override
    public String signedRead(Scope scope, String key) {
        if (!active(scope, key)) throw new IllegalStateException("EVIDENCE_UNAVAILABLE");
        return store.signedUrl(key, READ_TTL);
    }

    @Override
    public void store(Scope scope, String key, Upload upload) {
        if (!owns(scope, key)) throw new IllegalArgumentException("EVIDENCE_SCOPE_INVALID");
        // A PENDING row from an interrupted upload is resolved by the media reconcile job; never
        // overwrite bytes under a key that already has a ledger row.
        if (asset(key) != null) throw new IllegalStateException("EVIDENCE_UPLOAD_PENDING");
        ImageProbe.InputStreamSource source = () -> upload.content().get();
        String ext = "image/png".equals(upload.contentType()) ? "png" : "jpg";
        if (!ImageProbe.looksLikeImage(source, ext))
            throw new IllegalArgumentException("EVIDENCE_NOT_IMAGE");
        try {
            uploads.store(
                    key,
                    source,
                    upload.size(),
                    upload.contentType(),
                    BIZ_TYPE,
                    scope.entityNo(),
                    scope.store().storeNo(),
                    scope.submitterNo());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
