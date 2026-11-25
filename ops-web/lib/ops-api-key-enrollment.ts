export function parseApiKeyEnrollment(raw: string, origin: string, staffNo: string) {
  try {
    const value: unknown = JSON.parse(raw);
    if (typeof value !== "object" || value === null || Object.keys(value).length !== 4
      || !("origin" in value) || typeof value.origin !== "string"
      || !("staffNo" in value) || typeof value.staffNo !== "string"
      || !("keyNo" in value) || typeof value.keyNo !== "string"
      || !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value.keyNo)
      || !("secretHash" in value) || typeof value.secretHash !== "string"
      || !/^[a-f0-9]{64}$/.test(value.secretHash)) return "invalid";
    if (value.origin !== origin || value.staffNo !== staffNo) return "mismatch";
    return { keyNo: value.keyNo, secretHash: value.secretHash };
  } catch {
    return "invalid";
  }
}
