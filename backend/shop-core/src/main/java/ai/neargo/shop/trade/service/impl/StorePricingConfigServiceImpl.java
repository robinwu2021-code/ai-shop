package ai.neargo.shop.trade.service.impl;

import java.util.function.Supplier;

import ai.neargo.shop.trade.dto.PricingConfigCommands.Context;
import ai.neargo.shop.trade.dto.PricingConfigCommands.ResetReq;
import ai.neargo.shop.trade.dto.PricingConfigCommands.UpgradeReq;
import ai.neargo.shop.trade.dto.PricingConfigCommands.DraftSaveReq;
import ai.neargo.shop.trade.dto.PricingConfigCommands.DraftVO;
import ai.neargo.shop.trade.dto.PricingConfigCommands.PreviewVO;
import ai.neargo.shop.trade.dto.PricingConfigCommands.PublishReq;
import ai.neargo.shop.trade.dto.PricingConfigCommands.RevisionVO;
import ai.neargo.shop.trade.dto.PricingConfigCommands.InitializeReq;
import ai.neargo.shop.trade.service.StorePricingConfigService;
import ai.neargo.shop.trade.policy.PricingPublicationPolicy;
import org.springframework.stereotype.Service;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

/** Keeps resource-facing services in trade while one policy owns atomic publication invariants. */
@Service
public class StorePricingConfigServiceImpl implements StorePricingConfigService {
    private final PricingPublicationPolicy policy;

    public StorePricingConfigServiceImpl(PricingPublicationPolicy policy) { this.policy = policy; }

    public RevisionVO initialize(Context c, InitializeReq req, String key) {
        return policy.initialize(c, req, key);
    }

    @Override
    public DraftVO getDraft(Context c, String no) {
        return policy.getDraft(c, no, false);
    }

    @Override
    public DraftVO saveDraft(Context c, String no, DraftSaveReq req, String key) {
        return policy.saveDraft(c, no, req, key, false);
    }

    @Override
    public PreviewVO preview(Context c, String no, long version, String rollbackFrom) {
        return policy.preview(c, no, version, rollbackFrom, false);
    }

    @Override
    public RevisionVO publish(Context c, String no, PublishReq req, String key) {
        return policy.publish(c, no, req, key, false);
    }

    @Override
    public RevisionVO publish(Context identity, String no, PublishReq req, String key, Supplier<Context> evidence) {
        return policy.publish(identity, no, req, key, false, evidence);
    }

    @Override
    public RevisionVO initialize(Context identity, InitializeReq req, String key, Supplier<Context> evidence) {
        return policy.initialize(identity, req, key, evidence);
    }

    @Override
    public RevisionVO rollback(Context identity, String no, PublishReq req, String key, Supplier<Context> evidence) {
        if (req.rollbackFromRevisionNo() == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        return policy.publish(identity, no, req, key, false, evidence);
    }

    @Override
    public RevisionVO getRevision(Context c, String no, String revisionNo) {
        return policy.getRevision(c, no, revisionNo, false);
    }
    @Override
    public DraftVO resetOverrides(Context c, String no, ResetReq req, String key) {
        return policy.resetOverrides(c, no, req, key);
    }
    @Override
    public DraftVO upgradeTemplate(Context c, String no, UpgradeReq req, String key) {
        return policy.upgradeTemplate(c, no, req, key);
    }
    @Override
    public RevisionVO rollback(Context c, String no, PublishReq req, String key) {
        if (req.rollbackFromRevisionNo() == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        return policy.publish(c, no, req, key, false);
    }

}
