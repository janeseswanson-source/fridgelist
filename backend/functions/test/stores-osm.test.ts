import { describe, expect, it } from "vitest";
import { chainFor, CHAIN_IDS } from "../src/lib/chains.js";
import { countryOf, fromGeoJson, fromOverpass, overpassQuery } from "../src/stores/osm.js";

describe("chain mapping (ARCHITECTURE §5.1)", () => {
  it.each([
    ["Migros", "TR", "migros"],
    ["MİGROS", "TR", "migros"],
    ["MIGROS", "TR", "migros"],
    ["Migros Jet", "TR", "migros"],
    ["Macrocenter", "TR", "macrocenter"],
    ["CarrefourSA", "TR", "carrefoursa"],
    ["Carrefour SA Gurme", "TR", "carrefoursa"],
    ["Carrefour", "AE", "carrefour"],
    ["Carrefour Market", "SA", "carrefour"],
    ["A101", "TR", "a101"],
    ["BİM", "TR", "bim"],
    ["Bim", "TR", "bim"],
    ["Kibim Gıda", "TR", "other"],
    ["ŞOK", "TR", "sok"],
    ["Yeni Şok", "TR", "sok"],
    ["Şokella Pastanesi", "TR", "other"],
    ["File Market", "TR", "file"],
    ["Profile Kırtasiye", "TR", "other"],
    ["Hyper Panda", "SA", "panda"],
    ["بنده", "SA", "panda"],
    ["Pandas Toys", "SA", "other"],
    ["أسواق العثيم", "SA", "othaim"],
    ["Tamimi Markets", "SA", "tamimi"],
    ["LuLu Hypermarket", "AE", "lulu"],
    ["لولو هايبر ماركت", "AE", "lulu"],
    ["Union Coop", "AE", "union_coop"],
    ["UNION CO-OP", "AE", "union_coop"],
    ["VIVA", "AE", "viva"],
    ["Viva", "TR", "other"],
    ["Waitrose", "AE", "waitrose"],
    ["Al Maya Supermarket", "AE", "almaya"],
    ["Bakkal Ahmet", "TR", "other"],
  ] as const)("%s in %s → %s", (name, country, chain) => {
    expect(chainFor([name], country)).toBe(chain);
  });

  it("brand wins over name; every chain ID is known", () => {
    expect(chainFor(["Migros", "Bakkal"], "TR")).toBe("migros");
    expect(chainFor([undefined, "BİM"], "TR")).toBe("bim");
    expect(CHAIN_IDS).toContain("other");
    expect(new Set(CHAIN_IDS).size).toBe(CHAIN_IDS.length);
  });
});

describe("OSM parsing", () => {
  it("countries by box; UAE before Saudi Arabia", () => {
    expect(countryOf(40.99, 29.03)).toBe("TR");
    expect(countryOf(24.69, 46.68)).toBe("SA");
    expect(countryOf(25.08, 55.14)).toBe("AE");
    expect(countryOf(48.85, 2.35)).toBeNull();
  });

  it("Overpass query: country area, the five shop types, nodes and ways with centres", () => {
    const q = overpassQuery("AE");
    expect(q).toContain('area["ISO3166-1"="AE"][admin_level=2]');
    expect(q).toContain("supermarket|convenience|greengrocer|butcher|bakery");
    expect(q).toContain("out center tags;");
  });

  it("nodes and ways become osm_ stores with geohash, brand and chain", () => {
    const node = fromOverpass(
      { type: "node", id: 42, lat: 25.0805, lon: 55.1403, tags: { name: "Spinneys", shop: "supermarket" } },
      "AE",
    );
    expect(node).toMatchObject({
      storeId: "osm_node_42",
      doc: { name: "Spinneys", chainId: "spinneys", country: "AE", source: "osm", verified: true },
    });
    expect(node?.doc.geohash).toHaveLength(9);
    const way = fromOverpass(
      { type: "way", id: 7, center: { lat: 24.7, lon: 46.68 }, tags: { brand: "Panda", "name:ar": "بنده" } },
      "SA",
    );
    expect(way).toMatchObject({
      storeId: "osm_way_7",
      doc: { name: "بنده", brand: "Panda", chainId: "panda" },
    });
  });

  it("skips unnamed shops, missing positions and relations", () => {
    expect(
      fromOverpass({ type: "node", id: 1, lat: 25, lon: 55, tags: { shop: "convenience" } }, "AE"),
    ).toBeNull();
    expect(fromOverpass({ type: "way", id: 2, tags: { name: "X" } }, "AE")).toBeNull();
    expect(fromOverpass({ type: "relation", id: 3, lat: 25, lon: 55, tags: { name: "X" } }, "AE")).toBeNull();
  });

  it("osmium GeoJSON (Turkey): points and polygon centres; non-shops skipped", () => {
    const point = fromGeoJson(
      {
        type: "Feature",
        geometry: { type: "Point", coordinates: [29.03, 40.99] },
        properties: { "@type": "node", "@id": 9, shop: "supermarket", name: "Migros", brand: "Migros" },
      },
      "TR",
    );
    expect(point).toMatchObject({
      storeId: "osm_node_9",
      doc: { lat: 40.99, lng: 29.03, chainId: "migros" },
    });
    const polygon = fromGeoJson(
      {
        type: "Feature",
        geometry: {
          type: "Polygon",
          coordinates: [
            [
              [29, 41],
              [29.002, 41],
              [29.002, 41.002],
              [29, 41.002],
            ],
          ],
        },
        properties: { "@type": "way", "@id": 5, shop: "bakery", name: "Fırın" },
      },
      "TR",
    );
    expect(polygon?.doc.lat).toBeCloseTo(41.001, 6);
    expect(polygon?.doc.lng).toBeCloseTo(29.001, 6);
    expect(
      fromGeoJson(
        {
          type: "Feature",
          geometry: { type: "Point", coordinates: [29, 41] },
          properties: { "@type": "node", "@id": 1, shop: "florist", name: "Çiçek" },
        },
        "TR",
      ),
    ).toBeNull();
  });
});
