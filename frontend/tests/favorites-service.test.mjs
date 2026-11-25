/**
 * Dependency-free deterministic checks for the Stage 6.3 favorites service.
 * Run with: node --test frontend/tests/favorites-service.test.mjs
 *
 * No browser, backend or network is used. The real ES module is imported after
 * minimal window globals are installed, and global fetch is replaced with a
 * deterministic responder that records every request.
 */
import test, { beforeEach } from "node:test";
import assert from "node:assert/strict";

const ID1 = "11111111-1111-1111-1111-111111111111";
const ID2 = "22222222-2222-2222-2222-222222222222";
const USER = {
  id: "33333333-3333-3333-3333-333333333333",
  email: "buyer@example.com",
  role: "BUYER",
  firstName: "Stage",
  lastName: "Six"
};

globalThis.window = {
  ESTATEVISTA_CONFIG: { apiBaseUrl: "http://localhost:8080/api/v1" },
  location: {
    hostname: "localhost",
    href: "http://localhost:5500/",
    pathname: "/",
    search: "",
    hash: ""
  },
  setTimeout: globalThis.setTimeout,
  clearTimeout: globalThis.clearTimeout,
  addEventListener() {},
  removeEventListener() {}
};

const { default: favorites } = await import(
  "../assets/js/favorites/favorites-service.js"
);
const { setAuthenticated, setAnonymous } = await import(
  "../assets/js/auth/auth-state.js"
);
const { clearCsrfToken } = await import("../assets/js/core/api.js");

let calls = [];
let responder = () => null;

function installFetch(handler) {
  responder = handler;
  globalThis.fetch = async (input, init = {}) => {
    const url = new URL(typeof input === "string" ? input : input.url);
    const headers = new Headers(init.headers ?? {});
    calls.push({
      path: url.pathname + url.search,
      method: String(init.method ?? "GET").toUpperCase(),
      headers,
      signal: init.signal
    });
    const response = responder(url, init);
    if (response instanceof Error) {
      throw response;
    }
    return response;
  };
}

function json(body, status = 200, headers = {}) {
  const finalHeaders = new Headers(headers);
  if (!finalHeaders.has("Content-Type")) {
    finalHeaders.set("Content-Type", "application/json");
  }
  return new Response(JSON.stringify(body), { status, headers: finalHeaders });
}

function noContent(headers = {}) {
  return new Response(null, { status: 204, headers: new Headers(headers) });
}

function defaultHandler(url) {
  if (url.pathname === "/api/v1/auth/csrf") {
    return json({ success: true, data: {} }, 200, {
      "X-CSRF-TOKEN": "deterministic-csrf"
    });
  }
  return json({ success: true, data: {} });
}

function favoriteCalls() {
  return calls.filter((call) => call.path.includes("/favorites"));
}

function buyer() {
  setAuthenticated(USER);
}

beforeEach(() => {
  calls = [];
  setAnonymous();
  clearCsrfToken();
  favorites.getCooldown().clear();
  installFetch(defaultHandler);
});

test("anonymous, ADMIN and AGENT surfaces make no private favorites requests", async () => {
  for (const role of ["ADMIN", "AGENT"]) {
    setAuthenticated({ ...USER, role });
    await favorites.syncCollection([ID1]);
    await favorites.getStatus(ID1);
    await favorites.listFavorites();
    await favorites.addFavorite(ID1);
    await favorites.removeFavorite(ID1);
  }

  await favorites.syncCollection([ID1]);
  await favorites.getStatus(ID1);
  await favorites.listFavorites();
  await favorites.addFavorite(ID1);
  await favorites.removeFavorite(ID1);

  assert.equal(favoriteCalls().length, 0);
});

test("batched status canonicalizes IDs, de-duplicates and enforces the cap before de-duplication", async () => {
  buyer();
  installFetch((url) => {
    if (url.pathname === "/api/v1/auth/csrf") {
      return defaultHandler(url);
    }
    assert.equal(
      url.pathname,
      "/api/v1/favorites/status"
    );
    assert.equal(
      url.searchParams.get("propertyIds"),
      `${ID1},${ID2}`
    );
    return json({
      success: true,
      data: {
        favorites: [
          { propertyId: ID1, isFavorite: true },
          { propertyId: ID2, isFavorite: false }
        ]
      }
    });
  });

  const result = await favorites.syncCollection([
    ID1.toUpperCase(),
    ID1,
    ID2.toUpperCase()
  ]);

  assert.equal(favoriteCalls().length, 1);
  assert.equal(result.get(ID1), true);
  assert.equal(result.get(ID2), false);
  assert.equal(favorites.isFavorite(ID1), true);

  await assert.rejects(
    () => favorites.syncCollection(Array(101).fill(ID1)),
    /at most 100 properties/
  );
  assert.equal(favoriteCalls().length, 1);
});

test("individual status reads are forced and failures preserve the last confirmed state", async () => {
  buyer();
  let reads = 0;
  installFetch((url) => {
    if (url.pathname === "/api/v1/auth/csrf") {
      return defaultHandler(url);
    }
    assert.equal(url.pathname, `/api/v1/favorites/${ID1}/status`);
    reads += 1;
    if (reads === 1) {
      return json({ success: true, data: { propertyId: ID1, isFavorite: true } });
    }
    return json({ success: false, error: { code: "SERVER_ERROR" } }, 500);
  });

  assert.equal(await favorites.getStatus(ID1, { force: true }), true);
  assert.equal(await favorites.getStatus(ID1, { force: true }), true);
  assert.equal(reads, 2);
  assert.equal(favorites.isFavorite(ID1), true);
});

test("mutation success semantics and failed mutations preserve confirmed state", async () => {
  buyer();
  let mode = "add";
  installFetch((url, init) => {
    if (url.pathname === "/api/v1/auth/csrf") {
      return defaultHandler(url);
    }
    if (init.method === "POST") {
      if (mode === "add") {
        return json(
          { success: true, data: { propertyId: ID1, isFavorite: true } },
          201
        );
      }
      return json({ success: false, error: { code: "FORBIDDEN" } }, 403);
    }
    assert.equal(init.method, "DELETE");
    return noContent();
  });

  const added = await favorites.addFavorite(ID1);
  assert.equal(added.ok, true);
  assert.equal(added.action, "added");
  assert.equal(favorites.isFavorite(ID1), true);

  const removed = await favorites.removeFavorite(ID1);
  assert.equal(removed.ok, true);
  assert.equal(removed.action, "removed");
  assert.equal(favorites.isFavorite(ID1), false);

  mode = "fail";
  const failed = await favorites.addFavorite(ID1);
  assert.equal(failed.ok, false);
  assert.equal(failed.reason, "forbidden");
  assert.equal(favorites.isFavorite(ID1), false);
});

test("a mutation publishes pending state immediately and releases it on completion", async () => {
  buyer();
  let releaseDelete = null;
  installFetch((url, init) => {
    if (url.pathname === "/api/v1/auth/csrf") {
      return defaultHandler(url);
    }
    if (init.method === "DELETE") {
      return new Promise((resolve) => {
        releaseDelete = () => resolve(noContent());
      });
    }
    return json({ success: true, data: { propertyId: ID1, isFavorite: true } }, 201);
  });

  assert.equal((await favorites.addFavorite(ID1)).ok, true);

  const views = [];
  const unsubscribe = favorites.subscribe((view) => views.push(view));
  views.length = 0;

  const pendingResult = favorites.removeFavorite(ID1);
  await new Promise((resolve) => setImmediate(resolve));

  assert.equal(typeof releaseDelete, "function");
  assert.equal(favorites.isPending(ID1), true);
  assert.equal(views.at(-1).pendingCount, 1);

  releaseDelete();
  const result = await pendingResult;
  unsubscribe();

  assert.equal(result.ok, true);
  assert.equal(favorites.isFavorite(ID1), false);
  assert.equal(favorites.isPending(ID1), false);
  assert.equal(views.at(-1).pendingCount, 0);
});

test("an ambiguous mutation is reconciled by a read and never retried", async () => {
  buyer();
  let deletes = 0;
  let reads = 0;
  installFetch((url, init) => {
    if (url.pathname === "/api/v1/auth/csrf") {
      return defaultHandler(url);
    }
    if (init.method === "DELETE") {
      deletes += 1;
      throw new TypeError("network failure");
    }
    reads += 1;
    assert.equal(url.pathname, `/api/v1/favorites/${ID1}/status`);
    return json({ success: true, data: { propertyId: ID1, isFavorite: true } });
  });

  const result = await favorites.removeFavorite(ID1);
  assert.equal(result.ok, false);
  assert.equal(result.reason, "reconciled");
  assert.equal(result.state, true);
  assert.equal(deletes, 1);
  assert.equal(reads, 1);
  assert.equal(favorites.isFavorite(ID1), true);
});

test("429 starts the full shared cooldown and suppresses subsequent requests", async () => {
  buyer();
  installFetch((url, init) => {
    if (url.pathname === "/api/v1/auth/csrf") {
      return defaultHandler(url);
    }
    assert.equal(init.method, "POST");
    return json(
      { success: false, error: { code: "TOO_MANY_REQUESTS" } },
      429,
      { "Retry-After": "120" }
    );
  });

  const result = await favorites.addFavorite(ID1);
  assert.equal(result.ok, false);
  assert.equal(result.reason, "rate_limit");
  assert.ok(result.retryAfterMs > 119000);
  assert.equal(favorites.getCooldown().active(), true);
  assert.ok(favorites.getCooldown().remainingMs() > 100000);

  calls = [];
  await favorites.getStatus(ID1, { force: true });
  await favorites.addFavorite(ID1);
  await favorites.syncCollection([ID1]);
  assert.equal(favoriteCalls().length, 0);
});

test("paginated list validates arguments and marks restricted placeholders as saved", async () => {
  buyer();
  installFetch((url) => {
    if (url.pathname === "/api/v1/auth/csrf") {
      return defaultHandler(url);
    }
    assert.equal(url.pathname, "/api/v1/favorites");
    assert.equal(url.searchParams.get("page"), "0");
    assert.equal(url.searchParams.get("size"), "12");
    assert.equal(url.searchParams.get("sort"), "oldest");
    return json({
      success: true,
      data: [
        {
          propertyId: ID1,
          createdAt: "2026-11-24T10:30:00Z",
          property: { id: ID1, title: "Published fixture", status: "PUBLISHED" }
        },
        {
          propertyId: ID2,
          createdAt: "2026-11-23T10:30:00Z",
          property: {
            availability: "UNAVAILABLE",
            placeholder: "Property no longer available"
          }
        }
      ],
      meta: {
        page: 0,
        size: 12,
        totalElements: 2,
        totalPages: 1,
        hasNext: false,
        hasPrevious: false
      }
    });
  });

  const result = await favorites.listFavorites({ page: 0, size: 12, sort: "oldest" });
  assert.equal(result.items.length, 2);
  assert.equal(result.meta.totalElements, 2);
  assert.equal(favorites.isFavorite(ID1), true);
  assert.equal(favorites.isFavorite(ID2), true);

  await assert.rejects(() => favorites.listFavorites({ size: 101 }), /page size/);
  await assert.rejects(() => favorites.listFavorites({ sort: "random" }), /sort/);
  assert.equal(favoriteCalls().length, 1);
});

test("an auth revision change clears cached favorite state", async () => {
  buyer();
  installFetch((url) => {
    if (url.pathname === "/api/v1/auth/csrf") {
      return defaultHandler(url);
    }
    return json({ success: true, data: { propertyId: ID1, isFavorite: true } });
  });

  assert.equal(await favorites.getStatus(ID1, { force: true }), true);
  assert.equal(favorites.isFavorite(ID1), true);
  setAnonymous();
  assert.equal(favorites.isFavorite(ID1), false);
});
