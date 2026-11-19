/**
 * Page bootstrap for authentication-aware navigation.
 *
 * Loaded as an ES module on every public page. It renders the guest controls
 * immediately, then asks the backend whether a valid session exists.
 */

import { initializeSession } from "./auth-service.js";
import { initAuthNavigation } from "./auth-ui.js";

initAuthNavigation();
initializeSession();
