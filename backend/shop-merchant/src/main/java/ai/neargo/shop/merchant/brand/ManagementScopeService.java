package ai.neargo.shop.merchant.brand;

import ai.neargo.shop.merchant.brand.ManagementModels.Actor;
import ai.neargo.shop.merchant.brand.ManagementModels.GrantCommand;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Management-space authorization and durable grants, independent of the selected store. */
public interface ManagementScopeService {
    /** Read tenant-qualified space facts for cooperating authorization services. */
    Map<String,Object> space(Actor a,String m);

    /** Resolve only a live scope that the actor is currently permitted to enter. */
    Map<String,Object> resolve(Actor a,String m);

    /** Lock live space authority inside the caller’s existing source transaction. */
    void lock(Actor a,String m);

    /** Check current effective authority, including the full grant source chain. */
    boolean can(Actor a,String m,String type,String no,String permission);

    /** Validate the complete original grant chain before disclosing its resource. */
    boolean valid(Actor a,Map<String,Object> g,Set<String> seen);

    /** Resolve the operating entity of an existing tenant-qualified resource. */
    String resourceEntity(Actor a,String type,String no);

    /** Reject access unless the current scope or membership satisfies the original rules. */
    void require(Actor a,String m,String type,String no,String permission);

    /** Return only active ownership accounts belonging to the authenticated actor. */
    List<Map<String,Object>> ownerAccounts(Actor a);

    /** Return currently accessible authority sources and management spaces. */
    Map<String,Object> context(Actor a);

    /** Create under verified authority while preserving the original idempotent result. */
    Map<String,Object> create(Actor a,String name,String source,String key);

    /** Seed creator capabilities only within the authorized brand creation transaction. */
    void seedBrandCreator(Actor a,String m,String brand);

    /** Create or reactivate the exact authorized resource capability with source-chain checks. */
    Map<String,Object> grant(Actor a,String m,GrantCommand c);

    /** Check original ownership or valid parent-grant delegation authority. */
    boolean canDelegate(Actor a,String m,String type,String no,String permission);

    /** Revoke the original grant using its current revision and audit reason. */
    Map<String,Object> revoke(Actor a,String m,String no,long revision,String reason);
}
