/**
 * ARCHITECTURE §4.3 display rule (identical on every client):
 *   title = names[viewerLang] ?? (lang == viewerLang ? name : null) ?? names[defaultLanguage] ?? name
 */
export interface DisplayableItem {
  name: string;
  lang?: string | null;
  names?: Record<string, string> | null;
}

export function displayTitle(item: DisplayableItem, viewerLang: string, defaultLanguage: string): string {
  return (
    item.names?.[viewerLang] ??
    (item.lang === viewerLang ? item.name : undefined) ??
    item.names?.[defaultLanguage] ??
    item.name
  );
}
