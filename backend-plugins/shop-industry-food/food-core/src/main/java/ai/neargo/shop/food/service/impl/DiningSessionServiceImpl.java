package ai.neargo.shop.food.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.dto.DiningSessionTableVO;
import ai.neargo.shop.food.dto.DiningSessionVO;
import ai.neargo.shop.food.dto.OperationResultVO;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.dto.RevokeTableTokenReq;
import ai.neargo.shop.food.dto.RotateTableTokenReq;
import ai.neargo.shop.food.dto.StartDiningSessionReq;
import ai.neargo.shop.food.dto.TableTokenVO;
import ai.neargo.shop.food.dto.TableVO;
import ai.neargo.shop.food.entity.FnbActionLog;
import ai.neargo.shop.food.entity.FnbGuestSession;
import ai.neargo.shop.food.entity.FnbTable;
import ai.neargo.shop.food.entity.FnbTableArea;
import ai.neargo.shop.food.entity.FnbTableToken;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.GuestSessionMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableAreaMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableTokenMapper;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.service.DiningSessionService;
import ai.neargo.shop.spi.idempotency.RequestIdempotencyPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Conditional;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Restricted dining credentials; the platform remains the sole owner of customer login identity. */
@Service
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class DiningSessionServiceImpl implements DiningSessionService {
    private final TableMapper tables;
    private final TableTokenMapper tokens;
    private final GuestSessionMapper sessions;
    private final ActionLogMapper actions;
    private final RequestIdempotencyPort idem;
    private final TransactionTemplate tx;
    private static final SecureRandom RANDOM=new SecureRandom();

    public DiningSessionServiceImpl(TableAreaMapper areas,TableMapper tables,TableTokenMapper tokens,
            GuestSessionMapper sessions,ActionLogMapper actions,RequestIdempotencyPort idem,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.tables=tables;this.tokens=tokens;this.sessions=sessions;this.actions=actions;
        this.idem=idem;this.areas=areas;
        this.tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    // 桌台在行业库，令牌与顾客会话归平台（ADR-068 修订二、处置提案）：不跨库锁桌台。
    // 同一张桌的令牌轮换、撤销与开会话靠令牌行自身的行锁排队（撤销是带条件的更新，开会话锁令牌行）；
    // 桌台只读一次比对版本与状态，版本号在本事务提交后再加（带原版本条件，可重复）。
    private final TableAreaMapper areas;

    private FnbTable tableAt(String entity,String store,String table,Long version,boolean active) {
        var rows=tables.selectList(Wrappers.<FnbTable>lambdaQuery().eq(FnbTable::getEntityNo,entity)
                .eq(FnbTable::getStoreNo,store).eq(FnbTable::getTableNo,table).last("LIMIT 1"));
        if(rows.isEmpty()) throw BizException.of(ErrorCode.NOT_FOUND);
        var row=rows.getFirst();
        if(version!=null && !row.getVersion().equals(version)) throw BizException.of(ErrorCode.CONFLICT);
        if(active) {
            var area=areas.selectList(Wrappers.<FnbTableArea>lambdaQuery().eq(FnbTableArea::getEntityNo,entity)
                    .eq(FnbTableArea::getStoreNo,store).eq(FnbTableArea::getAreaNo,row.getAreaNo()).last("LIMIT 1"));
            if(area.isEmpty()) throw BizException.of(ErrorCode.NOT_FOUND);
            if(!"ACTIVE".equals(row.getStatus()) || !"ACTIVE".equals(area.getFirst().getStatus()))
                throw BizException.of(ErrorCode.CONFLICT);
        }
        return row;
    }

    /** 提交后给桌台加版本（带原版本条件；别人先加了就不再加）。返回本次命令给出的结果版本。 */
    private long bumpAfterCommit(FnbTable row,String actor) {
        long expected=row.getVersion();
        ai.neargo.shop.food.service.support.IndustryFollowUp.afterTradeCommit(() -> tables.update(null,
                Wrappers.<FnbTable>lambdaUpdate().eq(FnbTable::getId,row.getId()).eq(FnbTable::getVersion,expected)
                        .set(FnbTable::getVersion,expected+1).set(FnbTable::getUpdatedBy,actor)
                        .set(FnbTable::getUpdatedAt,LocalDateTime.now())));
        return expected+1;
    }

    @Override public TableTokenVO rotateTableToken(String entity,String store,String table,String actor,
            RotateTableTokenReq req,String key) {
        if(req==null || req.expiresAt()==null || !req.expiresAt().isAfter(Instant.now())) bad();
        if(req.expiresAt().isAfter(Instant.parse("9999-12-31T23:59:59.999Z"))) bad();
        version(req.version());id(table);
        var expiry=req.expiresAt().truncatedTo(ChronoUnit.MILLIS);
        var secret=new AtomicReference<String>();
        command("ROTATE_TABLE_TOKEN",entity,store,actor,key,
                FoodCommandFingerprint.hash(table,req.version().toString(),expiry.toString()), () -> {
                    var row=tableAt(entity,store,table,req.version(),true);
                    var before=view(row);var now=now();
                    revoke(entity,store,table,now,"ROTATED",actor);
                    String raw=randomToken("ft_");
                    var token=new FnbTableToken();token.setEntityNo(entity);token.setTokenNo(BizKey.next("FTOK"));
                    token.setTableNo(table);token.setTokenHash(hash(raw));token.setExpiresAt(local(expiry));
                    token.setVersion(0L);tokens.insert(token);
                    long version=bumpAfterCommit(row,actor);String op=tableEvent(row,actor,"ROTATE_TABLE_TOKEN",null,before,key);
                    secret.set(raw);
                    return new OperationResultVO(new OperationVO(op,"SUCCEEDED",version,table));
                });
        if(secret.get()==null) throw BizException.of(ErrorCode.CONFLICT);
        return new TableTokenVO(secret.get(),expiry);
    }
    @Override public OperationResultVO revokeTableToken(String entity,String store,String table,String actor,
            RevokeTableTokenReq req,String key) {
        if(req==null) bad();version(req.version());id(table);String reason=text(req.reason(),255);
        return command("REVOKE_TABLE_TOKEN",entity,store,actor,key,
                FoodCommandFingerprint.hash(table,req.version().toString(),reason), () -> {
                    var row=tableAt(entity,store,table,req.version(),false);
                    var before=view(row);revoke(entity,store,table,now(),reason,actor);long version=bumpAfterCommit(row,actor);
                    String op=tableEvent(row,actor,"REVOKE_TABLE_TOKEN",reason,before,key);
                    return new OperationResultVO(new OperationVO(op,"SUCCEEDED",version,table));
                });
    }
    @Override public DiningSessionTableVO resolveTableToken(String raw) {
        // A valid hashed credential is the authority here, not a platform SELF account column.
        return DataScopeContext.executeWithoutScope(() -> resolveCredentialTable(raw));
    }
    private DiningSessionTableVO resolveCredentialTable(String raw) {
        var token=validToken(raw,false);
        var found=tables.selectList(Wrappers.<FnbTable>lambdaQuery().eq(FnbTable::getEntityNo,token.getEntityNo())
                .eq(FnbTable::getTableNo,token.getTableNo()).eq(FnbTable::getStatus,"ACTIVE").last("LIMIT 1"));
        if(found.isEmpty()) throw BizException.of(ErrorCode.NOT_FOUND);
        var row=found.getFirst();
        return new DiningSessionTableVO(row.getEntityNo(),row.getStoreNo(),row.getTableNo(),
                token.getExpiresAt().toInstant(ZoneOffset.UTC));
    }
    @Override public DiningSessionVO startSession(String entity,String store,String buyer,StartDiningSessionReq req,
            String key) {
        return DataScopeContext.executeWithoutScope(() -> startCredentialSession(entity,store,buyer,req,key));
    }
    private DiningSessionVO startCredentialSession(String entity,String store,String buyer,StartDiningSessionReq req,
            String key) {
        if(req==null) bad();
        if(req.deviceCredential()!=null && !req.deviceCredential().isBlank()) throw BizException.of(ErrorCode.FORBIDDEN);
        if(buyer!=null) id(buyer);
        var target=resolveTableToken(req.tableToken());
        if(!target.entityNo().equals(entity) || !target.storeNo().equals(store)) throw BizException.of(ErrorCode.NOT_FOUND);
        String actor=buyer==null ? "GUEST":buyer;
        var raw=new AtomicReference<String>();var expiry=new AtomicReference<Instant>();
        var result=command("START_DINING_SESSION",entity,store,actor,key,
                FoodCommandFingerprint.hash(req.tableToken(),buyer==null ? "":buyer), () -> {
                    tableAt(entity,store,target.tableNo(),null,true);
                    var token=validToken(req.tableToken(),true);
                    if(!token.getEntityNo().equals(entity) || !token.getTableNo().equals(target.tableNo()))
                        throw BizException.of(ErrorCode.NOT_FOUND);
                    Instant end=Instant.now().plus(30,ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);
                    if(end.isAfter(target.expiresAt())) end=target.expiresAt();
                    var session=new FnbGuestSession();session.setEntityNo(entity);session.setStoreNo(store);
                    session.setTableNo(target.tableNo());session.setSessionNo(BizKey.next("FGSS"));session.setBuyerNo(buyer);
                    String access=randomToken("fs_");session.setTokenHash(hash(access));session.setExpiresAt(local(end));
                    session.setScope("[\"TABLE_MENU\"]");session.setVersion(0L);
                    sessions.insert(session);
                    String op=sessionEvent(session,actor,"START_DINING_SESSION",key);
                    raw.set(access);expiry.set(end);
                    return new OperationResultVO(new OperationVO(op,"SUCCEEDED",0L,session.getSessionNo()));
                });
        if(raw.get()==null) throw BizException.of(ErrorCode.CONFLICT);
        return new DiningSessionVO(result.operation().resultRef(),raw.get(),expiry.get(),null);
    }
    @Override public OperationResultVO endSession(String session,String access,String buyer,String key) {
        return DataScopeContext.executeWithoutScope(() -> endCredentialSession(session,access,buyer,key));
    }
    private OperationResultVO endCredentialSession(String session,String access,String buyer,String key) {
        id(session);text(access,128);
        var rows=sessions.selectList(Wrappers.<FnbGuestSession>lambdaQuery().eq(FnbGuestSession::getSessionNo,session)
                .eq(FnbGuestSession::getTokenHash,hash(access)).last("LIMIT 1"));
        if(rows.isEmpty() || rows.getFirst().getBuyerNo()!=null && !Objects.equals(rows.getFirst().getBuyerNo(),buyer)) throw BizException.of(ErrorCode.NOT_FOUND);
        var snapshot=rows.getFirst();String actor=buyer==null ? session:buyer;
        return command("END_DINING_SESSION",snapshot.getEntityNo(),snapshot.getStoreNo(),actor,key,
                FoodCommandFingerprint.hash(session,access), () -> {
                    var row=sessions.selectList(Wrappers.<FnbGuestSession>lambdaQuery()
                            .eq(FnbGuestSession::getEntityNo,snapshot.getEntityNo())
                            .eq(FnbGuestSession::getSessionNo,session).eq(FnbGuestSession::getTokenHash,hash(access))
                            .last("LIMIT 1 FOR UPDATE")).getFirst();
                    if(row.getRevokedAt()!=null || !row.getExpiresAt().isAfter(now()))
                        throw BizException.of(ErrorCode.NOT_FOUND);
                    if(row.getRevokedAt()==null) {
                        row.setRevokedAt(now());
                        if(sessions.updateById(row)!=1) throw BizException.of(ErrorCode.CONFLICT);
                    }
                    String op=sessionEvent(row,actor,"END_DINING_SESSION",key);
                    return new OperationResultVO(new OperationVO(op,"SUCCEEDED",row.getVersion(),session));
                });
    }
    private FnbTableToken validToken(String raw,boolean lock) {
        text(raw,128);
        var rows=tokens.selectList(Wrappers.<FnbTableToken>lambdaQuery().eq(FnbTableToken::getTokenHash,hash(raw))
                .isNull(FnbTableToken::getRevokedAt).gt(FnbTableToken::getExpiresAt,now())
                .last(lock ? "LIMIT 1 FOR UPDATE":"LIMIT 1"));
        if(rows.isEmpty()) throw BizException.of(ErrorCode.NOT_FOUND);
        return rows.getFirst();
    }
    private void revoke(String entity,String store,String table,LocalDateTime now,String reason,String actor) {
        tokens.update(null,Wrappers.<FnbTableToken>lambdaUpdate().eq(FnbTableToken::getEntityNo,entity)
                .eq(FnbTableToken::getTableNo,table).isNull(FnbTableToken::getRevokedAt)
                .set(FnbTableToken::getRevokedAt,now).set(FnbTableToken::getRevokedReason,reason).set(FnbTableToken::getUpdatedAt,LocalDateTime.now())
                .set(FnbTableToken::getUpdatedBy,actor));
        sessions.update(null,Wrappers.<FnbGuestSession>lambdaUpdate().eq(FnbGuestSession::getEntityNo,entity)
                .eq(FnbGuestSession::getStoreNo,store).eq(FnbGuestSession::getTableNo,table)
                .isNull(FnbGuestSession::getRevokedAt).set(FnbGuestSession::getRevokedAt,now).set(FnbGuestSession::getUpdatedAt,LocalDateTime.now())
                .set(FnbGuestSession::getUpdatedBy,actor));
    }
    private OperationResultVO command(String action,String entity,String store,String actor,String key,String hash,
            Supplier<OperationResultVO> work) {
        id(entity);id(store);id(actor);text(key,128);
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Dining command cannot join transaction");
        try {
            return tx.execute(status -> idem.execute(key,FoodCommandFingerprint.scope(action,entity,store),actor,hash,
                    OperationResultVO.class,work));
        } catch(ConcurrencyFailureException | DuplicateKeyException conflict) { throw BizException.of(ErrorCode.CONFLICT); }
    }
    private String tableEvent(FnbTable row,String actor,String action,String reason,TableVO before,String key) {
        String op=audit(row.getEntityNo(),row.getStoreNo(),"TABLE",row.getTableNo(),actor,action,reason,key);
        return op;
    }
    private String sessionEvent(FnbGuestSession row,String actor,String action,String key) {
        String op=audit(row.getEntityNo(),row.getStoreNo(),"GUEST_SESSION",row.getSessionNo(),actor,action,null,key);
        return op;
    }
    private String audit(String entity,String store,String type,String object,String actor,String action,String reason,String key) {
        var row=new FnbActionLog();row.setEntityNo(entity);row.setStoreNo(store);row.setActionNo(BizKey.next("FACT"));
        row.setObjectType(type);row.setObjectNo(object);row.setOperatorNo(actor);row.setAction(action);row.setReason(reason);
        row.setRequestNo(FoodCommandFingerprint.hash(FoodCommandFingerprint.scope(action,entity,store),key));
        actions.insert(row);return row.getActionNo();
    }
    private static TableVO view(FnbTable row) {
        return new TableVO(row.getTableNo(),row.getStoreNo(),row.getAreaNo(),row.getCode(),row.getName(),row.getCapacity(),
                row.getSortNo(),row.getStatus(),row.getTurnoverState(),row.getResourceNo(),row.getVersion());
    }
    private static String randomToken(String prefix) {
        byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);
        return prefix+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private static byte[] hash(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable",impossible); }
    }
    private static LocalDateTime local(Instant at) { return LocalDateTime.ofInstant(at,ZoneOffset.UTC); }
    private static LocalDateTime now() { return local(Instant.now().truncatedTo(ChronoUnit.MILLIS)); }
    private static void version(Long value) { if(value==null || value<0) bad(); }
    private static void id(String value) { text(value,32); }
    private static String text(String value,int max) {
        if(value==null || value.isBlank() || value.length()>max) bad();return value.trim();
    }
    private static void bad() { throw BizException.of(ErrorCode.BAD_REQUEST); }
}
