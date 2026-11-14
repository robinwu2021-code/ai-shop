package ai.neargo.shop.paybridge;

import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness.*;
import ai.neargo.shop.svc.InternalHttp;
import java.time.*;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Internal operator preflight. The sealed deployment inventory is server configuration, never a request body. */
@Service
public class StoredValueMigrationReadinessService {
    private final Supplier<Inventory> inventories;
    private final BiFunction<Node,Query,Capability> probe;
    @Autowired
    public StoredValueMigrationReadinessService(InternalHttp http,ObjectMapper json,
            @Value("${shop.pay.stored-value.migration.inventory:}") String configured,
            @Value("${shop.pay.stored-value.migration.inventory-file:}") String file) {
        this(()->load(json,configured,file),
                (node,query)->http.client(node.serviceKey(),PayInternalApi.class,Duration.ofSeconds(3)).svMigrationCapability(query));
    }
    StoredValueMigrationReadinessService(Inventory inventory,BiFunction<Node,Query,Capability> probe){this(()->inventory,probe);}
    StoredValueMigrationReadinessService(Supplier<Inventory> inventories,BiFunction<Node,Query,Capability> probe){this.inventories=inventories;this.probe=probe;}
    static Inventory load(ObjectMapper json,String inline,String file){
        try {
            if(file!=null&&!file.isBlank()) {
                var path=Path.of(file);
                if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))try(var input=Files.newInputStream(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
                    var bytes=input.readNBytes(65537);
                    if(bytes.length<=65536)return json.readValue(bytes,Inventory.class);
                }
                // A configured missing/invalid file must not reactivate a stale inline inventory.
            }else if(inline!=null&&!inline.isBlank())return json.readValue(inline,Inventory.class);
        }catch(IOException|RuntimeException invalid){ /* Only unready evidence; never expose a path or raw parse error. */ }
        return new Inventory("UNCONFIGURED",null,false,List.of());
    }
    public Report check() {return proof().report();}
    public Proof proof() {
        var inventory=inventories.get();
        String challenge=UUID.randomUUID().toString();Instant now=Instant.now();
        var preflight=StoredValueMigrationReadiness.check(inventory,List.of(),challenge,now);
        if(preflight.issues().stream().anyMatch(i->!"NODE_MISSING".equals(i.code())))return new Proof(inventory,preflight);
        var observations=new ArrayList<NodeResult>();var query=new Query(challenge);
        for(var node:inventory.nodes()) {
            try {observations.add(new NodeResult(node.nodeId(),probe.apply(node,query),null));}
            catch(RuntimeException unavailable) {observations.add(new NodeResult(node.nodeId(),null,"PROBE_UNAVAILABLE"));}
        }
        return new Proof(inventory,StoredValueMigrationReadiness.check(inventory,observations,challenge,Instant.now()));
    }
}
