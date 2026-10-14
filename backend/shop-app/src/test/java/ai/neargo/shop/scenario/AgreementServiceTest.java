package ai.neargo.shop.scenario;

import ai.neargo.shop.pay.points.agreement.AgreementService;
import ai.neargo.shop.pay.points.model.RedeemRate;
import ai.neargo.shop.pay.points.port.PointsFundsGateway;
import ai.neargo.shop.pay.points.type.PointTypeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:points_agreement;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class AgreementServiceTest {
    @Autowired private ApplicationContext context;
    @Autowired private PointTypeService types;

    @Test
    void authorized_signer_and_digest() throws Exception {
        assertThat(context.getBeanNamesForType(AgreementService.class))
                .as("acceptance service required").isNotEmpty();
        String type="AG"+UUID.randomUUID().toString().replace("-", "").substring(0,20);
        types.create(new PointTypeService.CreateType(type,"CN",new RedeemRate(1,1),3000,365),"ops");
        JdbcTemplate db=new JdbcTemplate(context.getBean("dataSource",DataSource.class));
        db.update("INSERT INTO mch_entity(entity_no,name,owner_user_no,status,created_at,updated_at)"
                + " VALUES('M-AGR','Merchant','OWNER','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        db.update("INSERT INTO mch_store(entity_no,store_no,name,status,created_at,updated_at)"
                + " VALUES('M-AGR','ST-AGR','Store','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        AgreementService agreements=context.getBean(AgreementService.class);
        String text="TEST terms: platform funds redeemed points; existing holds retain their snapshot.";
        agreements.publishTerms(type,"v1",text,"ops");
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
        var funds=new PointsFundsGateway.FundsContext(PointsFundsGateway.FundsPath.DIRECT,"TEST",
                "M-AGR","a1",null,null,null,0);
        var cmd=new AgreementService.SignCommand(type,"M-AGR","ST-AGR","v1",hash,"PER_ORDER",funds);
        assertThatThrownBy(() -> agreements.sign(cmd,"EMPLOYEE")).isInstanceOf(IllegalArgumentException.class);
        var signed=agreements.sign(cmd,"OWNER");
        assertThat(signed.signedBy()).isEqualTo("OWNER");
        assertThat(signed.termsText()).isEqualTo(text);
        assertThat(signed.termsHash()).isEqualTo(hash);
        agreements.terminate(type,"ST-AGR","OWNER");
        assertThat(agreements.active(type,"ST-AGR")).isNull();
        assertThat(signed.termsHash()).isEqualTo(hash);
    }
}
