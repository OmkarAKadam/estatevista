/**
 * Page-lifecycle coordination for live chat transports.
 *
 * Logout must close every live socket before the server-side session is
 * invalidated. This registry keeps only teardown callbacks in memory; it never
 * holds tokens, message bodies, identifiers or any other private data.
 */

const teardowns = new Set();

/** Register a transport teardown callback and return its disposer. */
export function registerChatTeardown(callback) {
  if (typeof callback !== "function") {
    return () => {};
  }
  teardowns.add(callback);
  return () => teardowns.delete(callback);
}

/** Close all live chat transports before an authentication state change. */
export function runChatTeardowns() {
  [...teardowns].reverse().forEach((callback) => {
    try {
      callback();
    } catch {
      /* A failed teardown must not prevent session invalidation. */
    }
  });
}

export function getChatTeardownCount() {
  return teardowns.size;
}
