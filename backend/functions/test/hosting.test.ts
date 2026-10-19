import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { PUBLIC } from "../src/callables/shareLinks.js";

/**
 * The Hosting emulator (firebase-tools 15) ignores `headers`, so the hosting config is checked
 * here instead; the live headers are checked with `curl -I` at the first deploy (RUNBOOK).
 */
interface Header {
  key: string;
  value: string;
}
interface Hosting {
  public: string;
  rewrites: { source: string; destination?: string; function?: { functionId: string; region: string } }[];
  headers: { source: string; headers: Header[] }[];
}
const hosting = (
  JSON.parse(readFileSync(resolve(import.meta.dirname, "../../firebase.json"), "utf8")) as {
    hosting: Hosting;
  }
).hosting;
const headersFor = (source: string) => hosting.headers.find((h) => h.source === source)?.headers ?? [];

describe("hosting config (ARCHITECTURE §3.7)", () => {
  it("the association file is served as application/json (it has no extension)", () => {
    expect(headersFor("/.well-known/apple-app-site-association")).toContainEqual({
      key: "Content-Type",
      value: "application/json",
    });
  });

  it("every page gets a strict CSP, nosniff and no referrer", () => {
    const all = headersFor("**");
    const csp = all.find((h) => h.key === "Content-Security-Policy")?.value ?? "";
    expect(csp).toContain("default-src 'self'");
    expect(csp).toContain("frame-ancestors 'none'");
    expect(csp).not.toMatch(/script-src[^;]*'unsafe-inline'/);
    expect(all).toContainEqual({ key: "X-Content-Type-Options", value: "nosniff" });
    expect(all).toContainEqual({ key: "Referrer-Policy", value: "no-referrer" });
  });

  it("the public callables are reached same-origin in the functions region; links route to the page", () => {
    for (const name of ["resolveShareLink", "guestAddItem"]) {
      expect(hosting.rewrites).toContainEqual({
        source: `/api/${name}`,
        function: { functionId: name, region: "me-central2" },
      });
    }
    for (const path of ["/k/**", "/join/**", "/l/**"]) {
      expect(hosting.rewrites).toContainEqual({ source: path, destination: "/index.html" });
    }
  });

  it("App Check is enforced outside the emulator", () => {
    // Vitest doesn't set FUNCTIONS_EMULATOR, so this is what a deployed function sees.
    expect(PUBLIC.enforceAppCheck).toBe(true);
  });
});
