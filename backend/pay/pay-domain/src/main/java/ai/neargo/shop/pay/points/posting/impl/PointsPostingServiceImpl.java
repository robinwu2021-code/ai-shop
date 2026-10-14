package ai.neargo.shop.pay.points.posting.impl;

import ai.neargo.shop.pay.points.posting.PointsPostingService;
import ai.neargo.shop.pay.points.posting.PostingResult;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.points.model.FundMove;
import ai.neargo.shop.pay.points.model.EvidenceRef;
import ai.neargo.shop.pay.points.model.PointsMove;
import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.model.Voucher;
import ai.neargo.shop.pay.points.model.ReserveMove;
import ai.neargo.shop.pay.points.store.PostingStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.UUID;

/** Sole financial writer: immutable replay, conserved amounts, and one atomic pay transaction. */
@Service("pointsPostingService")
public class PointsPostingServiceImpl implements PointsPostingService {
    private final PostingStore store;
    private final ObjectMapper json = new ObjectMapper();

    public PointsPostingServiceImpl(PostingStore store) { this.store = store; }

    /** Joins settlement's pay transaction; any conditional write failure rolls the whole voucher back. */
    @Transactional("payTxManager")
    public PostingResult post(Voucher voucher) {
        voucher.validateV1ToV4();
        // Trusted internal posting bypasses merchant read scope; controllers authorize the business action first.
        return DataScopeContext.executeWithoutScope(() -> {
            String hash = hash(voucher);
            String no = UUID.randomUUID().toString();
            PostingResult claimed = store.claim(voucher, hash, no);
            if (claimed.replayed()) { return claimed; }
            store.applyConditions(voucher, no);
            return claimed;
        });
    }

    @Override
    @Transactional("payTxManager")
    public void recordEvidence(EvidenceRef e) {
        if (e==null || e.evidenceNo()==null || e.evidenceNo().isBlank() || e.currency()==null
                || e.currency().isBlank() || e.externalMinor()==0) {
            throw new IllegalArgumentException("POINTS_EVIDENCE_INVALID");
        }
        DataScopeContext.executeWithoutScope(() -> { store.recordEvidence(e); return null; });
    }

    private String hash(Voucher voucher) {
        // Canonical lists and JSON preserve semantic equality while rejecting a changed amount under the same key.
        Voucher canonical = new Voucher(voucher.bizRef(), voucher.typeCode(), voucher.currency(), voucher.rate(),
                voucher.pointsMoves().stream().sorted(Comparator.comparing(PointsMove::account)
                        .thenComparingLong(PointsMove::deltaPoints)).toList(),
                voucher.fundMoves().stream().sorted(Comparator.comparing(FundMove::bucket)
                        .thenComparingLong(FundMove::deltaMinor)).toList(),
                voucher.positionMoves().stream().sorted(Comparator.comparing(PositionMove::kind)
                        .thenComparing(PositionMove::positionNo)).toList(), voucher.reserveDelta(),
                voucher.evidenceRefs().stream().sorted(Comparator.comparing(e -> e.evidenceNo())).toList(),
                voucher.resultSnapshot(), voucher.reserveMoves().stream()
                        .sorted(Comparator.comparing(ReserveMove::reserveNo)).toList());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Required SHA-256 unavailable", e);
        }
    }
}
