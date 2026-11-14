# Task60A-A5c 报价命名空间血缘修复
Status: IMPLEMENTATION_APPROVED after A5b product44fa7f3ce3eed5e36ae2d80d9e7cc9dca345fc2d. 档位0，仅工具/测试/生成说明，不改业务契约。唯一worktree /Users/robin/.codex/worktrees/beauty-data/ai-shop-codex，codex/beauty-foundation，实施BASE根dispatch提供。

## 需求与源事实
A5b scoped spec/quality PASS，但独立审查确认 inherited Important：全局quote_no→mkt_quote把结算根与确认连到营销；Minor live说明仍写54张详情表。本片按根ruling单独关闭两项，再执行A6。不可把A5b67通过或603关系称全局已审计。看 review-task-60a-a5b.md，不复做A5b审计范围。

根实际readSchema得到quote_no恰4源表：mkt_quote、mkt_quote_revision、ord_checkout_quote、ord_checkout_confirmation（仅本次观察，不写永久全库计数）。
- 营销writer backend/shop-core/.../marketing/group/impl/GroupServiceImpl.java:377以BizKey.next(BizKey.QUOTE)创建MktQuote，:423 revision复制q.getQuoteNo()。营销revision真正指向mkt_quote。
- 结算writer backend/shop-core/.../trade/service/impl/CheckoutServiceImpl.java:268 number("CQ")，:285 operation.currentQuoteNo，:289新OrdCheckoutQuote.quoteNo，:430 confirmation复制该checkout quote。:376–390锁operation.currentQuoteNo并核对request/operation/caller/revision/hash。V539__checkout_source_foundation.sql:51,69 quote根(tenant_no,quote_no)唯一；确认有quote_no，operation有current_quote_no。报价根不是营销根。
- 两者独立命名空间；图的标识关联不代替上述完整冻结确认/权限校验，业务Java不改。

## Owned与边界
仅 scripts/lib/schema-lineage.mjs、packages/shared/tests/schema-lineage.test.ts、scripts/gen-erd.mjs（只删除live54硬编码 prose数字或从现有DOMAINS+tables派生，不做额外格式重构），及既有npmgen:erd实际变动的 tracked db-*.svg/数据库-ER图.md。不得改SQL/Java/parser/A5登记/known/package/其它生成器/DOMAINS/TOPICS；不手改产物、不新增视觉域。自有report/evidence允许，root文档根负责。禁止subagents/stage/commit/push/merge/native/deploy。

## 实施
沿用A5b现有接口，不新增框架或第二resolver。
- 移除KEY_OWNERS.quote_no全局owner，NAME_COLLISIONS中注明两报价体系独立；COLLISION_MEMBERS.quote_no精确四实际成员，新第五表必须未分类且无edge/FK。
- TABLE_KEY_OWNERS限定 mkt_quote_revision.quote_no→mkt_quote.quote_no、ord_checkout_confirmation.quote_no→ord_checkout_quote.quote_no，均tenant_no scope。两根不self/互连，不画任何跨族edge。
- ord_checkout_operation.current_quote_no→ord_checkout_quote.quote_no，tenant_no scope，按精确源表/列映射，不新增泛化global alias。这里只登记逻辑标识关系，不宣称能省略业务revision/hash/caller校验。
- 原resolver的源/target/scope校验、UK优先、strictcollision逻辑保持。generated引用/badge继续共用同一resolver。
- live“网页版…54张表”数字移除（保留完整句子说明字段、类型、索引、关联），或派生正确detail-card数量；无需给文案数字写镜像单测，实际生成内容核对即可。历史注释里的旧规模说明不扩改。

## 行为与验证
- [ ] 在当前实际readSchema上先新增真实语义测试：quote_no family关系集合精确为上述2条，operation别名实际为上述1条，toCol和tenant scope精确；两根/跨族均无edge，不依赖同一map自比。新第五table fixture的quote_no无resolve/FK且真实shared classifier拒绝。UK可保留而不能误称FK。
- [ ] oldactualresolver行为RED（应暴露ord_checkout_quote/confirmation→mkt_quote及漏checkout/alias；不要把缺导出/构建/环境错计RED）。
- [ ] 最小修复后 packages/shared cwd ../../node_modules/.bin/vitest run tests/entity-alignment.test.ts tests/schema-lineage.test.ts --cache false GREEN，保留A5原SHA2756d20054045dcc8cd6443130fa53d223ac4d84a602f402f4eda411180e06ea。既有用例不得减弱；实际总数记录不锁死。
- [ ] 最小消融仅quote_no解析回旧global（包括覆盖限定优先才能真实回退），新增实际family/cross负例RED；逐字恢复implementation SHA后一遍同combined GREEN。不重复其它未变A5b三消融。
- [ ] freeze/status后仅npm run gen:erd两遍生成/幂等；记录所有tracked已有输出及ignoredHTML SHA。generated Markdown不再有checkout quote_no→mkt_quote，含tenant-scoped真实三条描述；HTML实际解析也正确（UK优先），live54硬编码消失。不得为测试import eagergen。实际改动manifest解释每件，若出非原gen输出交根。
- [ ] report task-60a-a5c-report.md / own evidence：命令真实输出/返回码、RED-GREEN/消融精确restore、源码+生成物manifest/fullBASEdiff含新旧边、源事实/自审/边界。根独立审查及精确提交后才关闭A5b inheritedImportant。

不扩大成全旧registry审计。其它未确认旧关系、缺域/历史行/动态运行时/全P1-P4/部署仍未证明；本片无业务编译/数据库操作。连续执行已授权，不重复问用户。
