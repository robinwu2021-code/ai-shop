/** 只接受微信逐模板返回的明确结果；调用失败、缺字段不增加额度。 */
export async function reportStoredValueSubscription(
  templateId: string,
  result: Record<string, unknown>,
  report: (ids: string[], accepted: boolean) => Promise<void>,
): Promise<"accepted" | "rejected" | "unknown"> {
  const status = result[templateId];
  if (status === "accept") { await report([templateId], true); return "accepted"; }
  if (status === "reject" || status === "ban") { await report([templateId], false); return "rejected"; }
  return "unknown";
}
