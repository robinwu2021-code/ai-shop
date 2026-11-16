# V40 · 166发布及真实增量验收

2026-11-15，用户授权提交/推送当前分支并发布166，只验证联系人、独立实体和正式改绑，不做全量测试。

- 后端运行来源03b86f393c1c15079e4cac5cd218c93a60359181；App e57c598。两仓当前分支codex/sse-stability-20261114已推送；本记录仅文档后续提交，不改变运行代码。
- JDK21 Maven受影响reactor打包BUILD SUCCESS，跳过重复测试；复用前轮18项后端、25项App有效增量证据。本轮只补真实HTTP页面和UI错误提示回归，未执行整仓pre-push测试；推送使用显式hooksPath=/dev/null，依据用户“不做全量测试”及项目Java规范§8增量约定，不宣称全量闸门通过。
- 使用现役包作为基线008d216fbd9fc00da46bc446c7b44fbd68da9a8f6cff65f7c347066752efd7d3，按manifest仅合入本轮37项编译类/迁移资源，并逐项核对所有未改根资源及嵌套模块内容。不是以旧分支完整JAR覆盖其他现网修复。
- 候选/现役SHA256=f60efb059f90e0690e8948b8509432f8709473e4974930e67fd4f90787cbd59f。原API/OPS共享挂载文件，因此在同一部署锁下保留同inode更新并重启两者；原容器、env、挂载、Pay制品及支付配置不变，API/OPS健康UP，发布锁已释放。
- Flyway先validate（仅V674 pending），再apply（恰好1项）。V674 success=1/checksum=-1140970194，联系人列及mch_entity_creation_request已存在。通过MySQL9服务器的本机管理socket使用已有管理员权限，临时精确授予应用账号ALTER mch_store、CREATE mch_entity_creation_request；完成后收回，DDL表级权限回读为0。没有改历史迁移、没有repair成功记录、没有手工SQL写业务测试数据。

## 真实模拟器结果

账号robin@neargo.ai。复用有效个人会话，通过adb私有文件一次性注入并删除，未将凭据打包/写日志/Git。所有页面使用真实166 HTTP。

1. 联系人保存回显、CONTACT冲突保留输入通过，BASIC/HOURS版本不变；最终恢复原联系人，数据库语义摘要与测试前一致。
2. 独立新增M261115B1H3EMFBG8，PENDING_LICENSE、storeCount=0。GET原回执及相同requestNo重放通过，仅1条回执，门店集合未变，未开通收款。该明确标记TEST ONLY的虚构NearGo实体保留作为测试记录。
3. 正式改绑正向**尚未完成**。源ST261111JX1JMM2F3Q、目标M261114Y28YADQANZ；实际预检返回库存/财务历史无法核实、执行未开放。Flutter无提交按钮，数据库claim数仍0、原归属M26111119233ZE990未变。自动化通过的是阻断与禁止提交，不是成功变更。

联调另修复App支付历史结构提示重复：六个PAY_HISTORY_SCHEMA错误归并为一条财务历史提示，去重，并用经营实体措辞；阻断时隐藏员工撤权确认说明。先红后绿的定向回归通过，5文件分析无问题，模拟器仅重跑改绑页，未重复新增实体。

## 正向改绑剩余条件

- 独立库存已启用，但StoreRelinkLocalPolicy没有可验证/可预约的库存归属迁移围栏，INVENTORY_HISTORY_UNKNOWN必须保留。
- near-pay StoreClaimHistory报告ent_grant、ent_instance、ent_issue_batch、pts_user_ledger、pts_grant_position、pts_redeem_position六项历史表/必要列结构不满足。需要核对权威迁移与实际结构及历史归属，不把结构未知解释成无历史。
- shop.merchant.store-claim.enabled保持原关闭值；依赖就绪后再按正常业务配置发布开启，并验证Pay预约、原子改绑及原单恢复。本轮不关闭库存、不跳过支付历史检查、不伪造审批。

## 证据与回退

[发布回执](deployment-result.json)、[编译条目清单](manifest.json)、[真实模拟器](emulator-result.json)、[改绑提示复测](emulator-relink-recheck.json)、[数据库回读](database-verification.json)。App截图见ai-shop-bapp同分支docs/review/v40-store-entity-increments-20261115/live-166。

166私有发布目录/data0/app/ai-shop/releases/store-v40-20261115（0700），内有before.jar、candidate.jar、manifest、迁移私有日志及部署脚本。回退前必须核对现役哈希、获取同一部署锁，在API/OPS均停止后恢复原挂载文件inode内容并守候两者健康；不以本备份覆盖后续发布。V674为兼容性新增，回退代码不删除已产生的实体回执或联系人字段。
