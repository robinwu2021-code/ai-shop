package ai.neargo.shop.spi.storedvalue;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** Trusted deployment inventory and fresh per-process observations; never a public financial authorization. */
public final class StoredValueMigrationReadiness {
    private StoredValueMigrationReadiness() { }
    public record Query(String challenge) { }
    public record Node(String nodeId,String serviceKey,String application,String expectedSha,String expectedProcessNo) { }
    /** Sealing is an operator assertion that this inventory covers every ledger writer, including jobs/callbacks. */
    public record Inventory(String revision,String ledgerFingerprint,boolean sealed,List<Node> nodes) {
        public Inventory { nodes=List.copyOf(nodes); }
    }
    public record Capability(String challenge,String application,String processNo,String buildSha,int protocol,
            boolean writerGuardEnabled,boolean maintenanceEnabled,String ledgerFingerprint,boolean schemaReady,Instant checkedAt) { }
    public record NodeResult(String nodeId,Capability capability,String errorCode) { }
    public record Proof(Inventory inventory,Report report) { }
    public record Issue(String nodeId,String code) { }
    public record Report(boolean ready,String inventoryHash,Instant checkedAt,Instant expiresAt,List<Issue> issues,List<NodeResult> nodes) {
        public Report { issues=List.copyOf(issues);nodes=List.copyOf(nodes); }
    }
    public static Report check(Inventory inventory,List<NodeResult> results,String challenge,Instant now) {
        var issues=new ArrayList<Issue>();var expected=new HashMap<String,Node>();var serviceKeys=new HashSet<String>();
        if(!inventory.sealed())issues.add(new Issue(null,"INVENTORY_UNSEALED"));
        if(!id(inventory.revision())||inventory.nodes().isEmpty()||inventory.nodes().size()>16)issues.add(new Issue(null,"INVALID_INVENTORY"));
        if(!digest(inventory.ledgerFingerprint(),64))issues.add(new Issue(null,"INVALID_LEDGER"));
        if(!id(challenge))issues.add(new Issue(null,"INVALID_CHALLENGE"));
        for(var node:inventory.nodes()) {
            if(!id(node.nodeId())||!id(node.serviceKey())||!Set.of("shop-app","pay-svc").contains(Objects.toString(node.application(),""))
                    ||!digest(node.expectedSha(),40)||!id(node.expectedProcessNo())||expected.putIfAbsent(node.nodeId(),node)!=null||!serviceKeys.add(node.serviceKey().toUpperCase(Locale.ROOT)))
                issues.add(new Issue(node.nodeId(),"INVALID_NODE"));
        }
        var seen=new HashSet<String>();var processes=new HashSet<String>();Instant expires=now.plusSeconds(30);
        for(var result:results) {
            var node=expected.get(result.nodeId());var cap=result.capability();
            if(node==null||!seen.add(result.nodeId())) {issues.add(new Issue(result.nodeId(),"UNEXPECTED_OR_DUPLICATE_NODE"));continue;}
            if(cap==null||result.errorCode()!=null) {issues.add(new Issue(node.nodeId(),"PROBE_UNAVAILABLE"));continue;}
            if(!Objects.equals(challenge,cap.challenge()))issues.add(new Issue(node.nodeId(),"CHALLENGE_MISMATCH"));
            if(!Objects.equals(node.application(),cap.application())||!Objects.equals(node.expectedSha(),cap.buildSha()))issues.add(new Issue(node.nodeId(),"BUILD_MISMATCH"));
            if(!id(cap.processNo())||!Objects.equals(node.expectedProcessNo(),cap.processNo())||!processes.add(cap.processNo()))issues.add(new Issue(node.nodeId(),"DUPLICATE_OR_UNKNOWN_PROCESS"));
            if(cap.protocol()!=2||!cap.writerGuardEnabled()||!cap.maintenanceEnabled())issues.add(new Issue(node.nodeId(),"WRITER_NOT_READY"));
            if(!Objects.equals(inventory.ledgerFingerprint(),cap.ledgerFingerprint())||!cap.schemaReady())issues.add(new Issue(node.nodeId(),"LEDGER_NOT_READY"));
            if(cap.checkedAt()==null||!cap.checkedAt().isAfter(now.minusSeconds(30))||cap.checkedAt().isAfter(now.plusSeconds(5)))issues.add(new Issue(node.nodeId(),"STALE_OBSERVATION"));
            else if(cap.checkedAt().plusSeconds(30).isBefore(expires))expires=cap.checkedAt().plusSeconds(30);
        }
        for(var node:inventory.nodes())if(!seen.contains(node.nodeId()))issues.add(new Issue(node.nodeId(),"NODE_MISSING"));
        String material=field(inventory.revision())+field(inventory.ledgerFingerprint())+inventory.sealed()+inventory.nodes().stream()
                .sorted(Comparator.comparing(n->Objects.toString(n.nodeId(),""))).map(n->field(n.nodeId())+field(n.serviceKey())+field(n.application())+field(n.expectedSha())+field(n.expectedProcessNo())).reduce("",String::concat);
        return new Report(issues.isEmpty(),hash(material),now,expires,issues,results);
    }
    private static boolean id(String s){return s!=null&&!s.isBlank()&&s.length()<=64&&s.equals(s.strip());}
    private static boolean digest(String s,int size){return s!=null&&s.matches("[a-f0-9]{"+size+"}");}
    private static String field(String s){return s==null?"-1:":s.length()+":"+s;}
    private static String hash(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
