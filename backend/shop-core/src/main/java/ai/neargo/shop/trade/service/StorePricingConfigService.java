package ai.neargo.shop.trade.service;

import ai.neargo.shop.trade.dto.PricingConfigCommands.Context;
import ai.neargo.shop.trade.dto.PricingConfigCommands.ResetReq;
import ai.neargo.shop.trade.dto.PricingConfigCommands.UpgradeReq;
import ai.neargo.shop.trade.dto.PricingConfigCommands.DraftSaveReq;
import ai.neargo.shop.trade.dto.PricingConfigCommands.DraftVO;
import ai.neargo.shop.trade.dto.PricingConfigCommands.PreviewVO;
import ai.neargo.shop.trade.dto.PricingConfigCommands.PublishReq;
import ai.neargo.shop.trade.dto.PricingConfigCommands.RevisionVO;
import ai.neargo.shop.trade.dto.PricingConfigCommands.InitializeReq;

/** Versioned pricing configuration, scoped to trusted backend identity; historical revisions have no write API. */
public interface StorePricingConfigService {
    RevisionVO initialize(Context context, InitializeReq req, String key);
    DraftVO getDraft(Context context, String no);
    DraftVO saveDraft(Context context, String no, DraftSaveReq req, String key);
    PreviewVO preview(Context context, String no, long version, String rollbackFrom);
    RevisionVO publish(Context context, String no, PublishReq req, String key);
    RevisionVO getRevision(Context context, String no, String revisionNo);
    DraftVO resetOverrides(Context context, String no, ResetReq req, String key);
    DraftVO upgradeTemplate(Context context, String no, UpgradeReq req, String key);
    RevisionVO rollback(Context context, String no, PublishReq req, String key);

}
