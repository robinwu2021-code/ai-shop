package ai.neargo.shop.pay.points.pool.impl;

import ai.neargo.shop.pay.points.model.AccountKey;
import ai.neargo.shop.pay.points.model.Bucket;
import ai.neargo.shop.pay.points.model.EvidenceRef;
import ai.neargo.shop.pay.points.model.FundBucket;
import ai.neargo.shop.pay.points.model.FundMove;
import ai.neargo.shop.pay.points.model.HolderType;
import ai.neargo.shop.pay.points.model.PointType;
import ai.neargo.shop.pay.points.model.PointsMove;
import ai.neargo.shop.pay.points.model.PoolRequest;
import ai.neargo.shop.pay.points.model.Voucher;
import ai.neargo.shop.pay.points.pool.PoolService;
import ai.neargo.shop.pay.points.posting.PointsPostingService;
import ai.neargo.shop.pay.points.posting.PostingResult;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/** A money receipt supports issuance; replenishing refund B never creates customer points. */
@Service
@Transactional("payTxManager")
public class PoolServiceImpl implements PoolService {
    private final PointsReadStore read;
    private final PointsConfigurationStore config;
    private final PointsPostingService posting;
    public PoolServiceImpl(PointsReadStore read, PointsConfigurationStore config, PointsPostingService posting) {
        this.read=read; this.config=config; this.posting=posting;
    }
    @Override public PostingResult applyApproved(PoolRequest r) {
        var request = config.lockRequest(r.requestNo());
        if (request==null || !"APPROVING".equals(request.get("state"))) {
            throw new IllegalArgumentException("POINTS_APPROVAL_REQUIRED");
        }
        config.lockTypeAndPool(r.typeCode());
        PointType type = read.type(r.typeCode()).orElseThrow();
        long amount=r.amountMinor();
        if (amount<=0 || amount%type.rate().minorUnit()!=0) {
            throw new IllegalArgumentException("POINTS_AMOUNT_NOT_ALIGNED");
        }
        long points=type.rate().pointsForMinor(amount);
        List<PointsMove> moves=new ArrayList<>();
        List<FundMove> funds=new ArrayList<>();
        List<EvidenceRef> evidence=new ArrayList<>();
        switch (r.action()) {
            case TOPUP_U -> {
                long free=read.account(r.typeCode(),HolderType.RECEIVE,"PLATFORM").points(Bucket.RECIRCULATABLE);
                long reused=Math.min(free,points);
                if (reused>0) { moves.add(move(type,HolderType.RECEIVE,Bucket.RECIRCULATABLE,-reused)); }
                if (points>reused) { moves.add(move(type,HolderType.MINT,Bucket.ISSUED,-(points-reused))); }
                moves.add(move(type,HolderType.ISSUE,Bucket.ISSUABLE,points));
                funds.add(new FundMove(FundBucket.U,amount));
                evidence.add(new EvidenceRef(r.evidenceNo(),type.currency(),amount));
            }
            case TOPUP_B -> {
                funds.add(new FundMove(FundBucket.B,amount));
                evidence.add(new EvidenceRef(r.evidenceNo(),type.currency(),amount));
            }
            case TRANSFER_U_TO_B -> {
                moves.add(move(type,HolderType.ISSUE,Bucket.ISSUABLE,-points));
                moves.add(move(type,HolderType.RECEIVE,Bucket.RECIRCULATABLE,points));
                funds.add(new FundMove(FundBucket.U,-amount)); funds.add(new FundMove(FundBucket.B,amount));
            }
            case WITHDRAW_U, WITHDRAW_B -> throw new UnsupportedOperationException(
                    "POINTS_WITHDRAW_REQUIRES_COMMITTED_FUNDS_INTENT");
        }
        return posting.post(new Voucher("POOL:"+r.requestNo(),type.typeCode(),type.currency(),type.rate(),
                moves,funds,List.of(),0,evidence,"{}"));
    }
    private PointsMove move(PointType type, HolderType holder, Bucket bucket, long points) {
        return new PointsMove(new AccountKey(type.typeCode(),holder,"PLATFORM",bucket),points);
    }
}
