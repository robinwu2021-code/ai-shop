/** 静态审查的可判定门槛；业务语义和视觉验收仍由处置台账承担。 */
export function uiPolicyViolations(report, exceptions = { literals: [], layouts: [] }) {
  const errors = [];
  for (const p of report.parity ?? []) {
    for (const field of ['missing', 'extra', 'placeholderMismatch']) {
      for (const key of p[field] ?? []) errors.push(`${p.lang}: ${field} ${key}`);
    }
  }
  for (const r of report.missingStaticKeys ?? []) errors.push(`${r.file}:${r.line}: missing ${r.key}`);
  for (const r of report.literalCandidates ?? []) {
    if (!exceptions.literals.some(e => e.file === r.file && e.kind === r.kind && e.text === r.text)) {
      errors.push(`${r.file}:${r.line}: untranslated ${r.text}`);
    }
  }
  for (const r of report.unresolvedClasses ?? []) errors.push(`${r.file}: undefined .${r.className}`);
  for (const r of report.unstyledControls ?? []) errors.push(`${r.file}:${r.line}: unstyled ${r.tag}`);
  for (const r of report.layoutContractViolations ?? []) errors.push(`${r.file}:${r.line}: ${r.rule} ${r.detail}`);
  for (const r of report.layoutCandidates ?? []) {
    if (!r.file.startsWith('b-app/')) continue; // 共享组件负责浮层与 fixed 等基础能力。
    if (!exceptions.layouts.some(e => ['rule', 'file', 'selector', 'property', 'value'].every(k => e[k] === r[k]))) {
      errors.push(`${r.file}:${r.line}: ${r.rule} ${r.selector} { ${r.property}: ${r.value} }`);
    }
  }
  return errors;
}
