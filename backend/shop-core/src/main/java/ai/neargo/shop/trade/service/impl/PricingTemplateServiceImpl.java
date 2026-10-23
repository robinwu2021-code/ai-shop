package ai.neargo.shop.trade.service.impl;

import ai.neargo.shop.trade.dto.PricingConfigCommands.Context;
import ai.neargo.shop.trade.dto.PricingConfigCommands.DraftSaveReq;
import ai.neargo.shop.trade.dto.PricingConfigCommands.DraftVO;
import ai.neargo.shop.trade.dto.PricingConfigCommands.PreviewVO;
import ai.neargo.shop.trade.dto.PricingConfigCommands.PublishReq;
import ai.neargo.shop.trade.dto.PricingConfigCommands.RevisionVO;
import ai.neargo.shop.trade.dto.PricingConfigCommands.CreateTemplateReq;
import ai.neargo.shop.trade.service.PricingTemplateService;
import ai.neargo.shop.trade.policy.PricingPublicationPolicy;
import org.springframework.stereotype.Service;

/** Keeps resource-facing services in trade while one policy owns atomic publication invariants. */
@Service
public class PricingTemplateServiceImpl implements PricingTemplateService {
    private final PricingPublicationPolicy policy;

    public PricingTemplateServiceImpl(PricingPublicationPolicy policy) { this.policy = policy; }

    public DraftVO create(Context c, CreateTemplateReq req, String key) {
        return policy.create(c, req, key);
    }

    @Override
    public DraftVO getDraft(Context c, String no) {
        return policy.getDraft(c, no, true);
    }

    @Override
    public DraftVO saveDraft(Context c, String no, DraftSaveReq req, String key) {
        return policy.saveDraft(c, no, req, key, true);
    }

    @Override
    public PreviewVO preview(Context c, String no, long version, String rollbackFrom) {
        return policy.preview(c, no, version, rollbackFrom, true);
    }

    @Override
    public RevisionVO publish(Context c, String no, PublishReq req, String key) {
        return policy.publish(c, no, req, key, true);
    }

    @Override
    public RevisionVO getRevision(Context c, String no, String revisionNo) {
        return policy.getRevision(c, no, revisionNo, true);
    }
}
