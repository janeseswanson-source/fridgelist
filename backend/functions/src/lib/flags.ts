import { getRemoteConfig } from "firebase-admin/remote-config";
import template from "../generated/remoteconfig.template.json" with { type: "json" };
import { log } from "./log.js";

/** CONTRACT §10 flags the backend acts on. */
export type FlagKey = "whatsapp_enabled" | "usuals_enabled" | "aisle_learning_enabled";

export interface Flags {
  enabled(key: FlagKey): Promise<boolean>;
}

const DEFAULTS = (template as { parameters: Record<string, { defaultValue: { value: string } }> }).parameters;

/** The contract default (`remoteconfig.template.json`); unknown flags are off. */
export function defaultFlag(key: FlagKey): boolean {
  return DEFAULTS[key]?.defaultValue.value === "true";
}

/** In the emulator: `FLAG_WHATSAPP_ENABLED=true|false` etc., else the contract default. */
export function emulatorFlag(key: FlagKey, env: Record<string, string | undefined>): boolean {
  const value = env[`FLAG_${key.toUpperCase()}`];
  return value === undefined ? defaultFlag(key) : value === "true";
}

/**
 * Server-side Remote Config with a one-minute cache. When Remote Config can't be read (the
 * emulator, no permission, an outage) the contract default applies: phase-2 features stay off.
 */
export class RemoteConfigFlags implements Flags {
  private cache: { at: number; values: Record<string, string> } | null = null;

  async enabled(key: FlagKey): Promise<boolean> {
    // Remote Config isn't emulated (the Admin SDK would hang on the metadata server): use the
    // default, or `FLAG_<KEY>=true` in functions/.env.local to try a phase-2 feature locally.
    if (process.env.FUNCTIONS_EMULATOR === "true") return emulatorFlag(key, process.env);
    try {
      if (!this.cache || Date.now() - this.cache.at > 60_000) {
        const remote = await getRemoteConfig().getTemplate();
        const values: Record<string, string> = {};
        for (const [name, parameter] of Object.entries(remote.parameters)) {
          const value = parameter.defaultValue;
          if (value && "value" in value) values[name] = value.value;
        }
        this.cache = { at: Date.now(), values };
      }
      const value = this.cache.values[key];
      return value === undefined ? defaultFlag(key) : value === "true";
    } catch (error) {
      log.warn("flags_unavailable", { key, code: (error as { code?: string }).code ?? "unknown" });
      return defaultFlag(key);
    }
  }
}

export const remoteFlags = new RemoteConfigFlags();

/** Tests and the emulator: fixed values. */
export class StaticFlags implements Flags {
  constructor(private readonly values: Partial<Record<FlagKey, boolean>>) {}
  enabled(key: FlagKey): Promise<boolean> {
    return Promise.resolve(this.values[key] ?? defaultFlag(key));
  }
}
