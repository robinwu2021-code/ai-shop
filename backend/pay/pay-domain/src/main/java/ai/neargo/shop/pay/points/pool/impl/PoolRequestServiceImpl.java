package ai.neargo.shop.pay.points.pool.impl;

import ai.neargo.shop.pay.points.model.PoolRequest;
import ai.neargo.shop.pay.points.pool.PoolRequestService;
import ai.neargo.shop.pay.points.pool.PoolService;
import ai.neargo.shop.pay.points.posting.PostingResult;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Checker approval and the financial voucher commit together; a failed receipt keeps the request unapproved. */
@Service
@Transactional("payTxManager")
public class PoolRequestServiceImpl implements PoolRequestService {
    private final PointsConfigurationStore config;
    private final PointsReadStore read;
    private final PoolService pools;
    private final ObjectMapper json=new ObjectMapper();
    public PoolRequestServiceImpl(PointsConfigurationStore config, PointsReadStore read, PoolService pools) {
        this.config=config; this.read=read; this.pools=pools;
    }
    @Override public void submit(PoolRequest r) {
        if (r.requestNo()==null || r.requestNo().isBlank() || r.submittedBy()==null || r.submittedBy().isBlank()
                || r.action()==null || r.amountMinor()<=0 || read.type(r.typeCode()).isEmpty()) {
            throw new IllegalArgumentException("POINTS_REQUEST_INVALID");
        }
        if ((r.action()==PoolRequest.Action.WITHDRAW_U || r.action()==PoolRequest.Action.WITHDRAW_B)
                && r.fundsContext()==null) { throw new IllegalArgumentException("POINTS_WITHDRAW_TARGET_REQUIRED"); }
        try { config.submit(r,json.writeValueAsString(new Envelope(r,null))); }
        catch (DuplicateKeyException e) {
            var prior=config.lockRequest(r.requestNo());
            if (!json.readValue((String)prior.get("payload_json"),Envelope.class).request().equals(r)) {
                throw new IllegalArgumentException("POINTS_IDEMPOTENCY_CONFLICT",e);
            }
        }
    }
    @Override public PostingResult approve(String no, String actor) {
        var row=config.lockRequest(no);
        if (row==null || actor==null || actor.isBlank()) { throw new IllegalArgumentException("POINTS_REQUEST_UNKNOWN"); }
        Envelope envelope=json.readValue((String)row.get("payload_json"),Envelope.class);
        if (actor.equals(envelope.request().submittedBy())) { throw new IllegalArgumentException("POINTS_SELF_APPROVE"); }
        if ("APPROVED".equals(row.get("state"))) { return envelope.result(); }
        config.beginApproval(no,actor);
        PostingResult result=pools.applyApproved(envelope.request());
        config.approve(no,actor,json.writeValueAsString(new Envelope(envelope.request(),result)));
        return result;
    }
    public record Envelope(PoolRequest request, PostingResult result) { }
}
