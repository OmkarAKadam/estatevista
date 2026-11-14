/**
 * EstateVista — property data.
 *
 * A plain global array by design: no fetch(), no JSON round-trip, so the site
 * works from a static server and from file:// without extra tooling.
 *
 * Consumed from Stage 2 onward by properties.js (listing grid) and
 * property-details.js (single listing view). Nothing renders this in Stage 1.
 *
 * All records are FICTIONAL demonstration content. Prices are in Indian Rupees
 * (INR), stored as whole rupees with no formatting applied.
 *
 * Schema (per record):
 *   id          string  stable slug used in property-details.html?id=
 *   title       string  display name
 *   type        string  "apartment" | "villa" | "penthouse" | "rowhouse"
 *   status      string  "available" | "reserved" | "sold"
 *   price       number  whole rupees, e.g. 48500000
 *   city        string
 *   locality    string
 *   bedrooms    number
 *   bathrooms   number
 *   areaSqft    number  built-up area
 *   featured    boolean shown in the home page highlight rail
 *   images      string[] relative paths, resolved from the site root
 */
window.EV_PROPERTIES = [
  {
    id: "sunlit-courtyard-villa",
    title: "Sunlit Courtyard Villa",
    type: "villa",
    status: "available",
    price: 48500000,
    city: "Bengaluru",
    locality: "Whitefield",
    bedrooms: 4,
    bathrooms: 4,
    areaSqft: 3200,
    featured: true,
    images: ["assets/images/properties/sunlit-courtyard-villa-01.webp"]
  },
  {
    id: "backwater-view-apartment",
    title: "Backwater View Apartment",
    type: "apartment",
    status: "available",
    price: 9500000,
    city: "Kochi",
    locality: "Marine Drive",
    bedrooms: 3,
    bathrooms: 2,
    areaSqft: 1580,
    featured: true,
    images: ["assets/images/properties/backwater-view-apartment-01.webp"]
  },
  {
    id: "hillside-terrace-home",
    title: "Hillside Terrace Home",
    type: "rowhouse",
    status: "reserved",
    price: 17800000,
    city: "Pune",
    locality: "Baner",
    bedrooms: 3,
    bathrooms: 3,
    areaSqft: 2140,
    featured: false,
    images: ["assets/images/properties/hillside-terrace-home-01.webp"]
  },
  {
    id: "heritage-haveli-wing",
    title: "Heritage Haveli Wing",
    type: "villa",
    status: "available",
    price: 26000000,
    city: "Jaipur",
    locality: "Civil Lines",
    bedrooms: 3,
    bathrooms: 3,
    areaSqft: 2450,
    featured: true,
    images: ["assets/images/properties/heritage-haveli-wing-01.webp"]
  },
  {
    id: "skyline-two-bed-residence",
    title: "Skyline Two-Bed Residence",
    type: "apartment",
    status: "sold",
    price: 12400000,
    city: "Mumbai",
    locality: "Powai",
    bedrooms: 2,
    bathrooms: 2,
    areaSqft: 1120,
    featured: false,
    images: ["assets/images/properties/skyline-two-bed-residence-01.webp"]
  }
];
