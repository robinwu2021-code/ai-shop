package ai.neargo.shop.paybridge;

import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness.*;
import ai.neargo.shop.svc.InternalHttp;
import java.time.*;
import java.util.*;
import java.util.function.BiFunction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Internal operator preflight. The sealed deployment inventory is server configuration, never a request body. */
@Service
public class StoredValueMigrationReadinessService {
    private final Inventory inventory;
    private final BiFunction<Node,Query,Capability> probe;
    @Autowired
    public StoredValueMigrationReadinessService(InternalHttp http,ObjectMapper json,
            @Value("${shop.pay.stored-value.migration.inventory:}") String configured) {
        this(configured==null||configured.isBlank()?new Inventory("UNCONFIGURED",null,false,List.of()):json.readValue(configured,Inventory.class),
                (node,query)->http.client(node.serviceKey(),PayInternalApi.class,Duration.ofSeconds(3)).svMigrationCapability(query));
    }
    StoredValueMigrationReadinessService(Inventory inventory,BiFunction<Node,Query,Capability> probe){this.inventory=inventory;this.probe=probe;}
    public Report check() {
        String challenge=UUID.randomUUID().toString();Instant now=Instant.now();
        var preflight=StoredValueMigrationReadiness.check(inventory,List.of(),challenge,now);
        if(preflight.issues().stream().anyMatch(i->!"NODE_MISSING".equals(i.code())))return preflight;
        var observations=new ArrayList<NodeResult>();var query=new Query(challenge);
        for(var node:inventory.nodes()) {
            try {observations.add(new NodeResult(node.nodeId(),probe.apply(node,query),null));}
            catch(RuntimeException unavailable) {observations.add(new NodeResult(node.nodeId(),null,"PROBE_UNAVAILABLE"));}
        }
        return StoredValueMigrationReadiness.check(inventory,observations,challenge,Instant.now());
    }
}
