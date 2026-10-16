/** 展示时组合称呼，原始姓名保持独立，避免保存时重复追加。 */
export function recipientName(
  name?: string | null,
  salutation?: string | null,
  labels: {mr: string; ms: string; fallback: string; prefix?: boolean} = { mr: "先生", ms: "女士", fallback: "先生/女士" },
): string {
  let clean = name?.trim() ?? "";
  if (!clean) return "";
  let existing: string | undefined;
  for (const suffix of ["先生/女士", "先生", "女士"]) {
    if (clean.endsWith(suffix)) {
      existing = suffix;
      clean = clean.slice(0, -suffix.length).trim();
      break;
    }
  }
  const suffix = salutation === "MR" ? labels.mr : salutation === "MS" ? labels.ms : existing ?? labels.fallback;
  return labels.prefix && (!existing || salutation === "MR" || salutation === "MS") ? `${suffix} ${clean}` : clean + suffix;
}
