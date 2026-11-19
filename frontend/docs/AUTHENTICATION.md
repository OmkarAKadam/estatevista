# EstateVista V2 — Frontend Authentication Integration

**Stage:** 3.7 — Authentication Frontend Pages and Vanilla JavaScript Integration
**Status:** Implemented against the completed Stage 3.6 backend
**Backend source of truth:** `AuthController`, `AuthService`, `SecurityConfig`,
`SecureCsrfTokenRepository`, and `docs/API_SPECIFICATION.md` §3.3

This document describes the browser-side authentication integration. It is a
Vanilla HTML/CSS/JavaScript implementation with no framework, no build step, and
no client-side token storage. The backend remains the only authority for
authentication and authorization decisions.

---

## 1. Verified backend contract

All REST calls use the `/api/v1` prefix and `credentials: "include"`, so the
browser sends and receives the server-managed `EV_SESSION` HttpOnly cookie.

| Method | Endpoint | Access | Request body | Success response |
| --- | --- | --- | --- | --- |
| `POST` | `/api/v1/auth/register` | Public (CSRF-exempt, exact Origin/Referer required) | `{ firstName, lastName, email, password, phone? }` | `201` with `{ success, message, data: { id, firstName, lastName, email, role, accountStatus } }` |
| `POST` | `/api/v1/auth/login` | Public, CSRF protected | `{ email, password }` | `200` with `{ success, message, data: { id, firstName, lastName, email, role, accountStatus } }` and `X-CSRF-TOKEN` |
| `GET` | `/api/v1/auth/me` | Authenticated | — | `200` with `{ success, data: { id, firstName, lastName, email, role, accountStatus } }` and `X-CSRF-TOKEN` |
| `GET` | `/api/v1/auth/csrf` | Public | — | `200` with `{ success, message }` and `X-CSRF-TOKEN`; the body contains no token |
| `POST` | `/api/v1/auth/logout` | Authenticated, CSRF protected | — | `200` with `{ success, message: "Logout successful", data: null }` |

Errors use the standard envelope:

```json
{
  "success": false,
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "The supplied data is invalid",
    "details": [{ "field": "email", "message": "must be a valid email address" }]
  },
  "timestamp": "2026-11-19T10:30:00Z",
  "path": "/api/v1/auth/register"
}
```

The frontend presents only safe, curated messages. It never renders raw server
text, stack traces, SQL details, or non-JSON error bodies.

### Registration notes

- The request DTO is exactly `firstName`, `lastName`, `email`, `password`, and
  optional `phone`. Unknown fields are rejected by the backend.
- Registration always creates a `BUYER` account and does **not** establish a
  session. The frontend shows a success state with a link to the login page.
- A duplicate email returns `409 CONFLICT` and is shown against the email field.

### Login notes

- The backend returns the same generic `401` message for an unknown email, a
  wrong password, and an inactive account. The login page shows one generic
  message, so it does not disclose whether an account exists.
- Login rotates the session identifier. The frontend then calls
  `GET /auth/me`, which both confirms the authenticated identity and refreshes
  the in-memory CSRF token.

---

## 2. CSRF handling

The backend uses the custom session-backed `SecureCsrfTokenRepository`:

- 32 bytes of `SecureRandom` data.
- Stored in the server-side session.
- Never written to a CSRF cookie or a JSON body.
- Returned only in the `X-CSRF-TOKEN` response header.
- CORS exposes that header only to the configured exact origins.

The frontend follows this lifecycle:

1. `ensureCsrfToken()` calls `GET /api/v1/auth/csrf` when no token is in memory.
2. The shared API client reads `X-CSRF-TOKEN` from the response header.
3. The token is kept in a module-scoped variable only.
4. `POST`, `PUT`, `PATCH`, and `DELETE` requests echo it in the
   `X-CSRF-TOKEN` request header.
5. Safe `GET`/`HEAD`/`OPTIONS` requests do not send the header.
6. Login and logout retry once after a `403` by forcing a fresh bootstrap, so a
   session that expired between page load and submit does not fail with a stale
   token.
7. Logout clears the in-memory token after the backend invalidates the session.
8. The token is never written to `localStorage`, `sessionStorage`, IndexedDB,
   cookies, URLs, or logs.

`POST /auth/register` opts out of CSRF header injection because the backend
explicitly exempts it and validates the exact `Origin`/`Referer` instead. The
browser supplies the `Origin` header automatically; the frontend does not spoof
it.

---

## 3. Frontend modules

```text
frontend/
├── login.html
├── register.html
├── assets/
│   ├── css/
│   │   └── auth.css
│   └── js/
│       ├── core/
│       │   ├── api.js          Shared credentialed API client + CSRF memory
│       │   └── config.js       API base URL resolution
│       └── auth/
│           ├── auth-state.js   In-memory presentation state
│           ├── auth-service.js Register/login/me/logout/initialize
│           ├── auth-ui.js      Navigation, form helpers, safe redirects, guard
│           ├── auth-init.js    Per-page session bootstrap
│           ├── login-page.js   Login validation and messaging
│           └── register-page.js Registration validation and messaging
```

### API client (`core/api.js`)

- Resolves the API base URL from `window.ESTATEVISTA_CONFIG.apiBaseUrl`, or from
  the local development default.
- Sends `credentials: "include"` on every request.
- Sets `Accept: application/json` and, when a JSON body is present,
  `Content-Type: application/json`.
- Parses JSON safely and tolerates empty or non-JSON responses.
- Converts failures into `ApiError` objects carrying the HTTP status, backend
  error code, field details, and envelope.
- Wraps transport failures as a non-sensitive network error.
- Does not log request bodies, credentials, cookies, or tokens.

### Authentication service (`auth-service.js`)

| Function | Purpose |
| --- | --- |
| `register(...)` | Buyer registration |
| `login(...)` | Ensure CSRF, submit credentials, verify via `/auth/me`, update state |
| `getCurrentUser()` | Retrieve `/auth/me` and synchronize state |
| `initializeSession()` | One deduplicated session check per page load |
| `logout()` | CSRF-protected server logout, then clear local state and token |
| `refreshCsrfToken()` | Force a fresh token for the current session |
| `clearSessionState()` | Clear local state after a foreign `401` |

`initializeSession()` uses an auth revision counter so an older in-flight
session check cannot overwrite a newer login or logout result.

### State (`auth-state.js`)

State is a small in-memory object with `status` (`unknown`, `anonymous`, or
`authenticated`) and the safe identity fields returned by the backend. It is
never persisted. Role values are used only to decide presentation; they are not
an authorization decision.

### Navigation and guard (`auth-ui.js`)

- Every page contains a `[data-auth-nav]` slot.
- Anonymous visitors see **Log in** and **Create account**.
- Authenticated visitors see the backend-provided first name and **Log out**.
- No agent or administrator dashboard links are shown, because those areas are
  not implemented.
- Logout failures are reported honestly and leave local state intact unless the
  backend already reports the session as unauthenticated.
- `requireAuthentication()` is a frontend UX guard for future protected pages.
  It redirects to the login page with a validated same-origin `next` parameter.
  No Stage 3.7 page requires authentication.

### Safe redirects

`resolveSameOriginTarget()` accepts only same-origin HTTP(S) destinations,
rejects credentials in the URL, and replaces login/register pages with the
fallback to prevent redirect loops. This is the only redirect input accepted
from a query string.

---

## 4. Page behaviour

### Registration page (`register.html`)

- First name, last name, email, optional phone, password, and confirmation.
- Client-side validation mirrors the backend's structural rules for usability:
  - 10–64 Unicode code points.
  - At most 72 UTF-8 bytes.
  - No null characters.
  - No password equal to the user's name, email local part, or the site name.
- The confirmation field is never sent.
- Loading state disables duplicate submissions.
- Backend validation details are mapped to their fields.
- Duplicate email and rate-limit responses have dedicated messages.
- Success clears the password fields and shows a success panel with a login link.
- No account is auto-authenticated after registration.

### Login page (`login.html`)

- Email and password with correct `autocomplete` values.
- Loading state disables duplicate submissions.
- Invalid credentials produce one generic message.
- Field validation errors and rate-limit failures have dedicated messages.
- A `registered=1` query parameter shows a one-time success notice.
- Already-authenticated visitors are redirected to a safe destination.
- On success the password field is cleared and the user is redirected to the
  validated `next` destination or the homepage.

---

## 5. Configuration

### Local development

The static frontend and the API are separate origins.

| Item | Value |
| --- | --- |
| Frontend | `http://localhost:5500` (or `http://127.0.0.1:5500`) |
| API | `http://localhost:8080/api/v1` (or `http://127.0.0.1:8080/api/v1`) |
| WebSocket | `ws://localhost:8080/ws` |

The local API host follows the page hostname. Opening the site at
`127.0.0.1` and calling `localhost` would make the `SameSite=Lax` session cookie
cross-site, so the credential would not be sent. Keep the hostnames aligned.

The backend must allow the exact frontend origin:

```powershell
$env:APP_CORS_ALLOWED_ORIGINS = "http://localhost:5500,http://127.0.0.1:5500"
$env:APP_WEBSOCKET_ALLOWED_ORIGINS = "http://localhost:5500,http://127.0.0.1:5500"
```

### Non-local deployments

Define the runtime configuration before the module scripts execute:

```html
<script>
  window.ESTATEVISTA_CONFIG = {
    apiBaseUrl: "https://api.example.com/api/v1",
    webSocketUrl: "wss://api.example.com/ws"
  };
</script>
```

Production must use exact HTTPS origins in `APP_CORS_ALLOWED_ORIGINS` and
`SESSION_COOKIE_SECURE=true`. No wildcard CORS is used or supported.

---

## 6. Running the integration locally

1. Start the backend with a reachable PostgreSQL database and exact CORS
   origins (see `docs/DEVELOPMENT_SETUP.md`).
2. Start the static frontend:

   ```powershell
   node frontend/dev-server.mjs
   ```

3. Open `http://localhost:5500/register.html`.
4. Create a buyer account.
5. Log in at `http://localhost:5500/login.html`.
6. Confirm that the header changes from **Log in / Create account** to the
   signed-in name and **Log out**.
7. Reload the page and confirm the session is restored through `/auth/me`.
8. Log out and confirm the header returns to the guest state and `/auth/me`
   returns `401`.

---

## 7. Manual verification checklist

- [ ] Registration form renders at desktop and mobile widths.
- [ ] Empty, malformed, short-password, mismatched-confirmation, and
      context-word passwords show field-level errors.
- [ ] Valid registration returns `201` and shows the success panel.
- [ ] Duplicate email shows the email-field conflict message.
- [ ] Backend validation details appear beside the matching fields.
- [ ] Login page renders and links to registration.
- [ ] CSRF bootstrap succeeds; the token appears only in the response header.
- [ ] Valid login creates the session cookie and redirects safely.
- [ ] Invalid login shows one generic message.
- [ ] A page reload restores the authenticated navigation state.
- [ ] Anonymous pages show **Log in / Create account**.
- [ ] Authenticated pages show the user name and **Log out**.
- [ ] Logout sends the CSRF header and invalidates the server-side session.
- [ ] After logout, `/auth/me` returns `401`.
- [ ] Expired or rotated sessions are re-checked without stale-token failures.
- [ ] Browser storage contains no credentials or CSRF token.
- [ ] Existing public pages still render and navigate correctly.
- [ ] No sensitive values appear in the console.

---

## 8. Security boundaries

- Server-side session authentication only; no JWT and no client-side tokens.
- `EV_SESSION` is an HttpOnly cookie managed by the browser and backend.
- The frontend never reads, writes, or fabricates the session cookie.
- CSRF tokens stay in memory and are sent only when required.
- Passwords are never persisted in browser storage or logged.
- Roles are presentation data only; every authorization decision is backend
  enforced.
- Frontend guards improve navigation; they are not a security boundary.
- Raw backend errors and stack traces are never shown to visitors.

---

## 9. Deferred work

Property management, favorites, inquiries, chat, notifications, dashboards, and
all Stage 3.8+ work remain out of scope. Only the authentication experience
described here is implemented.
