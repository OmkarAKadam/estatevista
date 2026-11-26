/**
 * Dependency-free deterministic checks for the Stage 6.6 inquiry service.
 * Run with: node --test frontend/tests/inquiry-service.test.mjs
 *
 * The real ES module is imported with minimal window globals; global fetch is
 * replaced with a deterministic responder that records every request. No
 * browser, backend, database or network is used.
 */
import test, { beforeEach } from "node:test";
import assert from "node:assert/strict";

const ID = "11111111-1111-1111-8111-111111111111";
const ID2 = "22222222-2222-2222-8222-222222222222";
const PROPERTY = "33333333-3333-3333-8333-333333333333";
const USER = {
  id: "44444444-4444-4444-8444-444444444444",
  email: "buyer@example.com",
  role: "BUYER",
  firstName: "Stage",
  lastName: "Six",
  accountStatus: "ACTIVE"
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

const inquiries = await import("../assets/js/inquiries/inquiry-service.js");
const lifecycle = await import("../assets/js/inquiries/inquiry-lifecycle.js");
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
      pathname: url.pathname,
      params: url.searchParams,
      method: String(init.method ?? "GET").toUpperCase(),
      body: init.body ? JSON.parse(init.body) : null,
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

function csrf(url) {
  if (url.pathname === "/api/v1/auth/csrf") {
    return json({ success: true, data: {} }, 200, {
      "X-CSRF-TOKEN": "deterministic-csrf"
    });
  }
  return null;
}

function detail(status = "NEW", message = "A private message") {
  return {
    id: ID,
    propertyId: PROPERTY,
    propertyTitle: "Published fixture",
    buyerId: USER.id,
    assignedAgentId: ID2,
    subject: "Subject",
    message,
    status,
    resolvedAt: null,
    createdAt: "2026-11-26T10:00:00Z",
    updatedAt: "2026-11-26T10:00:00Z"
  };
}

function listPayload(items = [detail()], meta = {}) {
  return {
    success: true,
    data: items.map(({ message, ...item }) => item),
    meta: {
      page: 0,
      size: 20,
      totalElements: items.length,
      totalPages: items.length ? 1 : 0,
      hasNext: false,
      hasPrevious: false,
      ...meta
    }
  };
}

function defaultHandler(url, init = {}) {
  const csrfResponse = csrf(url);
  if (csrfResponse) {
    return csrfResponse;
  }
  if (String(init.method ?? "GET").toUpperCase() === "POST") {
    return json({ success: true, data: { id: ID, status: "NEW" } }, 201);
  }
  if (url.pathname.endsWith("/status") && url.pathname.includes("/inquiries/")) {
    return json({ success: true, data: detail() });
  }
  if (url.pathname.includes("/inquiries")) {
    return json(listPayload());
  }
  return json({ success: true, data: {} });
}

function privateCalls() {
  return calls.filter(
    (call) =>
      call.pathname.includes("/inquiries") ||
      call.pathname.startsWith("/api/v1/admin/") ||
      call.pathname.startsWith("/api/v1/agents/me/") ||
      call.pathname.includes("/inquiries\"")
  );
}

async function tick(times = 2) {
  for (let index = 0; index < times; index += 1) {
    await new Promise((resolve) => setImmediate(resolve));
  }
}

function hang(signal) {
  return new Promise((resolve, reject) => {
    const error = new Error("The request was cancelled.");
    error.name = "AbortError";
    signal?.addEventListener("abort", () => reject(error), { once: true });
  });
}

beforeEach(() => {
  calls = [];
  setAnonymous();
  clearCsrfToken();
  inquiries.reset();
  installFetch(defaultHandler);
});

test("all six exact endpoints, payloads and query parameters are used", async () => {
  setAuthenticated(USER);
  await inquiries.submitInquiry(PROPERTY, { subject: " Hello ", message: " Hi " });
  await inquiries.listInquiries("buyer", { page: 1, size: 25, sort: "oldest", status: "NEW" });
  setAuthenticated({ ...USER, role: "AGENT" });
  await inquiries.listInquiries("agent", { page: 0, size: 20, sort: "newest" });
  setAuthenticated({ ...USER, role: "ADMIN" });
  await inquiries.listInquiries("admin", { page: 0, size: 20, sort: "newest", status: "CLOSED" });
  setAuthenticated(USER);
  await inquiries.getDetail(ID);
  setAuthenticated({ ...USER, role: "AGENT" });
  await inquiries.changeStatus(ID, "IN_PROGRESS", "NEW");

  const paths = privateCalls().map((call) => call.path);
  assert.deepEqual(paths, [
    `/api/v1/properties/${PROPERTY}/inquiries`,
    "/api/v1/inquiries/me?page=1&size=25&sort=oldest&status=NEW",
    "/api/v1/agents/me/inquiries?page=0&size=20&sort=newest",
    "/api/v1/admin/inquiries?page=0&size=20&sort=newest&status=CLOSED",
    `/api/v1/inquiries/${ID}`,
    `/api/v1/inquiries/${ID}/status`
  ]);

  const submit = privateCalls()[0];
  assert.equal(submit.method, "POST");
  assert.deepEqual(submit.body, { subject: "Hello", message: "Hi" });
  const mutation = privateCalls().at(-1);
  assert.equal(mutation.method, "PATCH");
  assert.deepEqual(mutation.body, { status: "IN_PROGRESS" });
  assert.equal(mutation.headers.get("X-CSRF-TOKEN"), "deterministic-csrf");
});

test("anonymous and wrong-role callers make no private requests", async () => {
  await inquiries.submitInquiry(PROPERTY, { message: "hello" });
  await inquiries.listInquiries("buyer");
  await inquiries.listInquiries("agent");
  await inquiries.listInquiries("admin");
  await inquiries.getDetail(ID);
  await inquiries.changeStatus(ID, "CLOSED", "NEW");

  setAuthenticated({ ...USER, role: "AGENT" });
  await inquiries.submitInquiry(PROPERTY, { message: "hello" });
  await inquiries.listInquiries("buyer");
  await inquiries.listInquiries("admin");
  setAuthenticated({ ...USER, role: "ADMIN" });
  await inquiries.submitInquiry(PROPERTY, { message: "hello" });
  await inquiries.listInquiries("buyer");
  await inquiries.listInquiries("agent");

  assert.equal(privateCalls().length, 0);
});

test("list validation rejects invalid pagination, sort and status before any request", async () => {
  setAuthenticated(USER);
  for (const params of [
    { page: -1 },
    { size: 0 },
    { size: 101 },
    { sort: "other" },
    { status: "PENDING" },
    { status: "" }
  ]) {
    assert.equal((await inquiries.listInquiries("buyer", params)).reason, "validation");
  }
  assert.equal(privateCalls().length, 0);
  assert.equal(
    inquiries.buildListQuery({ page: 0, size: 20, sort: "newest", status: "IN_PROGRESS" }),
    "page=0&size=20&sort=newest&status=IN_PROGRESS"
  );
});

test("submission validation enforces raw limits and rejects blank trimmed content", async () => {
  setAuthenticated(USER);
  assert.equal(
    (await inquiries.submitInquiry(PROPERTY, { message: "   " })).field,
    "message"
  );
  assert.equal(
    (await inquiries.submitInquiry(PROPERTY, { message: "ok", subject: "x".repeat(201) })).field,
    "subject"
  );
  assert.equal(
    (await inquiries.submitInquiry(PROPERTY, { message: "x".repeat(5001) })).field,
    "message"
  );
  assert.equal(
    (await inquiries.submitInquiry(PROPERTY, { subject: `${"x".repeat(200)} `, message: "ok" })).field,
    "subject"
  );
  assert.equal(privateCalls().length, 0);

  const boundary = await inquiries.submitInquiry(PROPERTY, {
    subject: "y".repeat(200),
    message: "z".repeat(5000)
  });
  assert.equal(boundary.ok, true);
  assert.equal(privateCalls()[0].body.subject.length, 200);
  assert.equal(privateCalls()[0].body.message.length, 5000);
  assert.equal(privateCalls()[0].body.buyerId, undefined);
});

test("duplicate submission and per-property pending state prevent a second POST", async () => {
  setAuthenticated(USER);
  let release = null;
  installFetch((url, init) => {
    const csrfResponse = csrf(url);
    if (csrfResponse) {
      return csrfResponse;
    }
    if (init.method === "POST") {
      return new Promise((resolve) => {
        release = () => resolve(json({ success: true, data: { id: ID } }, 201));
      });
    }
    return defaultHandler(url);
  });

  const first = inquiries.submitInquiry(PROPERTY, { message: "first" });
  await tick();
  assert.equal(inquiries.getView().pendingSubmissions.includes(PROPERTY), true);
  const second = await inquiries.submitInquiry(PROPERTY, { message: "second" });
  assert.equal(second.reason, "duplicate_submission");
  assert.equal(calls.filter((call) => call.method === "POST").length, 1);

  release();
  assert.equal((await first).ok, true);
  assert.equal(inquiries.getView().pendingSubmissions.length, 0);
});

test("per-inquiry pending mutation protection prevents duplicate PATCH", async () => {
  setAuthenticated({ ...USER, role: "AGENT" });
  let release = null;
  installFetch((url, init) => {
    const csrfResponse = csrf(url);
    if (csrfResponse) {
      return csrfResponse;
    }
    if (init.method === "PATCH") {
      return new Promise((resolve) => {
        release = () => resolve(json({ success: true, data: detail("IN_PROGRESS") }));
      });
    }
    return defaultHandler(url);
  });

  const first = inquiries.changeStatus(ID, "IN_PROGRESS", "NEW");
  await tick();
  const second = await inquiries.changeStatus(ID, "IN_PROGRESS", "NEW");
  assert.equal(second.reason, "duplicate_mutation");
  assert.equal(calls.filter((call) => call.method === "PATCH").length, 1);
  assert.equal(inquiries.getView().pendingMutations.length, 1);
  release();
  assert.equal((await first).ok, true);
  assert.equal(inquiries.getView().pendingMutations.length, 0);
});

test("invalid lifecycle transitions are rejected without a request", async () => {
  setAuthenticated({ ...USER, role: "ADMIN" });
  for (const [status, target] of [
    ["CLOSED", "NEW"],
    ["RESOLVED", "IN_PROGRESS"],
    ["NEW", "RESOLVED"],
    ["UNKNOWN", "CLOSED"]
  ]) {
    const result = await inquiries.changeStatus(ID, target, status);
    assert.equal(result.reason, "invalid_transition");
  }
  assert.equal(privateCalls().length, 0);
});

test("lifecycle presentation exposes exactly the approved transitions", () => {
  assert.deepEqual(lifecycle.allowedTransitions("NEW"), ["IN_PROGRESS", "CLOSED"]);
  assert.deepEqual(lifecycle.allowedTransitions("IN_PROGRESS"), ["RESOLVED", "CLOSED"]);
  assert.deepEqual(lifecycle.allowedTransitions("RESOLVED"), ["CLOSED"]);
  assert.deepEqual(lifecycle.allowedTransitions("CLOSED"), []);
  assert.deepEqual(lifecycle.allowedTransitions("UNEXPECTED"), []);
  assert.equal(lifecycle.isTransitionAllowed("CLOSED", "NEW"), false);
});

test("stale list responses cannot overwrite newer filter results", async () => {
  setAuthenticated(USER);
  let releaseOld = null;
  installFetch((url, init) => {
    const csrfResponse = csrf(url);
    if (csrfResponse) {
      return csrfResponse;
    }
    if (url.searchParams.get("status") === "NEW") {
      return new Promise((resolve) => {
        releaseOld = () =>
          resolve(json(listPayload([detail("NEW")], { page: 3, totalElements: 100 })));
      });
    }
    return json(listPayload([detail("CLOSED", "Second")], { totalElements: 1 }));
  });

  const oldRequest = inquiries.listInquiries("buyer", { status: "NEW", page: 3 });
  await tick();
  const newer = await inquiries.listInquiries("buyer", { status: "CLOSED", page: 0 });
  assert.equal(newer.ok, true);
  assert.equal(newer.items[0].status, "CLOSED");
  releaseOld();
  const oldResult = await oldRequest;
  assert.equal(oldResult.ok, false);
  assert.equal(oldResult.reason, "stale");
});

test("authentication revision invalidation discards delayed private responses", async () => {
  setAuthenticated(USER);
  let release = null;
  installFetch((url, init) => {
    const csrfResponse = csrf(url);
    if (csrfResponse) {
      return csrfResponse;
    }
    return new Promise((resolve) => {
      release = () => resolve(json(listPayload([detail()])));
    });
  });

  const pendingRequest = inquiries.listInquiries("buyer");
  await tick();
  setAuthenticated({ ...USER, email: "other@example.com" });
  release();
  const result = await pendingRequest;
  assert.equal(result.reason, "auth_changed");
  assert.equal(result.ok, false);
});

test("reset aborts cancellable reads and clears pending state", async () => {
  setAuthenticated(USER);
  installFetch((url, init) => {
    const csrfResponse = csrf(url);
    if (csrfResponse) {
      return csrfResponse;
    }
    return hang(init.signal);
  });

  const pendingRequest = inquiries.listInquiries("buyer");
  await tick();
  inquiries.reset();
  const result = await pendingRequest;
  assert.equal(result.reason, "cancelled");
  assert.equal(inquiries.getView().pendingSubmissions.length, 0);
  assert.equal(inquiries.getView().pendingMutations.length, 0);
});

test("409 conflict retrieves authoritative detail and never repeats the mutation", async () => {
  setAuthenticated({ ...USER, role: "ADMIN" });
  let patches = 0;
  let gets = 0;
  installFetch((url, init) => {
    const csrfResponse = csrf(url);
    if (csrfResponse) {
      return csrfResponse;
    }
    if (init.method === "PATCH") {
      patches += 1;
      return json({ success: false, error: { code: "CONFLICT" } }, 409);
    }
    gets += 1;
    return json({ success: true, data: detail("CLOSED") });
  });

  const result = await inquiries.changeStatus(ID, "IN_PROGRESS", "NEW");
  assert.equal(result.reason, "conflict");
  assert.equal(result.ok, false);
  assert.equal(result.detail.status, "CLOSED");
  assert.equal(patches, 1);
  assert.equal(gets, 1);
});

test("ambiguous PATCH retrieves authoritative detail and never replays", async () => {
  setAuthenticated({ ...USER, role: "AGENT" });
  let patches = 0;
  installFetch((url, init) => {
    const csrfResponse = csrf(url);
    if (csrfResponse) {
      return csrfResponse;
    }
    if (init.method === "PATCH") {
      patches += 1;
      throw new TypeError("network failure");
    }
    return json({ success: true, data: detail("RESOLVED") });
  });

  const result = await inquiries.changeStatus(ID, "RESOLVED", "IN_PROGRESS");
  assert.equal(result.reason, "ambiguous");
  assert.equal(result.detail.status, "RESOLVED");
  assert.equal(patches, 1);
});

test("ambiguous POST is reported without automatic replay", async () => {
  setAuthenticated(USER);
  let posts = 0;
  installFetch((url, init) => {
    const csrfResponse = csrf(url);
    if (csrfResponse) {
      return csrfResponse;
    }
    posts += 1;
    throw new Error("aborted after commit");
  });

  const result = await inquiries.submitInquiry(PROPERTY, { message: "committed?" });
  assert.equal(result.reason, "ambiguous");
  assert.equal(posts, 1);
});

test("429 preserves Retry-After and suppresses subsequent inquiry traffic", async () => {
  setAuthenticated(USER);
  installFetch((url, init) => {
    const csrfResponse = csrf(url);
    if (csrfResponse) {
      return csrfResponse;
    }
    return json({ success: false, error: { code: "TOO_MANY_REQUESTS" } }, 429, {
      "Retry-After": "120"
    });
  });

  const result = await inquiries.submitInquiry(PROPERTY, { message: "hello" });
  assert.equal(result.reason, "rate_limit");
  assert.ok(result.retryAfterMs > 119000);
  assert.equal(inquiries.getCooldown().active(), true);
  assert.ok(inquiries.getCooldown().remainingMs() > 110000);
  const before = calls.length;
  await inquiries.listInquiries("buyer");
  await inquiries.submitInquiry(PROPERTY, { message: "again" });
  await inquiries.getDetail(ID);
  assert.equal(calls.length, before);
});

test("401 clears session state while 403 does not", async () => {
  setAuthenticated(USER);
  installFetch((url) => {
    const csrfResponse = csrf(url);
    if (csrfResponse) {
      return csrfResponse;
    }
    return json({ success: false, error: { code: "UNAUTHORIZED" } }, 401);
  });
  assert.equal((await inquiries.getDetail(ID)).reason, "auth");
  assert.equal((await import("../assets/js/auth/auth-state.js")).getAuthState().status, "anonymous");

  setAuthenticated({ ...USER, role: "AGENT" });
  installFetch((url) => {
    const csrfResponse = csrf(url);
    if (csrfResponse) {
      return csrfResponse;
    }
    return json({ success: false, error: { code: "FORBIDDEN" } }, 403);
  });
  assert.equal((await inquiries.changeStatus(ID, "CLOSED", "NEW")).reason, "forbidden");
  assert.equal((await import("../assets/js/auth/auth-state.js")).getAuthState().status, "authenticated");
});

test("malformed list and detail responses fail safely", async () => {
  setAuthenticated(USER);
  installFetch((url) => {
    const csrfResponse = csrf(url);
    if (csrfResponse) {
      return csrfResponse;
    }
    return json({ success: true, data: [{ id: "not-a-uuid" }], meta: null });
  });
  assert.equal((await inquiries.listInquiries("buyer")).reason, "invalid_response");

  installFetch((url) => {
    const csrfResponse = csrf(url);
    if (csrfResponse) {
      return csrfResponse;
    }
    return json({ success: true, data: { id: ID, status: "NEW" } });
  });
  assert.equal((await inquiries.getDetail(ID)).reason, "invalid_response");
});

test("list normalization excludes messages and detail normalization requires them", async () => {
  setAuthenticated(USER);
  installFetch((url) => {
    const csrfResponse = csrf(url);
    if (csrfResponse) {
      return csrfResponse;
    }
    return json(listPayload());
  });
  const list = await inquiries.listInquiries("buyer");
  assert.equal(list.ok, true);
  assert.equal(Object.hasOwn(list.items[0], "message"), false);
  assert.equal((await inquiries.getDetail(ID)).reason, "invalid_response");
});

test("status mutation requires an approved current status", async () => {
  setAuthenticated({ ...USER, role: "AGENT" });
  assert.equal((await inquiries.changeStatus(ID, "IN_PROGRESS")).reason, "invalid_transition");
  assert.equal(
    (await inquiries.changeStatus(ID, "RESOLVED", "NEW")).reason,
    "invalid_transition"
  );
  assert.equal(privateCalls().length, 0);
});

test("conflict recovery reads authoritative detail despite a newly active cooldown", async () => {
  setAuthenticated({ ...USER, role: "ADMIN" });
  let patches = 0;
  let gets = 0;
  installFetch((url, init) => {
    const csrfResponse = csrf(url);
    if (csrfResponse) {
      return csrfResponse;
    }
    if (String(init.method ?? "GET").toUpperCase() === "PATCH") {
      patches += 1;
      inquiries.getCooldown().start(60000);
      return json({ success: false, error: { code: "CONFLICT" } }, 409);
    }
    gets += 1;
    return json({ success: true, data: detail("CLOSED") });
  });

  const result = await inquiries.changeStatus(ID, "IN_PROGRESS", "NEW");
  assert.equal(result.reason, "conflict");
  assert.equal(result.detail.status, "CLOSED");
  assert.equal(patches, 1);
  assert.equal(gets, 1);
  assert.equal(inquiries.getCooldown().active(), true);
});
