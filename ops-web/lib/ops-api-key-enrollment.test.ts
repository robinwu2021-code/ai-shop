import { describe, expect, it } from "vitest";
import { parseApiKeyEnrollment } from "./ops-api-key-enrollment";

const setup = {
  origin: "https://ops.example",
  staffNo: "STAFF-01",
  keyNo: "6b5b00e4-e10a-4dfd-8a78-6f9ce0430489",
  secretHash: "b".repeat(64),
};

describe("operator API key enrollment", () => {
  it("accepts only the local setup fields for the current account and API origin", () => {
    expect(parseApiKeyEnrollment(JSON.stringify(setup), setup.origin, setup.staffNo))
      .toEqual({ keyNo: setup.keyNo, secretHash: setup.secretHash });
    expect(parseApiKeyEnrollment(JSON.stringify(setup), "https://other.example", setup.staffNo))
      .toEqual("mismatch");
    expect(parseApiKeyEnrollment(JSON.stringify(setup), setup.origin, "STAFF-02"))
      .toEqual("mismatch");
  });

  it("rejects malformed payloads and raw keys", () => {
    for (const value of ["oak_" + "a".repeat(43), "null", "{}", JSON.stringify({ ...setup, secretHash: "invalid" })]) {
      expect(parseApiKeyEnrollment(value, setup.origin, setup.staffNo)).toEqual("invalid");
    }
    expect(parseApiKeyEnrollment(JSON.stringify({ ...setup, apiKey: "oak_" + "a".repeat(43) }),
      setup.origin, setup.staffNo)).toEqual("invalid");
  });
});
