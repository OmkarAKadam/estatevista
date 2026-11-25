/**
 * Classic-script bridge for the favorites service.
 *
 * Public pages use classic scripts for page logic, while the service is an ES
 * module so it can share the single Fetch client and auth state. This bridge
 * exposes a deliberately small surface on window and announces readiness in
 * the same way as the public property bridge.
 */
import favoritesService from "./favorites-service.js";

window.EVFavorites = favoritesService;
window.dispatchEvent(new CustomEvent("ev-favorites-ready"));
