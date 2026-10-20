package ai.neargo.shop.numbering.core;

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
public class CoreDocumentNumberConfiguration {
    @Bean("coreDocumentNumbers")
    public JdbcDocumentNumberRepository repository(@Qualifier("transactionManager") PlatformTransactionManager manager) {
        if(!(manager instanceof DataSourceTransactionManager jdbc))throw new IllegalStateException("Document numbers require a local JDBC transaction manager");
        return new JdbcDocumentNumberRepository(DocumentOwner.CORE,jdbc);
    }
    @Bean("coreDocumentNumberGenerator")
    public DocumentNumberGenerator generator(@Qualifier("coreDocumentNumbers") JdbcDocumentNumberRepository repository,
            @Value("${shop.numbering.business-zone:Asia/Shanghai}") String zone,
            @Value("${shop.numbering.segment-size:100}") int size) {
        return new SegmentDocumentNumberGenerator(repository,new DocumentNumberProperties(ZoneId.of(zone),size));
    }
    @Bean("coreDocumentNumberCoordinator")
    public DocumentNumberCoordinator coordinator(@Qualifier("coreDocumentNumbers") JdbcDocumentNumberRepository repository,
            @Qualifier("coreDocumentNumberGenerator") DocumentNumberGenerator generator,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new DocumentNumberCoordinator(repository,generator,manager);
    }

}
