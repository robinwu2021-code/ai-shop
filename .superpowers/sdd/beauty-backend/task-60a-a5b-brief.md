# Task60A-A5b 血缘语义与ER产物一致性

Status: IMPLEMENTATION_APPROVED after accepted A5 product 4a0fa71dda6b7fa522e486da1811a54ccb83b450. 档位0，修复工具及守卫，不改业务契约。唯一worktree /Users/robin/.codex/worktrees/beauty-data/ai-shop-codex，codex/beauty-foundation。IMPLEMENTATION_BASE根提供。

## 设计依据和选择

先读本brief；源审计 /tmp/task60a-a5b-semantic-audit.md 和精确实际键成员 /tmp/task60a-a5b-key-memberships.json 是取证附件，不能代替本需求。根选择抽取一份纯模块，而非继续维护测试/生成器两份独立map；后者已发生重复键和角色列丢失，单纯复制18项会新造跨域假边。代价为一个纯模块和测试/生成器适配；不引入SQL join引擎/框架/依赖/全库归属重审。条件性关系写明限制，不假装是无条件外键。

Owned产品只有：
1. 新 scripts/lib/schema-lineage.mjs（纯登记/解析/分类/字段标记，无文件读写/生成副作用）。
2. scripts/gen-erd.mjs（消费同一模块，不再自存冲突/owner map和关系循环；现CLI入口/图布局/domain/topic不扩展）。
3. packages/shared/tests/schema-lineage.test.ts（实际守卫及独立语义行为）。
4. 仅由既有 npm run gen:erd 生成且实际发生变化的 tracked docs/technical/diagrams/db-*.svg 与 docs/technical/reference/数据库-ER图.md。所有变化逐项manifest解释；不手改生成物。已忽略html可生成、检查，不force-add。不是预先限定A4六文件；只允许此生成器原本已有输出集合，不加新域/图文件。
允许本片report/evidence；root文档根负责。禁止改entity-alignment、DDL parser、SQL/schema-test/Python/Java/known/POM/package/lock/其它生成器/DOMAINS/TOPICS，禁止native/deploy/stage/commit/push/merge/子agent。

## 纯接口和关系形状

消费实际readSchema(ROOT)的Map及其cols/uniques，不能复制DDL识别器。导出：
- KEY_OWNERS：全局安全逻辑归属 Record<key,{table:string,col?:string,scope?:{from:string,to:string}[]}>；保留原有效owner和角色target col。
- NAME_COLLISIONS：冲突理由；原测试现有效理由迁入同一份，移除duplicate literal仅保留原最终有效语义。新增下述真实区别。
- TABLE_KEY_OWNERS：按具体源表/列限定的同形owner。优先于全局冲突；不按prefix猜测。
- COLLISION_MEMBERS：对本片审计的歧义键精确列出实际源表（附件）；新出现的未知表不能因全局collision而静默豁免。旧未触及冲突保留现有分类，不扩大成全库改造。
- resolveReference(tables,fromTable,columnName) -> {from,to,col,toCol,scope:[]}|null；验证源/目标列和scope双方列存在，禁止self，未知/歧义/条件型无无条件edge，精确限定优先。
- relations(tables) -> 上述reference[]；生产生成器必须实际使用；角色列保留toCol，不从table推列名。
- unclassifiedBusinessKeys(tables) -> string[]；真实跨表_no守卫与未知成员负例同用，保留tenant_no排除及现有业务键检查语义。
- columnBadge(tables,table,columnName) -> string；输出既有UK/FK HTML样式或空串，FK必须来自同一resolveReference真实结果，丢目标或歧义不显示。保留现有UK优先级。
- describeRelation(reference) -> string，明确源列、目标列及scope配对。生成Markdown/HTML实际引用描述和新增限定关系说明使用此结果（HTML需转义），不能暗示带作用域关系可裸号join。

纯模块可从ddl.mjs导入已有AUDIT而不执行readSchema。测试不再import有写副作用的gen-erd；不要把“同一常量等于自身”当新防线。原LINEAGE端点校验、Python真实列对照、owner目标存在、stale冲突等守卫保留；原parity检查替换为独立预期的实际关系/target列/scope和生产共用函数行为。其余旧global语义不在本片全面重审。

## 必须实现的语义

下述tenant scope均为tenant_no=tenant_no。普通有效新owner：instance_no→ent_instance，usage_no→ent_usage，recharge_no→sv_recharge，lot_no→sv_lot，exception_no→pay_ent_exception，recon_no→pay_ent_recon_run，stage_no→ent_migration_stage，alias_no→ent_migration_alias，checkout_no→ord_legacy_coupon_checkout，import_no→ent_migration_import，return_no→pmt_migration_return，以上带tenant scope。角色：target_template_no→ent_template.template_no；template_version_no→ent_template_version.version_no；activity_version_no→pmt_activity_version.version_no，带tenant scope。buyer_no→usr_account.user_no（四实际源表），confirmation_no→ord_checkout_confirmation.confirmation_no，带tenant scope。

精确限定/禁止：
- voucher_no全局冲突；仅四条关系 pts_ledger_entry→pts_voucher、pts_pool_flow→pts_voucher、sv_entry→sv_voucher、sv_posting_line→sv_voucher（同列，至少sv两条tenant scope；pts既有实际scope有则保留）。两根不互连、不self、不跨族；第七未知源表不出edge且分类报未登记。
- plan_no全局冲突；仅pmt_migration_decision、ent_migration_import→ent_migration_plan.plan_no，tenant scope。pts_topup_plan是独立(计划,期)命名空间，不画到迁移。
- source_order_no全局协议歧义；仅ent_legacy_coupon_operation→ord_order.order_no，tenant scope。ent_usage可存SERVICE_USE或子单，禁止无条件画到ord_order；在冲突理由/生成限定说明保留其条件真实链路。
- grant_no全局条件归属；ent_sale_reservation和pmt_pay_coupon_projection→ent_grant.grant_no，tenant scope。ent_instance含MIGRATION_BASELINE，无条件edge禁止；解释普通发放路径与迁移baseline区别。
- tier_no全局版本作用域；pmt_activity_reservation→pmt_recharge_tier.tier_no，scope tenant_no和version_no。根表不self。
- catalog_no全局主体作用域；prd_store_catalog_version→prd_store_catalog.catalog_no，scope tenant_no和entity_no。
- actor_account_no全局歧义；仅ord_checkout_payment_request与stl_payment_operation→mch_account.mch_account_no，tenant scope。支付USR路径也保存解析后的商家账号；mch_staff_log历史保存login userNo，不能无条件连该商家账号。
- receipt_no两个独立收据，source_event_no来源协议键，entry_no两个独立账本，line_no两个局部行序，均按审计成员精确登记冲突、不画跨域边。
- execution_no多态（普通退款根或PRF-售后积分路径），不画无条件ord_refund_obligation→ord_refund_execution；解释两条真实条件路径。
上述被省略条件边必须有清晰生成说明；不要把所有歧义简单忽略后丢失可证明的限定正向关系。

## 测试和验证

- [ ] 基线实际lineage单文件运行，记录现有两个真实失败，环境错误不算RED。测试命令明确packages/shared cwd ../../node_modules/.bin/vitest run tests/schema-lineage.test.ts --cache false；已有4.1.11，不安装。
- [ ] 行为RED先测真实现有relation/badge错误：可先将旧生成器的已有纯关系/map提取为同形模块以保持旧行为，禁止以缺模块/导出/编译错误冒充语义RED。禁止为测旧行为import执行eager生成器；保留仅搬运旧逻辑的test-first快照，再新增语义断言。必要的最小接口提取是RED前setup，不是修复完成。
- [ ] 独立预期断言真实readSchema上的精确四voucher edges、负cross/root/self、未知第七源表分类失败；真实buyer目标user_no、confirmation、迁移return、普通11与3角色列、限定plan/actor/catalog/tier的scope完整。按源表+列验证所有负例：receipt/sourceevent/entry/line/stafflog/refund/pts_plan/ent_usage/ent_instance无无条件假边。
- [ ] 小Map fixture缺target column或scope column，relations与实际columnBadge都不能出FK；fixture列已存在时正确显示，UK优先保留。原真实覆盖/owner存在/冲突stale/Python/LINEAGE检查不减弱，不写只比map副本/源码regex自证。
- [ ] 实现后定向lineage GREEN；combined entity-alignment+schema-lineage GREEN（A5登记必须保持原SHA）。只报真实计数，不把观察363表写永久固定常量。
- [ ] 最小消融voucher限定改回全局pts→真实crossfamily负例RED；再对plan/source_order任选一项改回错误global→真实负例RED；至少一次去scope/toCol防线使限定正例RED。每次只动实现，精确恢复，同SHA可以一次最终combined GREEN证明恢复，避免重复同代码套件。记录mutationdiff/真实counts/全部源码hash。
- [ ] 最终source冻结、status快照后，npm run gen:erd 一次更新实际产物，再一次幂等校验；第二次是生成一致性检查不是重复业务测试。记录CLI输出、actualchanged manifest及完整source+generated diff。核查ignored HTML字段标记/target列/scope描述，不提交html，不扫别的生成器。若额外tracked变化不属于此生成器原输出，停止并交根证据。
- [ ] 报告 task-60a-a5b-report.md / own evidence：源审计映射、实现偏差、所有commands/RED/GREEN/消融恢复/SHA、准确产品manifest含新文件/生成物、冻源不变/无副作用测试证明、自审。独立spec+quality gate后根精确提交。

保留限制：现DOMAINS没sv/ent/pay等详情域，本片不扩；关系集合及限定说明保护语义，不宣称已补所有域图。更广泛旧owner审计/缺失域/解析器uniqueness数据表示/历史行完整性后续注明，不能假绿或扩业务范围。全P1-P4与部署尚未完成，自动连续执行无需重复询问。
