// Curated verification evidence is separate from static source discovery and target acceptance.
export function validateFoodDeliveryStatus(value) {
 if(!value?.evidence?.length)throw new Error('Missing delivery evidence');
 if(value.businessAcceptance!=='PENDING_TERMINAL_AND_REAL_OWNER_E2E')throw new Error('Unsupported business acceptance promotion');
 if(!/^[a-f0-9]{40}$/.test(value.production?.sourceCommit||''))throw new Error('Missing exact production commit');
 if(value.production.shape!=='food-embedded')throw new Error('Unsupported recorded release shape');
}
export function renderFoodDeliveryStatus(value) {
 validateFoodDeliveryStatus(value);
 return `## 当前交付证据\n\n记录日期：${value.recordedDate}。生产已核验提交：\`${value.production.sourceCommit}\`（\`${value.production.sourceCommit.slice(0,9)}\`），餐饮集成部署；迁移基线 FOOD V${value.schema.food} / CORE V${value.schema.core} / INVENTORY V${value.schema.inventory}来自既有迁移核验记录，本节不代表持续在线监测。\n\n状态：代码已交付；专项验证见验收矩阵；生产部署已核验；终端、真实设备和跨owner完整营业验收仍待完成。报表生产未启用，本批未发布支付资金能力。后文V15、H5/PICKUP及CODED_PENDING_VERIFICATION保留为历史切片/完整目标验收状态，不表示当前所有后端尚未编译测试，也不表示所有完整目标均已交付。\n\n${value.localChanges ? `本地新增修复尚未提交或部署；待发布迁移 CORE V${value.localChanges.coreMigration} / INVENTORY V${value.localChanges.inventoryMigration}。旧规则目录失配须显式新发布，不能静默放宽。见[本轮原生记录](${value.localChanges.report})。\n\n` : ''}证据：${value.evidence.map(e=>`[${e.label}](${e.path})`).join(' · ')}。\n\n`;
}
