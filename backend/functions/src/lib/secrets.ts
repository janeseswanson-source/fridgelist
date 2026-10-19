/**
 * Every `defineSecret` must exist before a deploy, so an unused one holds the placeholder `none`
 * (RUNBOOK §5). Treat that, and an empty value, as "not configured".
 */
export const SECRET_PLACEHOLDER = "none";

export function configured(value: string): string {
  const trimmed = value.trim();
  return trimmed === "" || trimmed.toLowerCase() === SECRET_PLACEHOLDER ? "" : trimmed;
}
