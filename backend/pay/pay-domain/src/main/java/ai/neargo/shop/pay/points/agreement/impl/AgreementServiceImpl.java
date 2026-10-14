package ai.neargo.shop.pay.points.agreement.impl;

import ai.neargo.shop.pay.points.agreement.AgreementService;
import ai.neargo.shop.pay.points.model.AgreementSnapshot;
import ai.neargo.shop.pay.points.model.PointsKeys;
import ai.neargo.shop.pay.points.port.PointsFundsGateway;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Publishing terms and changing acceptance never overwrite the text held by an existing order. */
@Service
@Transactional("payTxManager")
public class AgreementServiceImpl implements AgreementService {
    private final PointsConfigurationStore config;
    private final PointsReadStore read;
    private final MerchantQueryPort merchants;
    private final StoreDirectoryPort stores;
    private final ObjectProvider<PointsFundsGateway> funds;
    private final ObjectMapper json=new ObjectMapper();
    public AgreementServiceImpl(PointsConfigurationStore config,PointsReadStore read,MerchantQueryPort merchants,
                                StoreDirectoryPort stores,ObjectProvider<PointsFundsGateway> funds) {
        this.config=config; this.read=read; this.merchants=merchants; this.stores=stores; this.funds=funds;
    }
    @Override public void publishTerms(String type,String version,String text,String actor) {
        if (version==null || version.isBlank() || text==null || text.isBlank() || actor==null || actor.isBlank()) {
            throw new IllegalArgumentException("POINTS_TERMS_INVALID");
        }
        config.lockTypeAndPool(type);
        String key=PointsKeys.position("T",type,"CURRENT_TERMS");
        var prior=config.agreement(key);
        if (prior!=null) {
            Terms old=json.readValue((String)prior.get("payload_json"),Terms.class);
            if (old.version().equals(version)) {
                if (!old.text().equals(text)) { throw new IllegalArgumentException("POINTS_TERMS_VERSION_CONFLICT"); }
                return;
            }
            config.terminateAgreement(key,actor);
        }
        config.putAgreement(UUID.randomUUID().toString(),type,"PLATFORM",key,"TERMS",json.writeValueAsString(
                new Terms(version,hash(text),text)),actor);
    }
    @Override public AgreementSnapshot sign(SignCommand c,String actor) {
        config.lockTypeAndPool(c.typeCode());
        if (!merchants.ownerUserNoOf(c.merchantNo()).filter(o -> o.equals(actor)).isPresent()) {
            throw new IllegalArgumentException("POINTS_SIGNER_NOT_OWNER");
        }
        var store=stores.cards(List.of(c.storeNo())).get(c.storeNo());
        if (store==null || !store.entityNo().equals(c.merchantNo())) {
            throw new IllegalArgumentException("POINTS_STORE_NOT_OWNED");
        }
        var row=config.agreement(PointsKeys.position("T",c.typeCode(),"CURRENT_TERMS"));
        if (row==null) { throw new IllegalArgumentException("POINTS_TERMS_REQUIRED"); }
        Terms terms=json.readValue((String)row.get("payload_json"),Terms.class);
        if (!terms.version().equals(c.termsVersion()) || !terms.hash().equals(c.termsHash())) {
            throw new IllegalArgumentException("POINTS_TERMS_STALE");
        }
        if (!"PER_ORDER".equals(c.settleMode()) && !"PERIODIC".equals(c.settleMode())) {
            throw new IllegalArgumentException("POINTS_SETTLE_MODE_INVALID");
        }
        if (c.fundsContext()==null || !c.merchantNo().equals(c.fundsContext().merchantNo())
                || !c.storeNo().equals(c.fundsContext().storeNo())) {
            throw new IllegalArgumentException("POINTS_FUNDS_CONTEXT_INVALID");
        }
        if ("PERIODIC".equals(c.settleMode())) {
            var gateway=funds.getIfAvailable();
            if (c.fundsContext().path()!=PointsFundsGateway.FundsPath.AGGREGATED || gateway==null
                    || !gateway.capabilities(c.fundsContext()).transfer()) {
                throw new IllegalArgumentException("POINTS_CAPABILITY_UNSUPPORTED");
            }
        }
        String key=PointsKeys.position("A",c.typeCode(),c.storeNo());
        var prior=active(c.typeCode(),c.storeNo());
        if (prior!=null) { throw new IllegalArgumentException("POINTS_AGREEMENT_ACTIVE_EXISTS"); }
        AgreementSnapshot signed=new AgreementSnapshot(UUID.randomUUID().toString(),c.typeCode(),c.merchantNo(),
                c.storeNo(),actor,terms.version(),terms.hash(),terms.text(),c.settleMode(),
                c.fundsContext().path().name(),c.fundsContext().channel(),c.fundsContext().accountVersion(),"ACTIVE");
        config.putAgreement(signed.agreementNo(),c.typeCode(),c.storeNo(),key,"ACTIVE",
                json.writeValueAsString(signed),actor);
        return signed;
    }
    @Override public void terminate(String type,String store,String actor) {
        config.lockTypeAndPool(type);
        AgreementSnapshot current=active(type,store);
        if (current==null) { return; }
        if (!merchants.ownerUserNoOf(current.merchantNo()).filter(o -> o.equals(actor)).isPresent()) {
            throw new IllegalArgumentException("POINTS_SIGNER_NOT_OWNER");
        }
        config.terminateAgreement(PointsKeys.position("A",type,store),actor);
    }
    @Override public AgreementSnapshot active(String type,String store) {
        var row=config.agreement(PointsKeys.position("A",type,store));
        return row==null?null:json.readValue((String)row.get("payload_json"),AgreementSnapshot.class);
    }
    private String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public record Terms(String version,String hash,String text) { }
}
