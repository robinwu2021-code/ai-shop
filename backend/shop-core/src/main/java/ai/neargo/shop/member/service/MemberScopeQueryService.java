package ai.neargo.shop.member.service;


/** Authorized membership queries with signed scope-bound continuation cursors. */
public interface MemberScopeQueryService {
    /** Read the resource only after current identity and scope checks. */
    MemberScopeModels.Summary detail(String tenant,String entity,String store,String no);

    /** Search consistent identities using signed cursors bound to the current scope revision. */
    MemberScopeModels.Page search(String tenant,String entity,String store,String actor,String keyword,String cursor,int size);
}
