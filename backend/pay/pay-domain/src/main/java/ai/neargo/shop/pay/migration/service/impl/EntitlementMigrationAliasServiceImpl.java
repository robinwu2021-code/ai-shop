package ai.neargo.shop.pay.migration.service.impl;
import ai.neargo.shop.pay.migration.service.EntitlementMigrationAliasService;
import ai.neargo.shop.pay.migration.service.EntitlementMigrationAliasWriter;
import ai.neargo.shop.pay.migration.service.LegacyCouponAliasComparator;
import ai.neargo.shop.pay.migration.service.LegacyCouponMigrationNormalizer;
import ai.neargo.shop.pay.migration.service.LegacyMigrationEvidenceReader;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.service.EntitlementCommandDeduplicator;
import ai.neargo.shop.pay.mapper.entitlement.EntMigrationAliasMapper;
import ai.neargo.shop.pay.migration.dto.MigrationAliasViews.AliasView;
import ai.neargo.shop.pay.migration.dto.MigrationAliasViews.Pair;
import ai.neargo.shop.pay.migration.dto.MigrationAliasViews.Recognize;
import ai.neargo.shop.pay.migration.entity.EntMigrationAlias;
import ai.neargo.shop.pay.migration.entity.EntMigrationStage;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.pay.MarketPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Revalidates both frozen sources outside pay locks. Recognition never commits either source or target owner. */
@Service("entitlementMigrationAliasService")
public class EntitlementMigrationAliasServiceImpl implements EntitlementMigrationAliasService {
    private final EntMigrationAliasMapper aliases;
    private final LegacyMigrationEvidenceReader evidence;
    private final EntitlementMigrationAliasWriter writer;
    private final EntitlementCommandDeduplicator commands;
    private final ObjectProvider<MarketPort> markets;
    private final ObjectMapper json;
    public EntitlementMigrationAliasServiceImpl(EntMigrationAliasMapper aliases,LegacyMigrationEvidenceReader evidence,
            EntitlementMigrationAliasWriter writer,EntitlementCommandDeduplicator commands,
            ObjectProvider<MarketPort> markets,ObjectMapper json) {
        this.aliases=aliases; this.evidence=evidence; this.writer=writer; this.commands=commands;
        this.markets=markets; this.json=json;
    }
    public LegacyCouponAliasComparator.Comparison compare(CatalogScope scope,Pair pair) {
        outside(scope); ContractChecks.present(pair);
        var source=pair(scope,pair);
        return comparison(source,Instant.now());
    }
    public AliasView recognize(CatalogScope scope,Recognize command,String key) {
        outside(scope); ContractChecks.present(command); ContractChecks.id(key);
        var replay=commands.replay(scope,EntitlementMigrationAliasWriter.OPERATION,key,command,AliasView.class);
        if(replay.isPresent()) return replay.get();
        var source=pair(scope,command.pair());
        Instant at=Instant.now().truncatedTo(ChronoUnit.MILLIS);
        var compared=comparison(source,at);
        if(!"CANDIDATE_ALIAS".equals(compared.status())) throw BizException.of(ErrorCode.CONFLICT);
        return writer.recognize(scope,command,key,source.pmt(),source.mkt(),compared,at);
    }
    public AliasView get(CatalogScope scope,String no) {
        outside(scope); ContractChecks.id(no);
        var row=aliases.selectOne(Wrappers.<EntMigrationAlias>lambdaQuery()
                .eq(EntMigrationAlias::getTenantNo,scope.tenantNo()).eq(EntMigrationAlias::getEntityNo,scope.entityNo())
                .eq(EntMigrationAlias::getAliasNo,no));
        if(row==null) throw BizException.of(ErrorCode.NOT_FOUND);
        return EntitlementMigrationAliasWriter.view(row);
    }

    /** Only the canonical source can discount the exact, still-verified companion collision. */
    public Optional<LegacyCouponMigrationNormalizer.Report> assessRecognized(CatalogScope scope,EntMigrationStage requested,
                                                                            JsonNode current,Instant at) {
        outside(scope);
        var alias=aliases.findMember(scope.tenantNo(),requested.getStageNo());
        if(alias==null) return Optional.empty();
        if(!scope.entityNo().equals(alias.getEntityNo()) || !"RECOGNIZED".equals(alias.getStatus())
                || !alias.getCanonicalStageNo().equals(alias.getPmtStageNo()))
            throw BizException.of(ErrorCode.CONFLICT);
        var source=pair(scope,new Pair(alias.getPmtStageNo(),alias.getMktStageNo()),requested,current);
        var compared=comparison(source,alias.getComparedAt().toInstant(ZoneOffset.UTC));
        if(!source.pmt().getContentHash().equals(alias.getPmtContentHash())
                || !source.mkt().getContentHash().equals(alias.getMktContentHash())
                || !source.pmt().getMigrationBatchNo().equals(alias.getMigrationBatchNo())
                || !"CANDIDATE_ALIAS".equals(compared.status())
                || !alias.getComparisonHash().equals(compared.comparisonHash())
                || compared.matchedInstanceCount()!=alias.getMatchedInstanceCount())
            throw BizException.of(ErrorCode.CONFLICT);
        var normalizer=new LegacyCouponMigrationNormalizer();
        if(!requested.getStageNo().equals(alias.getCanonicalStageNo())) {
            var original=normalizer.normalize(requested.getStageNo(),current,market(current),at);
            var blockers=new ArrayList<>(original.blockers()); blockers.add("ALIAS_SHADOW_SOURCE");
            return Optional.of(new LegacyCouponMigrationNormalizer.Report(original.stageNo(),"BLOCKED",false,
                    original.definition(),original.instances(),original.notices(),blockers,original.issuance()));
        }
        ObjectNode contextual=(ObjectNode)current.deepCopy();
        ((ObjectNode)contextual.path("assessmentFacts")).put("peerTemplateCount",0).put("peerInstanceCount",0);
        var primary=normalizer.normalize(requested.getStageNo(),contextual,market(current),at);
        var notices=new ArrayList<>(primary.notices()); notices.add("CANONICAL_ALIAS_RECOGNIZED");
        return Optional.of(new LegacyCouponMigrationNormalizer.Report(primary.stageNo(),primary.status(),false,
                primary.definition(),primary.instances(),notices,primary.blockers(),primary.issuance()));
    }
    private PairEvidence pair(CatalogScope scope,Pair pair) { return pair(scope,pair,null,null); }
    private PairEvidence pair(CatalogScope scope,Pair pair,EntMigrationStage alreadyRead,JsonNode alreadyCurrent) {
        var pmt=evidence.owned(scope,pair.pmtStageNo());
        var mkt=evidence.owned(scope,pair.mktStageNo());
        if(evidence.mode(pmt)!=2 || evidence.mode(mkt)!=2 || !"PMT".equals(pmt.getSourceSystem())
                || !"MKT_LEGACY".equals(mkt.getSourceSystem())
                || !pmt.getSourceTemplateNo().equals(mkt.getSourceTemplateNo())
                || !pmt.getMigrationBatchNo().equals(mkt.getMigrationBatchNo()))
            throw BizException.of(ErrorCode.CONFLICT);
        JsonNode pmtBody=body(scope,pmt,alreadyRead,alreadyCurrent);
        JsonNode mktBody=body(scope,mkt,alreadyRead,alreadyCurrent);
        return new PairEvidence(pmt,mkt,pmtBody,mktBody);
    }
    private JsonNode body(CatalogScope scope,EntMigrationStage row,EntMigrationStage already,JsonNode current) {
        if(already!=null && already.getStageNo().equals(row.getStageNo())) {
            if(!evidence.aad(row).equals(evidence.aad(already))
                    || !row.getPayloadCipher().equals(already.getPayloadCipher()))
                throw BizException.of(ErrorCode.CONFLICT);
            return current;
        }
        return json.readTree(evidence.current(scope,row));
    }
    private LegacyCouponAliasComparator.Comparison comparison(PairEvidence source,Instant at) {
        return new LegacyCouponAliasComparator().compare(source.pmtBody(),source.mktBody(),market(source.pmtBody()),at);
    }
    private MarketPort.MarketBrief market(JsonNode body) {
        String no=body.path("assessmentFacts").path("market").asText();
        var owner=markets.getIfAvailable();
        return owner==null || no.isBlank()?null:owner.find(no).orElse(null);
    }
    private static void outside(CatalogScope scope) {
        if(scope==null || !scope.platformActor()) throw BizException.of(ErrorCode.FORBIDDEN);
        if(!"MAIN".equals(scope.tenantNo())) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw BizException.of(ErrorCode.CONFLICT);
    }
    private record PairEvidence(EntMigrationStage pmt,EntMigrationStage mkt,JsonNode pmtBody,JsonNode mktBody) {
        @Override public String toString() { return "PairEvidence[redacted]"; }
    }
}
