package ai.neargo.shop.pay.entitlement.service;

import static ai.neargo.shop.pay.numbering.PayDocumentNumberConfiguration.memberMarketing;

import ai.neargo.shop.numbering.DocumentNumberService;
import ai.neargo.shop.numbering.DocumentSource;
import ai.neargo.shop.numbering.DocumentType;
import ai.neargo.shop.numbering.store.DocumentNumberCoordinator;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Human document aliases only; asset, idempotency and event identities remain unchanged. */
@Component
public class PayRightsDocumentNumbers {
    private final DocumentNumberCoordinator numbers;
    @Autowired
    @Qualifier("payDocumentNumbers")
    private DocumentNumberService lookup;
    @Autowired @Qualifier("memberMarketingDocumentNumberCoordinator")
    private DocumentNumberCoordinator memberNumbers;
    @Autowired @Qualifier("memberMarketingDocumentNumbers")
    private DocumentNumberService memberLookup;

    public PayRightsDocumentNumbers(@Qualifier("payDocumentNumberCoordinator") DocumentNumberCoordinator numbers) {
        this.numbers = numbers;
    }
    public String bind(DocumentType type, String tenant, String internalNo) {
        return coordinator(type).bind(type, source(type, tenant, internalNo));
    }
    public String find(DocumentType type, String tenant, String internalNo) {
        return internalNo == null ? null : coordinator(type).find(type, source(type, tenant, internalNo)).orElse(null);
    }
    public Map<String,String> findAll(DocumentType type, String tenant, Collection<String> internalNos) {
        var sources=internalNos.stream().filter(Objects::nonNull).distinct()
                .map(no -> source(type,tenant,no)).toList();
        if(sources.isEmpty()) return Map.of();
        var found=lookup(type).findAll(type,sources);
        var result=new HashMap<String,String>();
        found.forEach((source,number) -> result.put(source.sourceNo(),number));
        return result;
    }
    /** Resolving an alias changes only the locator; callers retain their existing ownership filters. */
    public String resolve(DocumentType type, String tenant, String value) {
        if (value == null || !value.matches(type.prefix()+"[0-9]{14,}")) return value;
        return lookup(type).resolve(tenant,type,value).filter(s -> "PAY".equals(s.sourceSystem())
                && type.name().equals(s.resourceType())).map(DocumentSource::sourceNo).orElse(value);
    }
    private DocumentNumberCoordinator coordinator(DocumentType type) {
        return memberMarketing(type) ? memberNumbers : numbers;
    }
    private DocumentNumberService lookup(DocumentType type) {
        return memberMarketing(type) ? memberLookup : lookup;
    }
    private static DocumentSource source(DocumentType type, String tenant, String no) {
        return new DocumentSource(tenant, "PAY", type.name(), no);
    }
}
