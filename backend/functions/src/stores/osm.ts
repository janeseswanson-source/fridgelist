import { geohashForLocation } from "geofire-common";
import { chainFor, type Country } from "../lib/chains.js";

/** CONTRACT §3.13 store document (without `updatedAt`, which the writer adds). */
export interface StoreDoc {
  name: string;
  brand?: string;
  chainId: string;
  lat: number;
  lng: number;
  geohash: string;
  country: Country;
  source: "osm" | "user";
  verified: boolean;
  addedByHousehold?: string;
}

export interface StoreRecord {
  storeId: string;
  doc: StoreDoc;
}

/** ARCHITECTURE §5.1: the shop types we import. */
export const SHOP_TYPES = ["supermarket", "convenience", "greengrocer", "butcher", "bakery"] as const;

/** Rough country boxes for the three markets; the UAE is checked before Saudi Arabia (they touch). */
const BOXES: readonly { country: Country; s: number; w: number; n: number; e: number }[] = [
  { country: "AE", s: 22.6, w: 51.5, n: 26.1, e: 56.4 },
  { country: "TR", s: 35.8, w: 25.6, n: 42.2, e: 44.9 },
  { country: "SA", s: 16.3, w: 34.5, n: 32.2, e: 55.7 },
];

export function countryOf(lat: number, lng: number): Country | null {
  return BOXES.find((b) => lat >= b.s && lat <= b.n && lng >= b.w && lng <= b.e)?.country ?? null;
}

/** Overpass QL for one country (UAE and Saudi Arabia; Turkey is too large for public Overpass). */
export function overpassQuery(country: "AE" | "SA"): string {
  const shops = SHOP_TYPES.join("|");
  return [
    "[out:json][timeout:600];",
    `area["ISO3166-1"="${country}"][admin_level=2]->.a;`,
    `(node["shop"~"^(${shops})$"](area.a);way["shop"~"^(${shops})$"](area.a););`,
    "out center tags;",
  ].join("\n");
}

type Tags = Record<string, string | undefined>;

function nameOf(tags: Tags): string | null {
  const name = tags.name ?? tags["name:en"] ?? tags["name:ar"] ?? tags["name:tr"] ?? tags.brand;
  const trimmed = name?.trim();
  return trimmed ? trimmed.slice(0, 80) : null;
}

function record(
  type: "node" | "way",
  id: number | string,
  lat: number,
  lng: number,
  tags: Tags,
  country: Country,
): StoreRecord | null {
  const name = nameOf(tags);
  if (name === null || !Number.isFinite(lat) || !Number.isFinite(lng)) return null;
  const brand = tags.brand?.trim();
  return {
    storeId: `osm_${type}_${String(id)}`,
    doc: {
      name,
      ...(brand ? { brand } : {}),
      chainId: chainFor([brand, tags.name, tags["name:en"], tags["name:ar"]], country),
      lat: round(lat),
      lng: round(lng),
      geohash: geohashForLocation([lat, lng], 9),
      country,
      source: "osm",
      verified: true,
    },
  };
}

function round(value: number): number {
  return Math.round(value * 1e7) / 1e7;
}

export interface OverpassElement {
  type: string;
  id: number;
  lat?: number;
  lon?: number;
  center?: { lat: number; lon: number };
  tags?: Tags;
}

/** One Overpass element → a store, or null (unnamed, no position, or not a node/way). */
export function fromOverpass(element: OverpassElement, country: Country): StoreRecord | null {
  if (element.type !== "node" && element.type !== "way") return null;
  const lat = element.lat ?? element.center?.lat;
  const lng = element.lon ?? element.center?.lon;
  if (lat === undefined || lng === undefined) return null;
  return record(element.type, element.id, lat, lng, element.tags ?? {}, country);
}

export interface GeoJsonFeature {
  type: string;
  geometry: { type: string; coordinates: unknown } | null;
  properties: Record<string, unknown> | null;
}

/** Mean of every coordinate pair in a GeoJSON geometry (good enough for a shop's footprint). */
function centre(coordinates: unknown): [number, number] | null {
  const points: [number, number][] = [];
  const walk = (c: unknown) => {
    if (Array.isArray(c) && typeof c[0] === "number" && typeof c[1] === "number") points.push([c[0], c[1]]);
    else if (Array.isArray(c)) c.forEach(walk);
  };
  walk(coordinates);
  if (points.length === 0) return null;
  const lng = points.reduce((s, p) => s + p[0], 0) / points.length;
  const lat = points.reduce((s, p) => s + p[1], 0) / points.length;
  return [lat, lng];
}

/**
 * One feature from `osmium export --attributes type,id` (Turkey path, ARCHITECTURE §5.1):
 * `@type`/`@id` in properties, tags alongside.
 */
export function fromGeoJson(feature: GeoJsonFeature, country: Country): StoreRecord | null {
  const props = feature.properties ?? {};
  const type = props["@type"];
  const id = props["@id"];
  const shop = props.shop;
  if ((type !== "node" && type !== "way") || (typeof id !== "number" && typeof id !== "string")) return null;
  if (typeof shop !== "string" || !(SHOP_TYPES as readonly string[]).includes(shop)) return null;
  const point = centre(feature.geometry?.coordinates);
  if (!point) return null;
  const tags = Object.fromEntries(
    Object.entries(props).filter((e): e is [string, string] => typeof e[1] === "string"),
  );
  return record(type, id, point[0], point[1], tags, country);
}
