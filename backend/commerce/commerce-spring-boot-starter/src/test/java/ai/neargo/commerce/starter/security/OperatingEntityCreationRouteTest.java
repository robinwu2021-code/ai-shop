package ai.neargo.commerce.starter.security;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class OperatingEntityCreationRouteTest {
    final CommerceRoutePolicy policy=new CommerceRoutePolicy();
    @Test void creationAndOriginalResultAreExactAccountRoutes(){
        for(String path:new String[]{"/biz/app/legal-subjects","/biz/app/operating-entities","/biz/app/operating-entities/context","/biz/app/operating-entities/OE1","/biz/app/operating-entities/create-results/original"})
            assertEquals(CommerceRoutePolicy.Lane.ACCOUNT,policy.match("GET",path).lane(),path);
        assertEquals(CommerceRoutePolicy.Lane.ACCOUNT,policy.match("POST","/biz/app/operating-entities").lane());
    }
    @Test void unknownWritesAndPathsStayClosed(){
        for(String path:new String[]{"/biz/app/legal-subjects","/biz/app/operating-entities/OE1","/biz/app/operating-entities/create-results/original","/biz/app/operating-entities/extra/path"})
            assertEquals(CommerceRoutePolicy.Lane.DENY,policy.match("POST",path).lane(),path);
        assertEquals(CommerceRoutePolicy.Lane.DENY,policy.match("PUT","/biz/app/operating-entities").lane());
    }
}
