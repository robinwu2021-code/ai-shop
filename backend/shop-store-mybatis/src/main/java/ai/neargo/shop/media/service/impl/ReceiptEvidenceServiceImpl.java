package ai.neargo.shop.media.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.media.MediaStore;
import ai.neargo.shop.media.SysMediaAsset;
import ai.neargo.shop.media.SysMediaAssetMapper;
import ai.neargo.shop.media.service.ReceiptEvidenceService;
import ai.neargo.shop.spi.media.ReceiptEvidencePort.ReceiptEvidence;
import ai.neargo.shop.spi.media.ReceiptEvidencePort.ReceiptEvidenceQuery;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;

/**
 * No bytes lookup holds SQL locks; the dedicated purpose is permanently excluded from application
 * purge.
 */
@Service
public class ReceiptEvidenceServiceImpl implements ReceiptEvidenceService {
    private final SysMediaAssetMapper assets;
    private final MediaStore store;
    private final ObjectMapper json;

    /** Use the existing media owner and its actual local/COS storage provider. */
    public ReceiptEvidenceServiceImpl(
            SysMediaAssetMapper assets, MediaStore store, ObjectMapper json) {
        this.assets = assets;
        this.store = store;
        this.json = json;
    }

    @Override
    public ReceiptEvidence verify(ReceiptEvidenceQuery request) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Receipt evidence verification requires no caller transaction");
        }
        String[] segments = request.evidenceRef().split("/", -1);
        if (segments.length != 5
                || !request.entityNo().equals(segments[0])
                || !request.storeNo().equals(segments[1])
                || !"offline_receipt".equals(segments[2])
                || !segments[3].matches("[0-9]{6}")
                || !segments[4].matches("[a-zA-Z0-9_-]+\\.(jpg|jpeg|png|webp|gif)")) {
            throw invalid();
        }
        var original = facts(request, read(request.evidenceRef()));
        if (!store.exists(request.evidenceRef())) {
            throw invalid();
        }
        // Detect changed owner facts after the lock-free storage operation; no current DTO may
        // replace P0 facts.
        var current = facts(request, read(request.evidenceRef()));
        if (!original.equals(current)) {
            throw invalid();
        }
        return original;
    }

    private SysMediaAsset read(String key) {
        return DataScopeContext.executeWithoutScope(
                () ->
                        assets.selectOne(
                                Wrappers.<SysMediaAsset>lambdaQuery()
                                        .eq(SysMediaAsset::getAssetKey, key)));
    }

    private ReceiptEvidence facts(ReceiptEvidenceQuery query, SysMediaAsset asset) {
        if (asset == null
                || !"MAIN".equals(query.tenantNo())
                || !query.entityNo().equals(asset.getEntityNo())
                || !query.storeNo().equals(asset.getStoreNo())
                || !query.actorAccountNo().equals(asset.getUploadedBy())
                || !SysMediaAsset.OFFLINE_RECEIPT.equals(asset.getBizType())
                || !SysMediaAsset.ACTIVE.equals(asset.getStatus())
                || asset.getPurgeBatchNo() != null
                || asset.getBytes() == null
                || asset.getBytes() <= 0
                || asset.getCreatedAt() == null
                || asset.getContentType() == null
                || !asset.getContentType().startsWith("image/")) {
            throw invalid();
        }
        var uploadedAt = asset.getCreatedAt().toInstant(ZoneOffset.UTC);
        String digest =
                hash(
                        json.writeValueAsString(
                                List.of(
                                        "MAIN",
                                        asset.getAssetKey(),
                                        asset.getEntityNo(),
                                        asset.getStoreNo(),
                                        asset.getUploadedBy(),
                                        asset.getBizType(),
                                        asset.getBytes(),
                                        asset.getContentType(),
                                        uploadedAt)));
        return new ReceiptEvidence(
                "MAIN",
                asset.getAssetKey(),
                asset.getEntityNo(),
                asset.getStoreNo(),
                asset.getUploadedBy(),
                asset.getBizType(),
                asset.getBytes(),
                asset.getContentType(),
                uploadedAt,
                digest);
    }

    private static String hash(String content) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static BizException invalid() {
        return BizException.of(ErrorCode.CHECKOUT_RECEIPT_EVIDENCE_INVALID);
    }
}
