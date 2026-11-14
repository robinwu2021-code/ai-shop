package ai.neargo.shop.trade.service;

import java.util.function.Supplier;

import ai.neargo.shop.trade.dto.PricingConfigCommands.Context;
import ai.neargo.shop.trade.dto.PricingConfigCommands.DraftSaveReq;
import ai.neargo.shop.trade.dto.PricingConfigCommands.DraftVO;
import ai.neargo.shop.trade.dto.PricingConfigCommands.PreviewVO;
import ai.neargo.shop.trade.dto.PricingConfigCommands.PublishReq;
import ai.neargo.shop.trade.dto.PricingConfigCommands.RevisionVO;
import ai.neargo.shop.trade.dto.PricingConfigCommands.CreateTemplateReq;

/** Versioned pricing configuration, scoped to trusted backend identity; historical revisions have no write API. */
public interface PricingTemplateService {
    DraftVO create(Context context, CreateTemplateReq req, String key);
    DraftVO getDraft(Context context, String no);
    DraftVO saveDraft(Context context, String no, DraftSaveReq req, String key);
    PreviewVO preview(Context context, String no, long version, String rollbackFrom);
    RevisionVO publish(Context context, String no, PublishReq req, String key);
    /** Resolve fresh evidence only after checking an existing receipt for this trusted identity. */
    RevisionVO publish(Context identity, String no, PublishReq req, String key, Supplier<Context> evidence);
    RevisionVO getRevision(Context context, String no, String revisionNo);
}
