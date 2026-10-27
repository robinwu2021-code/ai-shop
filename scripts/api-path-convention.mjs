/** Existing namespace contracts; do not rename deployed APIs to normalize their spelling. */
export function usesPluralCollections(path) {
  return /^\/ops(?:\/|$)/.test(path)
    // 支付运营端（ADR-044 OPS 面，与 /ops 同一套运营会话）：沿用 /ops 的复数约定
    || /^\/pay-ops\/v1(?:\/|$)/.test(path)
    || /^\/biz\/management\//.test(path)
    || /^\/biz\/stores\/(?:\{[^}]+\}|:[^/]+)(?:\/|$)/.test(path);
}

const uncountable = new Set(["goods", "address", "business", "status", "express", "news"]);
const isId = segment => /^\{[^}]+\}$/.test(segment) || /^:[^/]+$/.test(segment);
const looksPlural = segment => /^[a-z][a-z-]*s$/.test(segment)
  && !segment.endsWith("ss") && !segment.endsWith("alias");

/** Only collection segments with a child identifier have a singular/plural requirement. */
export function resourcePluralityOffenders(path) {
  const segments = path.split("/");
  const plural = usesPluralCollections(path);
  // Here {channel} selects a fulfillment setting; it is not a resource identifier.
  const settingDimension = (segment, next) => /^\/biz\/stores\//.test(path)
    && segment === "fulfillment" && /^(?:\{channel\}|:channel)$/.test(next);
  return segments.filter((segment, index) => segment && !isId(segment)
    && !uncountable.has(segment) && !settingDimension(segment, segments[index + 1] ?? "")
    && isId(segments[index + 1] ?? "")
    && looksPlural(segment) !== plural);
}
