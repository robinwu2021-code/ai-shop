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
import ai.neargo.shop.pay.points.pool.PoolWithdrawalService;
import tools.jackson.databind.ObjectMapper;
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
    private final PoolWithdrawalService withdrawals;
    private final ObjectMapper json=new ObjectMapper();
    public PoolServiceImpl(PointsReadStore read, PointsConfigurationStore config, PointsPostingService posting,PoolWithdrawalService withdrawals) {
        this.read=read; this.config=config; this.posting=posting; this.withdrawals=withdrawals;
    }
    @Override public PostingResult applyApproved(PoolRequest r) {
        var request = config.lockRequest(r.requestNo());
        if (request==null || !"APPROVING".equals(request.get("state"))) {
            throw new IllegalArgumentException("POINTS_APPROVAL_REQUIRED");
        }
        if (!json.readValue((String)request.get("payload_json"),PoolRequestServiceImpl.Envelope.class)
                .request().equals(r)) { throw new IllegalArgumentException("POINTS_APPROVAL_CONTENT_MISMATCH"); }
        if (r.action()==PoolRequest.Action.WITHDRAW_U || r.action()==PoolRequest.Action.WITHDRAW_B) {
            return withdrawals.prepareApproved(r);
        }
        config.lockTypeAndPool(r.typeCode());
        PointType type = read.type(r.typeCode()).orElseThrow();
        long amount=r.amountMinor();
        if (amount<=0 || (r.action()!=PoolRequest.Action.TOPUP_B && amount%type.rate().minorUnit()!=0)) {
            throw new IllegalArgumentException("POINTS_AMOUNT_NOT_ALIGNED");
        }
        long points=r.action()==PoolRequest.Action.TOPUP_B?0:type.rate().pointsForMinor(amount);
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
            case WITHDRAW_U, WITHDRAW_B -> throw new IllegalStateException("POINTS_WITHDRAW_PREPARATION_REQUIRED");
        }
        return posting.post(new Voucher("POOL:"+r.requestNo(),type.typeCode(),type.currency(),type.rate(),
                moves,funds,List.of(),0,evidence,"{}"));
    }
    private PointsMove move(PointType type, HolderType holder, Bucket bucket, long points) {
        return new PointsMove(new AccountKey(type.typeCode(),holder,"PLATFORM",bucket),points);
    }
}
