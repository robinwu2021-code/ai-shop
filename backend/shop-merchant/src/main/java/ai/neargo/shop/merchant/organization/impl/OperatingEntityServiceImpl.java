package ai.neargo.shop.merchant.organization.impl;

import ai.neargo.shop.merchant.organization.OperatingEntityService;

import ai.neargo.shop.common.*;
import ai.neargo.shop.merchant.brand.ManagementModels.Actor;
import ai.neargo.shop.merchant.brand.ManagementRepository;
import ai.neargo.shop.merchant.organization.OperatingEntityModels.*;
import org.springframework.stereotype.Service;
import java.util.*;
import static ai.neargo.shop.merchant.brand.ManagementRepository.*;

@Service
public class OperatingEntityServiceImpl implements OperatingEntityService {
    private final ManagementRepository db;
    public OperatingEntityServiceImpl(ManagementRepository db) { this.db = db; }

    // Staff credentials cannot mint personal ownership. An owner MCH identity must have a real active USR link.
    private Map<String,Object> account(Actor actor, boolean lock) {
        String user = actor.actorKey().substring(actor.actorKey().indexOf(':') + 1);
        if (actor.actorKey().startsWith("MCH:")) {
            var link = db.one("SELECT user_no FROM mch_account WHERE tenant_no=? AND mch_account_no=? AND is_owner=1 AND status='ACTIVE' AND deleted=0", actor.tenantNo(), user);
            user = str(link, "user_no");
            if (user == null) throw BizException.of(ErrorCode.FORBIDDEN);
        }
        var row = db.one("SELECT user_no,market FROM usr_account WHERE tenant_no=? AND user_no=? AND status='NORMAL' AND deleted=0" + (lock ? " FOR UPDATE" : ""), actor.tenantNo(), user);
        if (row == null) throw BizException.of(ErrorCode.FORBIDDEN);
        return row;
    }
    public Context context(Actor actor) {
        var row = account(actor, false);
        String market = str(row, "market");
        return new Context(market, Set.of("CN", "TW", "AE").contains(market == null ? "" : market));
    }
    public List<View> list(Actor actor) {
        String owner = str(account(actor, false), "user_no");
        return db.rows("SELECT * FROM mch_operating_entity WHERE tenant_no=? AND owner_user_no=? AND deleted=0 ORDER BY id DESC", actor.tenantNo(), owner).stream().map(this::view).toList();
    }
    public View get(Actor actor, String no) {
        String owner = str(account(actor, false), "user_no");
        return view(db.one("SELECT * FROM mch_operating_entity WHERE tenant_no=? AND owner_user_no=? AND operating_entity_no=? AND deleted=0", actor.tenantNo(), owner, no));
    }
    public View result(Actor actor, String key) {
        String owner = str(account(actor, false), "user_no");
        return view(db.one("SELECT * FROM mch_operating_entity WHERE tenant_no=? AND owner_user_no=? AND create_key=? AND deleted=0", actor.tenantNo(), owner, key));
    }
    public List<LegalSubject> legalSubjects(Actor actor) {
        String owner = str(account(actor, false), "user_no");
        return db.rows("SELECT e.* FROM mch_entity e WHERE e.tenant_no=? AND e.deleted=0 AND e.status='ACTIVE' AND e.verified=1 AND EXISTS (SELECT 1 FROM mch_account a WHERE a.tenant_no=e.tenant_no AND a.entity_no=e.entity_no AND a.user_no=? AND a.is_owner=1 AND a.status='ACTIVE' AND a.deleted=0) ORDER BY e.id DESC", actor.tenantNo(), owner)
            .stream().filter(r -> Set.of("CN", "TW", "AE").contains(str(r,"market")))
            .map(r -> new LegalSubject(str(r,"entity_no"),str(r,"name"),Market.valueOf(str(r,"market")),str(r,"legal_form"),num(r,"version"),"ACTIVE",true)).toList();
    }
    public View create(Actor actor, Create command, String key) {
        String name = command.name().trim();
        String hash = hash(db.encode(Arrays.asList(name, command.market().name(), command.legalSubjectId())));
        return db.write(() -> {
            var account = account(actor, true);
            String owner = str(account,"user_no");
            var prior = db.one("SELECT * FROM mch_operating_entity WHERE tenant_no=? AND owner_user_no=? AND create_key=?",actor.tenantNo(),owner,key);
            if (prior != null) {
                if (!hash.equals(str(prior,"create_hash"))) throw BizException.of(ErrorCode.CONFLICT);
                return view(prior);
            }
            if (!command.market().name().equals(str(account,"market"))) throw BizException.of(ErrorCode.CONFLICT);
            if (command.legalSubjectId() != null) {
                var legal = db.one("SELECT e.market FROM mch_entity e JOIN mch_account a ON a.tenant_no=e.tenant_no AND a.entity_no=e.entity_no WHERE e.tenant_no=? AND e.entity_no=? AND e.deleted=0 AND e.status='ACTIVE' AND e.verified=1 AND a.user_no=? AND a.is_owner=1 AND a.status='ACTIVE' AND a.deleted=0 LIMIT 1 FOR UPDATE",actor.tenantNo(),command.legalSubjectId(),owner);
                if (legal == null) throw BizException.of(ErrorCode.NOT_FOUND);
                if (!command.market().name().equals(str(legal,"market"))) throw BizException.of(ErrorCode.CONFLICT);
            }
            var row = db.base(actor);
            row.put("operating_entity_no",BizKey.next(BizKey.OPERATING_ENTITY));
            row.put("owner_user_no",owner);row.put("name",name);row.put("market",command.market().name());
            row.put("legal_subject_no",command.legalSubjectId());row.put("status","ENABLED");
            row.put("create_key",key);row.put("create_hash",hash);
            db.insert("mch_operating_entity",row);
            return view(row);
        });
    }
    private View view(Map<String,Object> row) {
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return new View(str(row,"operating_entity_no"),str(row,"name"),Market.valueOf(str(row,"market")),str(row,"status"),num(row,"version"),str(row,"legal_subject_no"),Set.of("operatingEntity.read"));
    }
}
