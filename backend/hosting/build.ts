/**
 * Builds hosting/public (ARCHITECTURE §3.7):
 * - /.well-known/apple-app-site-association and /.well-known/assetlinks.json from the CLAUDE.md
 *   constants and hosting/signing.json;
 * - /config.js (link domain, store URLs, App Check site key) for the pages;
 * - /app.js compiled from hosting/src/app.ts (plain TypeScript transpile: no bundler).
 * Generated files are git-ignored. Warns, but still builds, while any value is a placeholder.
 */
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import ts from "typescript";

const here = import.meta.dirname;
const root = resolve(here, "../..");
const pub = resolve(here, "public");

function constant(name: string): string {
  const claude = readFileSync(resolve(root, "CLAUDE.md"), "utf8");
  const match = new RegExp(`\\|\\s*\`${name}\`\\s*\\|\\s*([^|]+?)\\s*\\|`).exec(claude);
  if (!match?.[1]) throw new Error(`CLAUDE.md has no ${name}`);
  return match[1];
}

interface Signing {
  androidSha256: string[];
  webAppCheckSiteKey: string;
  playStoreUrl: string;
  appStoreUrl: string;
}
const signing = JSON.parse(readFileSync(resolve(here, "signing.json"), "utf8")) as Signing;

const values = {
  appleTeamId: constant("APPLE_TEAM_ID"),
  iosBundleId: constant("IOS_BUNDLE_ID"),
  androidApplicationId: constant("ANDROID_APPLICATION_ID"),
  linkDomain: constant("LINK_DOMAIN"),
  urlScheme: constant("URL_SCHEME"),
};

const placeholders = [
  ...Object.entries(values).filter(([, v]) => /TODO|example/i.test(v)).map(([k]) => k),
  ...(signing.androidSha256.some((f) => f.startsWith("TODO")) ? ["androidSha256"] : []),
  ...(["webAppCheckSiteKey", "playStoreUrl", "appStoreUrl"] as const).filter((k) => signing[k].startsWith("TODO")),
];

mkdirSync(resolve(pub, ".well-known"), { recursive: true });

// CONTRACT §6 paths; the file has no extension and is served as application/json (firebase.json).
writeFileSync(
  resolve(pub, ".well-known/apple-app-site-association"),
  JSON.stringify(
    {
      applinks: {
        details: [
          {
            appIDs: [`${values.appleTeamId}.${values.iosBundleId}`],
            components: [{ "/": "/k/*" }, { "/": "/join/*" }, { "/": "/l/*" }],
          },
        ],
      },
    },
    null,
    2,
  ),
);

// Both the release app and the dev flavor (".dev") may open links.
writeFileSync(
  resolve(pub, ".well-known/assetlinks.json"),
  JSON.stringify(
    [values.androidApplicationId, `${values.androidApplicationId}.dev`].map((pkg) => ({
      relation: ["delegate_permission/common.handle_all_urls"],
      target: { namespace: "android_app", package_name: pkg, sha256_cert_fingerprints: signing.androidSha256 },
    })),
    null,
    2,
  ),
);

const config = {
  linkDomain: values.linkDomain,
  playStoreUrl: signing.playStoreUrl.startsWith("TODO") ? "" : signing.playStoreUrl,
  appStoreUrl: signing.appStoreUrl.startsWith("TODO") ? "" : signing.appStoreUrl,
  appCheckSiteKey: signing.webAppCheckSiteKey.startsWith("TODO") ? "" : signing.webAppCheckSiteKey,
};
writeFileSync(resolve(pub, "config.js"), `window.FRIDGELIST = ${JSON.stringify(config)};\n`);

const source = readFileSync(resolve(here, "src/app.ts"), "utf8");
const out = ts.transpileModule(source, {
  compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.ESNext, removeComments: true },
});
writeFileSync(resolve(pub, "app.js"), out.outputText);

process.stdout.write(
  `hosting/public built.${placeholders.length > 0 ? ` Placeholders still in use: ${placeholders.join(", ")}.` : ""}\n`,
);
