package ai.neargo.shop.pay.numbering;

import ai.neargo.shop.numbering.*;
import ai.neargo.shop.numbering.store.*;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods=false)
public class PayDocumentNumberConfiguration {
    public static boolean memberMarketing(DocumentType type) {
        return switch (type) {
            case SV_RECHARGE, SV_VOUCHER, SV_SETTLEMENT_BILL, SV_SETTLEMENT_PAYMENT, SV_SETTLEMENT_EVENT,
                    ENTITLEMENT_USAGE, ENTITLEMENT_GRANT, ENTITLEMENT_ISSUE_BATCH, LEGACY_COUPON_ISSUE,
                    ENTITLEMENT_MIGRATION_PLAN, ENTITLEMENT_MIGRATION_IMPORT, ENTITLEMENT_MIGRATION_STAGE,
                    ENTITLEMENT_RECON_RUN, ENTITLEMENT_RECON_DIFF, ENTITLEMENT_EXCEPTION, ENTITLEMENT_EXCEPTION_ACTION -> true;
            default -> false;
        };
    }

    @Bean("payDocumentNumbers")
    public JdbcDocumentNumberRepository repository(@Qualifier("payTxManager") PlatformTransactionManager manager) {
        if(!(manager instanceof DataSourceTransactionManager jdbc))throw new IllegalStateException("Document numbers require a local JDBC transaction manager");
        return new JdbcDocumentNumberRepository(DocumentOwner.PAY,jdbc);
    }
    @Bean("payDocumentNumberGenerator")
    public DocumentNumberGenerator generator(@Qualifier("payDocumentNumbers") JdbcDocumentNumberRepository repository,
            @Value("${shop.numbering.business-zone:Asia/Shanghai}") String zone,
            @Value("${shop.numbering.segment-size:100}") int size) {
        return new SegmentDocumentNumberGenerator(repository,new DocumentNumberProperties(ZoneId.of(zone),size));
    }
    @Bean("payDocumentNumberCoordinator")
    public DocumentNumberCoordinator coordinator(@Qualifier("payDocumentNumbers") JdbcDocumentNumberRepository repository,
            @Qualifier("payDocumentNumberGenerator") DocumentNumberGenerator generator,
            @Qualifier("payTxManager") PlatformTransactionManager manager) {
        return new DocumentNumberCoordinator(repository,generator,manager);
    }
    @Bean("memberMarketingDocumentNumbers")
    public JdbcDocumentNumberRepository memberMarketingRepository(@Qualifier("memberMarketingTxManager") PlatformTransactionManager manager) {
        return repository(manager);
    }
    @Bean("memberMarketingDocumentNumberGenerator")
    public DocumentNumberGenerator memberMarketingGenerator(@Qualifier("memberMarketingDocumentNumbers") JdbcDocumentNumberRepository repository,
            @Value("${shop.numbering.business-zone:Asia/Shanghai}") String zone,
            @Value("${shop.numbering.segment-size:100}") int size) {
        return generator(repository, zone, size);
    }
    @Bean("memberMarketingDocumentNumberCoordinator")
    public DocumentNumberCoordinator memberMarketingCoordinator(@Qualifier("memberMarketingDocumentNumbers") JdbcDocumentNumberRepository repository,
            @Qualifier("memberMarketingDocumentNumberGenerator") DocumentNumberGenerator generator,
            @Qualifier("memberMarketingTxManager") PlatformTransactionManager manager) {
        return coordinator(repository, generator, manager);
    }

}
