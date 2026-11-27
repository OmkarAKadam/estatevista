# ESTATEVISTA — API SPECIFICATION

**Project:** EstateVista V2  
**Stage:** 1.5 — API Specification  
**API Style:** REST  
**API Version:** v1  
**Backend:** Java + Spring Boot  
**Database:** PostgreSQL  
**Status:** Proposed baseline — §25 records APPROVED clarifications; headers reconciled during Stage 1.6; Stage 1.9 audit corrections applied; Stage 6 Favorites and Inquiry decisions are recorded in `docs/STAGE_6_DECISION_REGISTER.md` and surfaced in §§9–10, §17, §19, §23, and §25.4 without changing this document's overall status

---

# 1. Purpose

This document defines the API contract between the EstateVista frontend and backend.

It establishes:

- REST API conventions.
- Endpoint definitions.
- Request and response structures.
- Authentication and authorization requirements.
- Validation and error handling.
- Pagination, filtering, and sorting.
- WebSocket/STOMP messaging contracts.
- Image upload operations.
- Role-based access requirements.
- Functional requirement traceability.

The API specification is the communication contract for frontend and backend development.

# 2. API Architecture

## 2.1 Base URL

Development:

```text
http://localhost:8080/api/v1
```

Production:

```text
https://<backend-domain>/api/v1
```

All REST endpoints must use the `/api/v1/` prefix.

## 2.2 Communication Standards

| Item | Standard |
|---|---|
| API architecture | REST |
| Data format | JSON |
| Character encoding | UTF-8 |
| Authentication | Secure authenticated requests |
| HTTP methods | GET, POST, PUT, PATCH, DELETE |
| API version | v1 |
| Frontend communication | JavaScript Fetch API |
| Real-time communication | WebSocket + STOMP |
| Date/time format | ISO 8601 |
| Identifier format | UUID |

## 2.3 HTTP Methods

| Method | Purpose |
|---|---|
| GET | Retrieve resources |
| POST | Create resources or execute actions |
| PUT | Replace a resource |
| PATCH | Partially update a resource |
| DELETE | Remove a relationship or resource where permitted |

The backend should use appropriate HTTP methods rather than implementing every operation through POST.

# 3. Authentication and Authorization

Authentication is settled as a technical design by `SECURITY_ARCHITECTURE.md` SD-01: a server-side session with an opaque host-only `HttpOnly` cookie, Spring Session JDBC storage, and a session-bound CSRF token. Implementation is not complete in Stage 1.8; this section defines the contract the implementation must satisfy.

The API contract assumes that the backend can identify the authenticated user and their role for protected requests.

## 3.1 User Roles

| Role | Description |
|---|---|
| VISITOR | Unauthenticated user |
| BUYER | Registered property seeker |
| AGENT | Registered property agent |
| ADMIN | Platform administrator |

Visitors are not stored as user accounts.

## 3.2 Authorization Principles

1. Public endpoints must not require authentication.
2. Protected endpoints must require a valid authenticated account.
3. Buyers may access only their own private records.
4. Agents may manage only their assigned properties and authorized related records.
5. Administrators may access authorized platform-management operations.
6. The backend must validate ownership and assignment on every protected operation.
7. Frontend route protection is not a substitute for backend authorization.
8. Unauthorized resource access should not expose sensitive information.

## 3.3 Authentication API

| Method | Endpoint | Access | Description |
|---|---|---|---|
| POST | `/auth/register` | Visitor | Register buyer account |
| POST | `/auth/login` | Public | Authenticate user |
| POST | `/auth/logout` | Authenticated | End current session |
| GET | `/auth/me` | Authenticated | Retrieve current user |
| GET | `/auth/csrf` | Public | Issue session-bound CSRF token in `X-CSRF-TOKEN` header |
| POST | `/auth/forgot-password` | Public | Request password reset |
| POST | `/auth/reset-password` | Public, valid reset token | Reset password |

Registration is limited to buyer accounts. Agent and administrator accounts are created through authorized administrative workflows.

Authentication uses a server-side session with an opaque host-only `HttpOnly` cookie and a session-bound CSRF token exposed through the CORS-exposed `X-CSRF-TOKEN` response header; no JWT or cookie-based CSRF token is used. The token is returned on the login response, `GET /auth/me`, and the header-only `GET /auth/csrf` bootstrap; it must be echoed in the `X-CSRF-TOKEN` request header on state-changing requests and is held in memory by the frontend. No response body, including the CSRF bootstrap body, contains the token or an alternative token field. The CSRF mechanism is governed by `SECURITY_ARCHITECTURE.md` SD-05, SD-48, and SD-53. `POST /auth/reset-password` accepts a purpose-scoped token: a `PASSWORD_RESET` token (30-minute expiry) or an `ACCOUNT_ACTIVATION` token (72-hour expiry) issued when an administrator creates an agent. This reuses one contract instead of adding a duplicate activation endpoint.

### 3.3.1 Register Buyer

`POST /auth/register`

Request:

```json
{
  "firstName": "John",
  "lastName": "Doe",
  "email": "john@example.com",
  "password": "example-password",
  "phone": "+919876543210"
}
```

Success: `201 Created`

```json
{
  "success": true,
  "message": "Account registered successfully",
  "data": {
    "id": "uuid",
    "firstName": "John",
    "lastName": "Doe",
    "email": "john@example.com",
    "role": "BUYER",
    "accountStatus": "ACTIVE"
  }
}
```

Password values must never be returned in API responses.

### 3.3.2 Login

`POST /auth/login`

Request:

```json
{
  "email": "john@example.com",
  "password": "example-password"
}
```

Success: `200 OK`

```json
{
  "success": true,
  "message": "Login successful",
  "data": {
    "id": "uuid",
    "firstName": "John",
    "lastName": "Doe",
    "email": "john@example.com",
    "role": "BUYER"
  }
}
```

The authentication mechanism is a server-side session cookie as specified in `SECURITY_ARCHITECTURE.md` (SD-01, approved 11 November 2026). The response also carries the session-bound CSRF token in the `X-CSRF-TOKEN` response header; the token is not part of the JSON body.

### 3.3.3 Current User

`GET /auth/me`

Success: `200 OK`

```json
{
  "success": true,
  "data": {
    "id": "uuid",
    "firstName": "John",
    "lastName": "Doe",
    "email": "john@example.com",
    "role": "BUYER"
  }
}
```

`GET /auth/me` also returns the session-bound CSRF token in the `X-CSRF-TOKEN` response header. The JSON body never contains the token.

### 3.3.4 CSRF Bootstrap

`GET /auth/csrf`

Access: public. This compatibility bootstrap creates or resolves the anonymous server-side session and returns its session-bound token only in the `X-CSRF-TOKEN` response header. It is not an authentication endpoint and does not replace `GET /auth/me` as the authenticated bootstrap.

Success: `200 OK`

Headers:

```http
X-CSRF-TOKEN: <session-bound token>
Cache-Control: no-store
Access-Control-Expose-Headers: X-CSRF-TOKEN
```

Response body:

```json
{
  "success": true,
  "message": "CSRF token issued"
}
```

The body intentionally has no `data`, `token`, or `headerName` field. Clients must read the token from the CORS-exposed `X-CSRF-TOKEN` response header and send it back in the `X-CSRF-TOKEN` request header on `POST`, `PUT`, `PATCH`, and `DELETE` requests. No CSRF cookie or browser-storage mechanism is used. CORS exposes the header only to configured allowed origins; registration keeps its exact Origin/Referer validation.

# 4. User Management APIs

## 4.1 User Profile

| Method | Endpoint | Access | Description |
|---|---|---|---|
| GET | `/users/me` | Authenticated | Retrieve own profile |
| PATCH | `/users/me` | Authenticated | Update own profile |
| PATCH | `/users/me/password` | Authenticated | Change password |

Users must not be able to change their own role or account status through profile updates.

## 4.2 Administrative User Management

| Method | Endpoint | Access | Description |
|---|---|---|---|
| GET | `/admin/users` | Admin | List users |
| GET | `/admin/users/{userId}` | Admin | Retrieve user |
| PATCH | `/admin/users/{userId}/status` | Admin | Change account status |

Agent account creation is handled separately through agent management.

# 5. Agent Management APIs

| Method | Endpoint | Access | Description |
|---|---|---|---|
| POST | `/admin/agents` | Admin | Create agent account |
| GET | `/admin/agents` | Admin | List agents |
| GET | `/admin/agents/{agentId}` | Admin | Retrieve agent |
| PATCH | `/admin/agents/{agentId}` | Admin | Update agent profile |
| PATCH | `/admin/agents/{agentId}/status` | Admin | Change agent status |
| GET | `/agents/me` | Agent | Retrieve own agent profile |
| PATCH | `/agents/me` | Agent | Update permitted profile fields |
| GET | `/agents/me/properties` | Agent | Retrieve assigned properties |
| GET | `/agents/me/inquiries` | Agent | Retrieve authorized inquiries |
| GET | `/conversations` (agent scope) | Agent | Retrieve conversations where the agent is the original `assigned_agent_id` (§11) |

Agent profile updates must not allow agents to modify their role, account status, or property assignments.

## 5.1 Create Agent

`POST /admin/agents`

Request:

```json
{
  "firstName": "Alex",
  "lastName": "Smith",
  "email": "alex@example.com",
  "phone": "+919876543210",
  "agencyName": "EstateVista Realty",
  "bio": "Residential and commercial property consultant"
}
```

Success: `201 Created`

The backend creates the agent account with `accountStatus = INACTIVE` and an associated agent profile, then issues a 256-bit single-use `ACCOUNT_ACTIVATION` token with a 72-hour expiry. The agent sets their own password by redeeming that token through `POST /auth/reset-password`; no temporary or admin-known password is returned, displayed, or emailed. Delivery depends on the email-provider prerequisite recorded in `SECURITY_ARCHITECTURE.md` SD-11/SD-12.

# 6. Property Management APIs

## 6.1 Property Endpoints

| Method | Endpoint | Access | Description |
|---|---|---|---|
| POST | `/properties` | Admin | Create property |
| GET | `/properties/{propertyId}` | Public, subject to visibility | Retrieve property details |
| PUT | `/properties/{propertyId}` | Assigned Agent / Admin | Replace property data |
| PATCH | `/properties/{propertyId}` | Assigned Agent / Admin | Partially update property |
| PATCH | `/properties/{propertyId}/status` | Admin / authorized Agent | Update listing status |
| DELETE | `/properties/{propertyId}` | Admin | Archive property |
| GET | `/admin/properties` | Admin | Retrieve all properties |
| GET | `/agents/me/properties` | Agent | Retrieve assigned properties |
| PATCH | `/admin/properties/{propertyId}/assignment` | Admin | Assign or reassign agent |

Property creation and assignment must validate that the selected agent exists, has the `AGENT` role, and has an active account. Every property must be created with one primary agent; there is no unassigned property state (Database Design §6.3, §23.1.C; PRD PD-03).

The API must not expose archived or otherwise restricted properties through public discovery.

## 6.2 Create Property

`POST /properties`

Request:

```json
{
  "title": "Modern Family Villa",
  "description": "Spacious residential property.",
  "categoryId": "uuid",
  "subtypeId": "uuid",
  "listingType": "SALE",
  "primaryAgentId": "uuid",
  "price": 12500000,
  "currencyCode": "INR",
  "areaValue": 2400,
  "areaUnit": "SQ_FT",
  "location": {
    "addressLine": "Example Road",
    "locality": "Example Locality",
    "city": "Vadodara",
    "state": "Gujarat",
    "country": "India",
    "postalCode": "390001",
    "latitude": 22.3072,
    "longitude": 73.1812
  },
  "residentialDetails": {
    "bedrooms": 4,
    "bathrooms": 3,
    "balconies": 2,
    "furnishingStatus": "SEMI_FURNISHED",
    "parkingSpaces": 2
  },
  "amenityIds": [
    "uuid",
    "uuid"
  ]
}
```

Success: `201 Created`

The backend generates the property identifier and records the authenticated administrator as the creator. `primaryAgentId` is required, must reference an active `AGENT`, and is the property's primary assignment from creation onward.

The exact category-specific fields will be finalized during property management implementation.

# 7. Property Discovery APIs

These endpoints support the public property discovery experience.

**Public visibility rule (C-10 / D-08).** Public discovery and public detail return only `PUBLISHED` properties and their publicly servable images. `DRAFT`, `UNAVAILABLE`, `SOLD`, `RENTED`, and `ARCHIVED` records are excluded from public list, featured, recent, related, detail, and image responses. Authorized participants retain access to historical references through authenticated surfaces (favorites, inquiries, conversations, and agent/admin management). The rule adds no status and changes no lifecycle. Requests for a non-published property return `404` to an unauthenticated caller rather than disclosing existence.

| Method | Endpoint | Access | Description |
|---|---|---|---|
| GET | `/properties` | Public | Search and list properties |
| GET | `/properties/featured` | Public | Retrieve featured properties |
| GET | `/properties/recent` | Public | Retrieve recently published properties |
| GET | `/properties/{propertyId}/related` | Public | Retrieve related properties |
| GET | `/property-categories` | Public | Retrieve categories |
| GET | `/property-subtypes` | Public | Retrieve subtypes |
| GET | `/amenities` | Public | Retrieve amenities |

**Stage 5.2 implementation status.** All endpoints above are implemented except `/properties/featured`, which remains deliberately unmapped and denied by the security chain for anonymous and authenticated callers. No featured column or migration was introduced.

## 7.1 Search and Filtering

`GET /properties`

Supported query parameters:

| Parameter | Type | Description |
|---|---|---|
| `keyword` | String | Search title, description, city, or locality; maximum 100 characters |
| `categoryId` | UUID | Residential or commercial category |
| `subtypeId` | UUID | Property subtype |
| `listingType` | String | SALE or RENT (case-insensitive) |
| `minPrice` | Decimal | Minimum price |
| `maxPrice` | Decimal | Maximum price |
| `city` | String | City |
| `locality` | String | Locality |
| `minBedrooms` | Integer | Minimum bedroom count |
| `maxBedrooms` | Integer | Maximum bedroom count |
| `amenityIds` | UUID list | Required amenities; maximum 20 IDs |
| `sort` | String | Sorting option; blank defaults to `newest` |
| `page` | Integer | Zero-based page number |
| `size` | Integer | Results per page; default 12, maximum 100 |

Example:

```text
GET /properties?categoryId=uuid&listingType=SALE&minPrice=5000000&maxPrice=15000000&city=Vadodara&page=0&size=12&sort=price_asc
```

Supported sorting:

- `newest`
- `oldest`
- `price_asc`
- `price_desc`

The backend must validate query parameters and enforce reasonable page-size limits.

**Stage 5.2 verified implementation notes.** Public search enforces the approved `SECURITY_ARCHITECTURE.md` §11.10 keyword and `amenityIds` caps and returns `VALIDATION_ERROR` for violations. Invalid pages, sizes, sort values, ranges, bedroom filters, enum values, UUIDs, and numeric values use the standard error envelope. Blank string parameters are ignored, while blank numeric parameters fail Spring type conversion with `BAD_REQUEST`. Blank `sort` selects `newest`; sort names are case-sensitive; `keyword` values remain parameterized SQL `LIKE` patterns, so wildcard characters retain their existing behavior. Unknown query parameters are ignored. Public search, detail, related, recent, and reference endpoints are subject to `SECURITY_ARCHITECTURE.md` §11.9 HTTP rate limits and return `429 TOO_MANY_REQUESTS` with `Retry-After`.

## 7.2 Property List Response

Success: `200 OK`

```json
{
  "success": true,
  "data": [
    {
      "id": "uuid",
      "title": "Modern Family Villa",
      "slug": "modern-family-villa",
      "category": "RESIDENTIAL",
      "subtype": "VILLA",
      "listingType": "SALE",
      "price": 12500000,
      "currencyCode": "INR",
      "city": "Vadodara",
      "locality": "Example Locality",
      "areaValue": 2400,
      "areaUnit": "SQ_FT",
      "bedrooms": 4,
      "bathrooms": 3,
      "primaryImageUrl": "https://example.com/image.jpg",
      "status": "PUBLISHED"
    }
  ],
  "meta": {
    "page": 0,
    "size": 12,
    "totalElements": 48,
    "totalPages": 4,
    "hasNext": true,
    "hasPrevious": false
  }
}
```

Public responses must not expose private agent information, internal notes, or administrative fields.

# 8. Property Image APIs

| Method | Endpoint | Access | Description |
|---|---|---|---|
| POST | `/properties/{propertyId}/images` | Assigned Agent / Admin | Upload property image |
| GET | `/properties/{propertyId}/images` | Public, subject to visibility | Retrieve property images |
| PATCH | `/properties/{propertyId}/images/{imageId}` | Assigned Agent / Admin | Update image metadata |
| DELETE | `/properties/{propertyId}/images/{imageId}` | Assigned Agent / Admin | Remove image |
| PATCH | `/properties/{propertyId}/images/{imageId}/primary` | Assigned Agent / Admin | Set primary image |

## 8.1 Upload Contract

Uploads use `multipart/form-data`.

Field:

`file`

The backend validates:

- File type.
- File size.
- Image format.
- Property ownership or assignment.
- Upload authorization.

The backend uploads the file to the configured image-storage provider and stores its reference in PostgreSQL.

The storage provider's credentials must never be exposed to the browser.

Maximum file size, allowed formats, and image count remain pending configuration.

# 9. Favorites APIs

All paths in this section are relative to `/api/v1`.

| Method | Endpoint | Access | Description |
|---|---|---|---|
| POST | `/favorites/{propertyId}` | Buyer | Add favorite |
| DELETE | `/favorites/{propertyId}` | Buyer | Remove favorite |
| GET | `/favorites` | Buyer | Retrieve own paginated favorites |
| GET | `/favorites/{propertyId}/status` | Buyer | Check favorite status |
| GET | `/favorites/status?propertyIds=...` | Buyer | Check up to 100 favorite states in one request |

## 9.1 Add Favorite

`POST /favorites/{propertyId}`

Success: `201 Created` for a new favorite or `200 OK` for an existing favorite on a `PUBLISHED` property.

```json
{
  "success": true,
  "message": "Property added to favorites",
  "data": {
    "propertyId": "uuid",
    "isFavorite": true
  }
}
```

Buyer identity is derived only from the authenticated principal. The backend must preserve the `UNIQUE(buyer_id, property_id)` database constraint and handle concurrent duplicate additions safely. Adding a nonexistent or non-public property returns generic `404`; this restriction takes precedence over duplicate-add success. No soft-delete flag is introduced.

## 9.2 Remove Favorite

`DELETE /favorites/{propertyId}`

Success: `204 No Content` with no body for both an existing and a nonexistent favorite. Only the authenticated buyer's relationship is removed. A retained favorite for a non-public property remains removable.

## 9.3 Favorite Retention and Placeholder

New favorites may reference only `PUBLISHED` properties. Existing favorites remain stored when a property becomes `DRAFT`, `UNAVAILABLE`, `SOLD`, `RENTED`, or `ARCHIVED`; they are never automatically deleted. A non-public favorite returns only:

```json
{
  "propertyId": "uuid",
  "createdAt": "2026-11-24T10:30:00Z",
  "property": {
    "availability": "UNAVAILABLE",
    "placeholder": "Property no longer available"
  }
}
```

The `property` member is the restricted placeholder DTO; a published entry uses the approved public property summary from §7.2 in the same member. No unpublished title, price, description, image, address, agent detail, or internal lifecycle status is exposed. When the property becomes `PUBLISHED` again, the normal approved public-summary presentation is restored without creating a new favorite row.

## 9.4 Favorite List, Pagination, and Status

`GET /favorites?page=0&size=12&sort=newest`

The default size is 12, the maximum is 100, and `sort` accepts only `newest` (default) and `oldest`. Ordering uses `createdAt` with a deterministic UUID tiebreak. Each published entry contains `propertyId`, `createdAt`, and the approved public property summary from §7.2; a non-public entry uses the restricted object in §9.3. The response retains the standard `data` plus `meta` envelope.

`GET /favorites/{propertyId}/status` returns `{ "propertyId": "uuid", "isFavorite": false }` inside `data`.

`GET /favorites/status?propertyIds=<uuid1>,<uuid2>` is the approved batched status route. `propertyIds` is a single comma-separated list of canonical lowercase UUIDs; missing, empty, malformed, or more than 100 supplied values return `400`. Exact duplicates are de-duplicated after validation and returned once in first-occurrence order. The response is:

```json
{
  "success": true,
  "data": {
    "favorites": [
      { "propertyId": "uuid", "isFavorite": true },
      { "propertyId": "uuid", "isFavorite": false }
    ]
  }
}
```

The route reports only the authenticated buyer's state. Nonexistent and non-public identifiers produce `isFavorite: false`; no property existence or unpublished information is disclosed. Private favorite responses set `Cache-Control: no-store`. Homepage and listing cards use one batched request per displayed collection; the favorites page remains paginated, and no personalized favorite flag is added to public property responses. Bounded `buyer_id`/`property_id IN (...)` queries are required, with no per-card N+1 behavior. Stale in-flight responses are ignored; in-memory state is reset on logout, account change, and expired-session detection.

Stage 6 contract authority: `docs/STAGE_6_DECISION_REGISTER.md` §5.1.

# 10. Inquiry APIs

| Method | Endpoint | Access | Description |
|---|---|---|---|
| POST | `/properties/{propertyId}/inquiries` | Buyer | Submit inquiry |
| GET | `/inquiries/me` | Buyer | Retrieve own inquiries |
| GET | `/inquiries/{inquiryId}` | Buyer / Authorized Agent / Admin | Retrieve inquiry |
| GET | `/agents/me/inquiries` | Agent | Retrieve authorized inquiries |
| GET | `/admin/inquiries` | Admin | Retrieve all inquiries |
| PATCH | `/inquiries/{inquiryId}/status` | Authorized Agent / Admin | Update inquiry status |

## 10.1 Submit Inquiry

`POST /properties/{propertyId}/inquiries`

Request:

```json
{
  "subject": "Interested in this property",
  "message": "I would like to schedule a property visit."
}
```

Success: `201 Created`

```json
{
  "success": true,
  "message": "Inquiry submitted successfully",
  "data": {
    "id": "uuid",
    "propertyId": "uuid",
    "status": "NEW",
    "createdAt": "2026-11-06T10:30:00Z"
  }
}
```

The buyer identity and assigned agent must be derived from authenticated backend context and current property assignment, not trusted from client-supplied identifiers.

The original agent assignment must be retained on the inquiry.

Administrative access to an inquiry is limited to the inquiry record, its metadata, and its message. It does not grant access to any conversation or message transcript (API §25.1.E; UI D-16).

## 10.2 Stage 6 Approved Inquiry Contract

All paths in this section are relative to `/api/v1`. The table above is reconciled into the following single authoritative endpoint inventory; repeated buyer-route descriptions in other sections are superseded by it.

| Method | Endpoint | Authority | Success behavior |
|---|---|---|---|
| POST | `/properties/{propertyId}/inquiries` | Authenticated BUYER | `201` new inquiry with `NEW` status |
| GET | `/inquiries/me` | Buyer owner | `200` paginated buyer inquiry history |
| GET | `/inquiries/{inquiryId}` | Buyer owner, historically assigned AGENT, or ADMIN | `200` inquiry detail |
| GET | `/agents/me/inquiries` | Authenticated AGENT | `200` paginated historically assigned inquiries |
| GET | `/admin/inquiries` | ADMIN | `200` paginated inquiry metadata |
| PATCH | `/inquiries/{inquiryId}/status` | Historically assigned active AGENT or ADMIN | `200` updated inquiry detail |

No buyer close, withdraw, reopen, or modify route is approved. Buyer inquiry status is read-only.

**Request and DTO rules.** `subject` is optional, may be omitted or `null`, and is at most 200 characters when supplied. `message` is required, nonblank after trimming, and at most 5,000 characters. Client-supplied `buyerId`, `agentId`, `assignedAgentId`, owner, actor, `status`, `resolvedAt`, or initial-status fields are rejected with `400`. Actor and assignment values are derived server-side. A new inquiry requires a `PUBLISHED` property and an eligible active assigned AGENT; those conditions are rechecked transactionally during creation. The buyer and agent values are derived from `AuthenticatedAccount`, and the original `assigned_agent_id` is never rewritten by later property reassignment.

Inquiry detail `data` contains `id`, `propertyId`, `propertyTitle` or generic unavailable placeholder, `buyerId`, `assignedAgentId`, `subject`, `message`, `status`, `resolvedAt`, `createdAt`, and `updatedAt`. List responses exclude `message` but otherwise use the same fields within the standard `data` plus `meta` envelope. Admin detail access does not include conversations or message transcripts.

**Status transitions.** The approved statuses are `NEW`, `IN_PROGRESS`, `RESOLVED`, and `CLOSED`. Permitted transitions are `NEW → IN_PROGRESS`, `NEW → CLOSED`, `IN_PROGRESS → RESOLVED`, `IN_PROGRESS → CLOSED`, and `RESOLVED → CLOSED`; `CLOSED` is terminal. An invalid transition returns `409`. Repeating the current status is an idempotent `200` with unchanged `resolvedAt`, `updatedAt`, and no duplicate audit record. Entering `RESOLVED` sets `resolvedAt`, which is retained afterward; direct closure leaves it `null`. Every real transition creates an `INQUIRY_STATUS_CHANGED` audit record with IDs, actor, before/after status, time, and correlation ID, but no inquiry content. Concurrent status changes must be serialized or otherwise protected against invalid transitions and lost updates.

**Duplicate policy.** At most one active inquiry may exist per buyer/property pair. `NEW` and `IN_PROGRESS` are active; `RESOLVED` and `CLOSED` are inactive and permit a new inquiry. Duplicate active submissions return `409`. A PostgreSQL partial unique index is mandatory; service checks alone are insufficient. Existing inquiries are never silently merged, overwritten, or deleted.

**Pagination, sorting, and errors.** Inquiry lists use `page=0`, `size=20` default, maximum `100`, `sort=newest` default, and `sort=oldest`; `status` accepts `NEW`, `IN_PROGRESS`, `RESOLVED`, or `CLOSED`. Ordering uses `createdAt` with a deterministic UUID tiebreak. Unsupported sort fields, invalid statuses, and invalid pagination return `400`. Unauthorized and nonexistent records both return the same generic `404` standard envelope. No private-existence hint, SQL detail, stack trace, inquiry content in URLs/logs/toasts, or other user's private data is exposed.

Stage 6 contract authority: `docs/STAGE_6_DECISION_REGISTER.md` §5.2.

# 11. Conversation APIs

Conversations are property-specific and persistent.

| Method | Endpoint | Access | Description |
|---|---|---|---|
| POST | `/properties/{propertyId}/conversations` | Buyer | Create or retrieve conversation |
| GET | `/conversations` | Buyer / Agent | Retrieve accessible conversations |
| GET | `/conversations/{conversationId}` | Participants | Retrieve conversation details |
| GET | `/conversations/{conversationId}/messages` | Participants | Retrieve message history |
| PATCH | `/conversations/{conversationId}/read-state` | Participants | Update read progress |
| PATCH | `/conversations/{conversationId}/status` | Original assigned agent | **Deferred beyond Stage 7 by P-04**; not implemented or mapped in Stage 7 |

Administrators do not have conversation access or conversation-status authority in the MVP. They may view inquiry metadata and the inquiry message, but not conversations or message transcripts (Database Design §23.1.D; API §25.1.E; UI D-16).

## 11.1 Create or Retrieve Conversation

`POST /properties/{propertyId}/conversations`

Request body: none.

The backend must distinguish retrieval of an existing conversation from creation of a new one:

1. Identify the authenticated active buyer from the server-side session.
2. Check whether the permanent `(buyer_id, property_id)` conversation already exists.
3. If it exists and the requester is an authorized active original participant, return it with `200` regardless of the property's later status or current assignment. Never mutate its participants or snapshot.
4. Otherwise, require a `PUBLISHED` property, an active `BUYER` and an active `AGENT` as the current primary agent. A non-eligible or nonexistent property returns generic `404` (P-13).
5. Within a transaction, lock and refresh the property and user rows in stable UUID order, create the conversation with the current agent as the immutable `assigned_agent_id`, and rely on `uk_conversations_buyer_property` as the concurrency authority. On a unique violation, re-read and return the existing conversation; never return `409`.
6. Create participant read-state records for the buyer and original assigned agent.

The first message, like every subsequent message, is sent through the approved messaging mechanism (`/app/chat.send`). Conversation creation does not accept or persist an `initialMessage` (API §25.1.A). If the original assigned agent is inactive, protected messaging in that conversation is disabled and no fallback or transfer is created (P-03).

Success: `200 OK` for an existing conversation or `201 Created` for a new conversation.

Response:

```json
{
  "success": true,
  "data": {
    "conversationId": "uuid",
    "propertyId": "uuid",
    "propertyTitle": "string",
    "propertyStatus": "PUBLISHED",
    "buyerId": "uuid",
    "assignedAgentId": "uuid",
    "unreadCount": 0,
    "lastMessageId": "uuid or null",
    "lastMessageAt": "timestamp or null",
    "createdAt": "2026-11-06T10:30:00Z"
  }
}
```

`unreadCount` is participant-specific and is included in both conversation list and detail responses (P-10). The database unique constraint on `(buyer_id, property_id)` is the concurrency authority for creation.

## 11.2 Retrieve Messages

`GET /conversations/{conversationId}/messages?limit=30[&before=<cursor>|&after=<cursor>]`

Message history is the approved cursor-pagination exception to the generic zero-based offset rules in §18. There is no `page`, `size`, `totalElements` or `totalPages` parameter for this endpoint.

- Retrieval order is latest-first: `sentAt DESC, id DESC`.
- The caller renders the loaded window in chronological order (oldest at the top, newest at the bottom).
- `limit` defaults to `30` and has a maximum of `100`; invalid values return `400`.
- `before` returns the next older page; `after` returns messages newer than the cursor for reconnection recovery. `before` and `after` are mutually exclusive.
- A cursor is an opaque server-validated encoding of `(sentAt, id)`. A malformed, foreign-conversation or tampered cursor returns `400`; message content is never encoded in a URL.
- Concurrent inserts, reconnect recovery and commit/timestamp order differences must not shift, duplicate or omit rows.

Success: `200 OK`

```json
{
  "success": true,
  "data": [
    {
      "id": "uuid",
      "conversationId": "uuid",
      "senderId": "uuid",
      "messageType": "TEXT",
      "content": "Is this property still available?",
      "idempotencyKey": "uuid",
      "sentAt": "2026-11-06T10:30:00Z"
    }
  ],
  "meta": {
    "limit": 30,
    "nextCursor": "opaque cursor or null",
    "hasMore": true
  }
}
```

The V19 index `(conversation_id, sent_at, id)` supports this order. No monotonic sequence column is introduced by Stage 7.1.

# 12. Message APIs

Message sending is handled through WebSocket/STOMP for real-time delivery.

REST is used for retrieving message history and related operations.

| Method | Endpoint | Access | Description |
|---|---|---|---|
| GET | `/conversations/{conversationId}/messages` | Participants | Retrieve message history |
| PATCH | `/conversations/{conversationId}/read-state` | Participants | Update read position |

The backend must verify conversation participation before returning messages or updating read state.

# 13. Notification APIs

| Method | Endpoint | Access | Description |
|---|---|---|---|
| GET | `/notifications` | Authenticated | Retrieve own notifications |
| GET | `/notifications/unread-count` | Authenticated | Retrieve unread count |
| PATCH | `/notifications/{notificationId}/read` | Owner | Mark notification as read |
| PATCH | `/notifications/read-all` | Authenticated | Mark all own notifications as read |

Example response:

```json
{
  "success": true,
  "data": [
    {
      "id": "uuid",
      "type": "NEW_MESSAGE",
      "title": "New message",
      "body": "You received a new property inquiry message.",
      "resourceType": "CONVERSATION",
      "resourceId": "uuid",
      "isRead": false,
      "createdAt": "2026-11-06T10:30:00Z"
    }
  ]
}
```

# 14. Dashboard APIs

| Method | Endpoint | Access | Description |
|---|---|---|---|
| GET | `/dashboard/buyer` | Buyer | Buyer dashboard data |
| GET | `/dashboard/agent` | Agent | Agent dashboard data |
| GET | `/dashboard/admin` | Admin | Administrator dashboard data |

Dashboard responses should contain aggregated information rather than requiring the frontend to make many separate requests.

### Buyer dashboard

Potential data:

- Favorite property count.
- Recent favorites.
- Inquiry count.
- Active conversations.
- Unread messages.
- Recent notifications.

### Agent dashboard

Potential data:

- Assigned property count.
- Published property count.
- New inquiries.
- Active conversations.
- Unread messages.
- Recent activity.

### Administrator dashboard

Potential data:

- Total users.
- Total buyers.
- Total agents.
- Total properties.
- Published properties.
- Unavailable properties.
- Inquiry statistics.
- Recent platform activity.

Exact dashboard metrics will be finalized during Stage 8.

# 15. Administrative APIs

| Method | Endpoint | Access | Description |
|---|---|---|---|
| GET | `/admin/overview` | Admin | Platform overview |
| GET | `/admin/users` | Admin | User management |
| GET | `/admin/agents` | Admin | Agent management |
| GET | `/admin/properties` | Admin | Property management |
| GET | `/admin/inquiries` | Admin | Inquiry management |
| GET | `/admin/audit-logs` | Admin | Audit history |

Administrative operations must be explicitly authorized.

Administrative access to private conversation content is not assumed by this specification. Any such access must be separately approved and audited.

# 16. Standard Response Structure

Successful responses should follow a consistent structure.

```json
{
  "success": true,
  "message": "Operation completed successfully",
  "data": {}
}
```

For paginated responses:

```json
{
  "success": true,
  "data": [],
  "meta": {
    "page": 0,
    "size": 12,
    "totalElements": 100,
    "totalPages": 9,
    "hasNext": true,
    "hasPrevious": false
  }
}
```

The `message` field is optional for successful retrieval operations.

# 17. Error Handling

All API errors must follow a consistent structure.

```json
{
  "success": false,
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "The supplied data is invalid",
    "details": [
      {
        "field": "email",
        "message": "A valid email address is required"
      }
    ]
  },
  "timestamp": "2026-11-06T10:30:00Z",
  "path": "/api/v1/auth/register"
}
```

## 17.1 HTTP Status Codes

| Code | Meaning | Usage |
|---|---|---|
| 200 | OK | Successful retrieval or update |
| 201 | Created | Resource successfully created |
| 204 | No Content | Successful operation without response body |
| 400 | Bad Request | Invalid request |
| 401 | Unauthorized | Missing or invalid authentication |
| 403 | Forbidden | Authenticated user lacks permission |
| 404 | Not Found | Resource does not exist or is not accessible |
| 409 | Conflict | Duplicate or conflicting operation |
| 413 | Payload Too Large | Upload exceeds permitted size |
| 415 | Unsupported Media Type | Unsupported upload format |
| 422 | Unprocessable Entity | Valid request structure but invalid business state |
| 429 | Too Many Requests | Rate limit exceeded |
| 500 | Internal Server Error | Unexpected server error |
| 503 | Service Unavailable | Required service temporarily unavailable |

The API must not expose internal stack traces, SQL errors, database credentials, or sensitive implementation details.

Stage 6 Favorites and Inquiries use this standard envelope with `400` for invalid requests, `401` for unauthenticated requests, `403` for role-level denial, `404` for both missing and unauthorized resources, `409` for business-state conflicts, and `429` for rate-limit exceeded. Unauthorized and nonexistent resources are indistinguishable to callers. `429` responses include `Retry-After` reflecting every blocking limit, and that header is exposed to approved browser origins through the implemented CORS configuration. Private error/denial responses use `Cache-Control: no-store` under the Stage 6.2 security prerequisites recorded in `docs/STAGE_6_DECISION_REGISTER.md` §9.

# 18. Validation Requirements

The backend must validate all incoming requests.

Examples:

| Field | Validation |
|---|---|
| Email | Valid format and unique |
| Password | Meets approved security policy |
| First name | Required and length-limited |
| Property title | Required and length-limited |
| Property description | Required |
| Price | Positive numeric value |
| Currency | Valid supported currency code |
| Category | Must reference an existing category |
| Subtype | Must belong to selected category |
| Bedrooms | Non-negative integer |
| Bathrooms | Non-negative integer |
| Latitude | Between -90 and 90 |
| Longitude | Between -180 and 180 |
| Inquiry message | Required and length-limited |
| Chat message | Non-empty and length-limited |

Validation must be implemented in the backend even if equivalent frontend validation exists.

# 19. Pagination, Filtering and Sorting

List endpoints should support consistent pagination.

Standard parameters:

```text
?page=0&size=12&sort=createdAt,desc
```

Rules:

- Page numbering starts at zero.
- Default page size: 12 for property discovery.
- Default page size: 20 for administrative and management lists.
- Maximum page size: 100 unless a specific endpoint requires a stricter limit.
- Invalid sort fields must be rejected.
- Sorting must use an explicit allowlist.
- Large message histories must use the cursor contract in §11.2; they are the explicit exception to zero-based offset pagination.

## 19.1 Stage 6 Endpoint-Specific Contracts

Owner-approved Stage 6 values refine the generic framework for favorites and inquiries:

| Surface | Default page | Default size | Maximum size | Default sort | Allowed sort | Additional filter |
|---|---:|---:|---:|---|---|---|
| `/favorites` | 0 | 12 | 100 | `newest` | `newest`, `oldest` | none |
| Inquiry lists | 0 | 20 | 100 | `newest` | `newest`, `oldest` | `status` |

Every list uses `createdAt` with a deterministic UUID secondary ordering. Invalid page, size, sort, or status values return `400`. Exact route coverage and response DTOs are in §§9–10 and `docs/STAGE_6_DECISION_REGISTER.md` §5.

# 20. WebSocket and STOMP Architecture

REST handles normal resource operations. WebSocket/STOMP handles real-time messaging.

## 20.1 Connection Endpoint

```text
/ws
```

The endpoint is hosted by the Spring Boot backend. The approved design uses native WebSocket (no SockJS fallback) at `/ws`, with the same server-side session as REST, an exact `Origin` allowlist, and no query-string token (Security Architecture SD-01, SD-34, SD-48). The handshake is a `GET` and needs no CSRF token itself; the STOMP `CONNECT` frame must carry the session-bound token from the existing `X-CSRF-TOKEN` flow in a native header. Missing, invalid, stale or mismatched tokens and disallowed origins are rejected before subscriptions. Exact production origins and the hostnames in SD-48 remain deployment configuration (P-12). This is a design contract; Stage 7.1 does not claim handshake or STOMP implementation.

## 20.2 Client Subscription Destinations

| Destination | Purpose | Authorization |
|---|---|---|
| `/user/queue/messages` | Receive private messages | Authenticated user |
| `/user/queue/notifications` | Receive private notifications | Authenticated user |
| `/user/queue/errors` | Receive messaging errors | Authenticated user |

Clients must not subscribe directly to arbitrary conversation destinations without server-side authorization.

## 20.3 Client Message Destinations

| Destination | Purpose |
|---|---|
| `/app/chat.send` | Send a message |

## 20.4 Send Message Payload

Destination:

```text
/app/chat.send
```

Payload:

```json
{
  "conversationId": "uuid",
  "content": "I would like to schedule a visit.",
  "idempotencyKey": "uuid"
}
```

`idempotencyKey` is mandatory and must be a client-generated UUID reused for retries of the same logical message. The same key with the same content returns the existing persisted message; the same key with different content returns a safe conflict. The sender ID is derived from the authenticated connection; the client is never trusted to supply its own identity. Unknown fields, including `senderId`, `id`, `sentAt` and audit fields, are rejected or ignored and are never persisted. A native STOMP `receipt` header, if supplied, is transport metadata only and is not the persistence acknowledgement (P-05/P-08).

## 20.5 Incoming Message Event

Destination:

```text
/user/queue/messages
```

Authorized participants receive `MESSAGE_CREATED` frames:

```json
{
  "type": "MESSAGE_CREATED",
  "id": "uuid",
  "conversationId": "uuid",
  "senderId": "uuid",
  "messageType": "TEXT",
  "content": "I would like to schedule a visit.",
  "idempotencyKey": "uuid",
  "sentAt": "2026-11-06T10:30:00Z"
}
```

After commit, the sender alone also receives `PERSISTENCE_ACK` on the same user queue:

```json
{
  "type": "PERSISTENCE_ACK",
  "id": "uuid",
  "conversationId": "uuid",
  "idempotencyKey": "uuid",
  "sentAt": "2026-11-06T10:30:00Z"
}
```

No recipient-delivery or read-receipt frame is emitted. Native STOMP `RECEIPT` is not authoritative for application sends (F-7.1-01).

## 20.6 Read-State Updates

Read state is updated only through the REST endpoint:

```text
PATCH /conversations/{conversationId}/read-state
```

The `/app/chat.read` STOMP destination is not part of the contract and is not a read-receipt channel. Read state advances only for messages the participant actually viewed, is transactionally monotonic, and excludes the participant's own messages from that participant's unread count (P-07/F-7.1-06).

## 20.7 Messaging Rules

1. Authenticate WebSocket connections and require a valid session-bound CSRF token on STOMP CONNECT.
2. Validate authoritative session existence/expiry and account status at CONNECT, heartbeat and each protected frame; never rely solely on session-destruction events.
3. Validate conversation participation for every send operation and again immediately before every outbound dispatch.
4. Reject missing or reused-invalid `idempotencyKey` values according to the P-05 conflict rules; persist with the V19 unique constraint as authority.
5. Persist messages before broadcasting them; broadcast only to authorized participants derived from the conversation.
6. Send the application-level `PERSISTENCE_ACK` to the sender only after commit; emit no recipient or read receipts.
7. Retrieve history latest-first by `(sentAt DESC, id DESC)` with a deterministic cursor; render chronologically.
8. Handle disconnected users through REST cursor refresh and ID deduplication; never automatically replay an ambiguous mutation.
9. Advance read state only for viewed messages, transactionally and monotonically; expose only the requester's `unreadCount`.
10. Use server-side rate limits and message-size limits.

# 21. API Security Requirements

The backend must enforce:

- Authentication on protected endpoints.
- Role-based authorization.
- Resource ownership checks.
- Property assignment checks.
- Input validation.
- Rate limiting for sensitive endpoints.
- Secure password handling.
- Secure session or token management.
- CSRF protection where applicable to the selected authentication mechanism.
- CORS restrictions for approved frontend origins.
- Secure WebSocket authentication and authorization.
- Safe error responses.
- Audit logging for sensitive administrative operations.

The frontend must never contain database credentials, image-storage secrets, or backend private keys.

# 22. API-to-FRD Traceability

| API Group | Related FRD Requirements |
|---|---|
| Authentication | FR-AUTH-001 to FR-AUTH-009 |
| User Management | FR-USER-001 to FR-USER-004 |
| Property Management | FR-PROP-001 to FR-PROP-010 |
| Property Discovery | FR-DISC-001 to FR-DISC-010 |
| Favorites | FR-FAV-001 to FR-FAV-005 |
| Inquiries | FR-INQ-001 to FR-INQ-006 |
| Conversations and Messages | FR-CHAT-001 to FR-CHAT-014 |
| Notifications | FR-NOTIF-001 to FR-NOTIF-006 |
| Dashboards | FR-DASH-001 to FR-DASH-004 |
| Audit Logs | FR-AUDIT-001 to FR-AUDIT-004 |
| Validation and Error Handling | FR-VAL-001 to FR-VAL-005 |

Every functional requirement must map to an implemented endpoint, event, backend operation, or explicitly documented non-API behavior before its feature is considered complete.

# 23. Deferred API Decisions

The following details require final decisions during their corresponding implementation stages. Where a row records an already-approved design, the remaining item is implementation, verification, or deployment rather than an open design decision.

| Area | Deferred Decision |
|---|---|
| Authentication | Server-side session with an opaque host-only `HttpOnly` cookie and a session-bound CSRF token in the `X-CSRF-TOKEN` header (SD-01, SD-05, SD-48, SD-53; approved as designs). The SD-53 recheck against Spring Security 7.1.2 is recorded in `DEVELOPMENT_SETUP.md` §12; implementation and deployment hostnames remain prerequisites. |
| Registration | Email verification is deferred (SD-16); public buyer registration returns `ACTIVE`. Agent creation returns `INACTIVE` and uses a purpose-scoped `ACCOUNT_ACTIVATION` token through `POST /auth/reset-password` (SD-12; approved as a design; email delivery prerequisite) |
| Password Reset | 256-bit single-use token, SHA-256 hash at rest, 30-minute expiry, all sessions revoked (SD-11; approved as a design). The email provider, sender domain, and token-table migration remain implementation/deployment prerequisites |
| Authorization | Detailed role and resource permission matrix is defined in `SECURITY_ARCHITECTURE.md` §10 |
| Property Lifecycle | Final status transitions |
| Property Updates | Which property fields agents may edit |
| Property Assignment | Every property is created with one primary agent; there is no unassigned state |
| Property Images | Upload limits and supported formats. Image deletion uses the approved durable outbox: metadata and cleanup task commit together; provider cleanup is eventual and retried by a backend worker (project-owner-approved ADR: `STAGE_4_IMAGE_DELETION_OUTBOX_ADR.md`; register index in `STAGE_4_DECISION_REGISTER.md` §10). |
| Search | Full-text search and geographic search implementation |
| Favorites | Stage 6 mutation, retention, pagination, and batch-status decisions are approved in §9 and `docs/STAGE_6_DECISION_REGISTER.md` §5.1; they were implemented and verified in Stage 6.7. |
| Inquiries | Stage 6 status transitions, duplicate policy, actor authority, pagination, and DTO rules are approved in §10.2 and `docs/STAGE_6_DECISION_REGISTER.md` §5.2; they were implemented and verified in Stage 6.7. Retention duration remains deferred. |
| Conversations | Resolved by P-02/P-03: permanent uniqueness and immutable original agent are retained; deactivation preserves history, disables protected messaging and introduces no fallback or transfer |
| Chat | Resolved as designs in `docs/STAGE_7_DECISION_REGISTER.md`: mandatory idempotency key, V19 constraint/index scope, cursor history, monotonic own read state and `unreadCount`, sender-only persistence acknowledgement, REST reconnection, STOMP `CONNECT` CSRF, publication eligibility, and Stage 7 status deferral. Editing, deletion, attachments, receipts, status mutation, exact client-version values and exact hostnames remain deferred |
| Chat Pagination | Resolved by P-06/F-7.1-04: latest-first deterministic cursor retrieval with chronological rendering |
| Notifications | Email delivery and notification preferences |
| Dashboard | Final metrics and aggregation requirements |
| Audit | Retention period and exact audited operations |
| API Rate Limits | Stage 6 favorites/inquiry route limits are approved in `docs/SECURITY_ARCHITECTURE.md` §11.9 and `docs/STAGE_6_DECISION_REGISTER.md` §5.4; they were implemented and verified in Stage 6.7. Other endpoint-specific limits remain deferred. |
| API Documentation | OpenAPI/Swagger configuration |

Remaining decisions must be approved before implementing the corresponding functionality; the Stage 6 favorites, inquiry, and route-limit rows above are closed by the Stage 6.7 verification evidence.

# 24. Final Design Summary

This API specification establishes the initial communication contract for EstateVista V2.

It covers:

- Authentication and user management.
- Agent management.
- Property creation and management.
- Public property discovery.
- Property image operations.
- Favorites.
- Buyer inquiries.
- Property-specific conversations.
- Real-time messaging.
- Notifications.
- Role-specific dashboards.
- Administrative operations.
- Validation and error handling.
- Security and authorization.

The API follows REST conventions for standard operations and WebSocket/STOMP for real-time chat.

The specification is designed to support independent frontend and backend development while maintaining a consistent contract.

# 25. Final Technical Review & Approved Clarifications

**Review Status:** APPROVED  
**Stage:** 1.5 — API Specification  
**Project:** EstateVista V2

The API specification has been reviewed for consistency, endpoint coverage, authorization, database compatibility, and alignment with the approved functional requirements.

The overall API structure is accepted as the implementation baseline, subject to the following clarifications.

## 25.1 Required API Clarifications

### A. Conversation Creation and Message Sending

Conversation creation and message sending must be separate operations.

`POST /properties/{propertyId}/conversations` must:

- Create a conversation if one does not exist.
- Return the existing conversation if one already exists.
- Never create duplicate conversations.
- Never resend an initial message when returning an existing conversation.

The `initialMessage` field has been removed from the conversation creation request and must not be reintroduced (API §11.1).

The first message, like subsequent messages, must be sent through the approved messaging mechanism.

### B. Read-State Handling

The REST endpoint is the only read-state mutation:

`PATCH /conversations/{conversationId}/read-state`

The `/app/chat.read` STOMP destination is not part of the approved contract and is not a read-receipt channel; it must not be introduced by assumption. Read state advances only for messages the participant actually viewed, within the same conversation, and the transactionally monotonic highest valid viewed position wins under concurrency. Only the requester's own `unreadCount` is returned in conversation list and detail responses (P-07/P-10, F-7.1-06). No read-receipt event or UI is approved.

### C. Property Archiving

`DELETE /properties/{propertyId}` represents logical archival, not physical deletion.

The operation must:

- Archive the property.
- Preserve its inquiries.
- Preserve its conversations.
- Preserve its messages.
- Preserve historical audit records.
- Prevent normal public discovery from returning it.

The backend must not cascade-delete historical business records.

### D. Featured Properties

The specification includes:

`GET /properties/featured`

The property data model must eventually support a featured-property designation.

Add a `is_featured BOOLEAN NOT NULL DEFAULT FALSE` field to the properties table through a future Flyway migration.

Only authorized administrators should be able to change featured status.

This is a schema clarification, not a reason to reopen the approved database design.

**Stage 5.2 verification:** no `is_featured` column or Flyway V18 exists. `GET /properties/featured` remains unmapped and is denied with `401` for anonymous callers and `403` for authenticated callers of every role.

### E. WebSocket Authorization

The WebSocket implementation must authenticate connections and authorize every messaging operation.

In particular:

- A new conversation is created only for a `PUBLISHED` property with an active buyer and an active current primary agent; an existing permanent conversation is retrieved by an authorized active original participant regardless of later property status or assignment (P-13, F-7.1-07).
- Clients cannot choose their sender identity, buyer identity, agent assignment, conversation snapshot or message identity.
- Clients cannot subscribe to arbitrary private conversation destinations.
- Only the original buyer and originally assigned agent can access a conversation; administrators are denied, and reassignment neither transfers history nor creates a second conversation for the same buyer/property (P-02).
- Conversation status mutation is deferred beyond Stage 7 by P-04; no `PATCH /status` route is implemented or mapped in Stage 7.
- Every `/app/chat.send` frame carries a mandatory client-generated UUID `idempotencyKey`. The V19 unique key `(conversation_id, sender_id, idempotency_key)` is the duplicate authority; the same key with different content returns a safe conflict (P-05).
- Message persistence must succeed before broadcasting, and participant/session/account authorization must be rechecked immediately before every outbound dispatch (F-7.1-03).
- After commit, a sender-only application `PERSISTENCE_ACK` correlated by idempotency key is emitted. Native STOMP `RECEIPT` is non-authoritative, and no recipient, delivery or read-receipt frame is emitted (P-08, F-7.1-01).
- Read state is REST-only and monotonic; only the requester's own `unreadCount` is returned in conversation list and detail responses (P-07/P-10).
- Reconnection reuses participant-scoped REST cursor history and deduplicates by server message ID; no separate sync endpoint is approved (P-09).

The exact authentication handshake is defined as a server-side session with an exact WebSocket `Origin` allowlist and no query-string token. The STOMP `CONNECT` frame must carry the session-bound token from the existing `X-CSRF-TOKEN` flow in a native header; missing, invalid, stale or mismatched tokens and disallowed origins are rejected before subscriptions (Security Architecture SD-01, SD-34, SD-40, SD-48; P-12/F-7.1-08). Exact production hostnames remain deployment-verification inputs.

### F. Agent and Inquiry Access

The agent inquiry endpoint must return only inquiries the agent is authorized to access.

Historical inquiry assignment must not automatically change when a property's current agent changes.

The same historical assignment principle applies to conversations.

### G. Property API Authorization

The following baseline must apply:

- Administrators create properties and assign agents.
- Assigned agents manage their authorized properties.
- Agents cannot independently reassign properties.
- Public users can only retrieve properties eligible for public display.
- Administrative operations must not rely on frontend restrictions.

### H. Response and Pagination Consistency

All paginated collection endpoints must follow the documented pagination structure.

All API errors must follow the standard error response format.

The backend should use consistent DTOs and response structures rather than exposing JPA entities directly.

### I. Public Property Visibility

Only `PUBLISHED` properties and their publicly servable images may be returned to unauthenticated callers. `DRAFT`, `UNAVAILABLE`, `SOLD`, `RENTED`, and `ARCHIVED` records must be excluded from public discovery, featured, recent, related, detail, and image responses. A request for a non-published property returns `404` to an unauthenticated caller rather than disclosing that the record exists.

Authorized participants may retrieve historical references through authenticated surfaces (favorites, inquiries, conversations, and agent/admin management). The rule adds no status and does not change the property lifecycle. It is recorded as the approved C-10/D-08 determination in `SECURITY_ARCHITECTURE.md` §18.7.

## 25.2 Approved API Design Decisions

| Area | Final Decision |
|---|---|
| API style | REST |
| API versioning | `/api/v1/` |
| Data exchange | JSON |
| Frontend communication | Fetch API |
| Backend | Spring Boot |
| Standard operations | REST |
| Real-time messaging | WebSocket + STOMP over native `/ws`; no SockJS fallback |
| Conversation initiation | Explicit buyer action; new creation requires a `PUBLISHED` property plus active buyer and active current agent; no `initialMessage` |
| Conversation uniqueness | Permanent `UNIQUE(buyer_id, property_id)` with immutable original `assigned_agent_id`; no transfer on reassignment |
| Message history pagination | Latest-first cursor by `(sent_at DESC, id DESC)` with chronological client rendering; no new sequence column |
| Message identity and duplicates | Server-generated message ID/timestamp plus mandatory client UUID `idempotencyKey` and V19 unique constraint |
| Send acknowledgement | Sender-only application `PERSISTENCE_ACK` after commit; native STOMP `RECEIPT` is non-authoritative |
| WebSocket CSRF | Session-bound token from the `X-CSRF-TOKEN` flow in a STOMP `CONNECT` native header with exact `Origin` validation |
| Read state | REST-only monotonic advancement for viewed messages; participant-specific `unreadCount`; no read or delivery receipts |
| Conversation status mutation | Deferred beyond Stage 7 by P-04; no `PATCH /status` implementation in Stage 7 |
| Identifiers | UUID |
| Pagination | Zero-based for standard collections; cursor for message history as specified in §11.2 |
| Default property page size | 12 |
| Default management page size | 20 |
| Maximum general page size | 100 |
| Property-specific chat | One conversation per buyer-property |
| Message persistence | PostgreSQL |
| Image storage | External object storage |
| Authorization | Backend-enforced |
| API error structure | Standardized JSON |
| API documentation | OpenAPI/Swagger decision deferred |
| Authentication | Server-side session with an opaque host-only `HttpOnly` cookie (SD-01) |
| CSRF | Session-bound synchronizer token in the `X-CSRF-TOKEN` request header, exposed through CORS (SD-05, SD-53) |
| Agent activation | `INACTIVE` creation plus purpose-scoped `ACCOUNT_ACTIVATION` token redeemed through `POST /auth/reset-password` (SD-12) |
| Public property visibility | `PUBLISHED`-only public retrieval (C-10/D-08) |

## 25.3 Implementation Restrictions

1. Do not introduce React or another frontend framework.
2. Do not expose backend secrets in frontend code.
3. Do not trust client-supplied user identities.
4. Do not trust client-supplied agent assignments.
5. Do not allow buyers to access other buyers' private records.
6. Do not allow agents to access unauthorized properties or conversations.
7. Do not broadcast messages before successful persistence.
8. Do not physically delete historical conversations or messages through property archival.
9. Do not expose JPA entities directly as public API responses.
10. Do not introduce endpoints that conflict with the approved authorization model without documenting and reviewing the change.

## 25.4 Deferred Decisions

All decisions listed in Section 23 remain deferred unless explicitly resolved by this specification, the Stage 6 decision register, or the Stage 7 decision register.

Session authentication, the CSRF header contract, the password-reset token mechanics, agent activation, and public property visibility are resolved as designs (Security Architecture SD-01, SD-05, SD-11, SD-12, SD-53, and C-10/D-08); their implementation, email-provider, token-migration, hostname, and test prerequisites remain. The Spring Session migration exists, and the SD-53 recheck against Spring Security 7.1.2 is recorded in `DEVELOPMENT_SETUP.md` §12. Stage 6 favorites and inquiry contracts are resolved by the owner decisions in `docs/STAGE_6_DECISION_REGISTER.md` and were implemented and verified in Stage 6.7; this specification is not itself the test evidence.

Stage 7.1 chat contracts are resolved by `docs/STAGE_7_DECISION_REGISTER.md`: V19 idempotency/index scope, latest-first cursor history, monotonic own read state and participant `unreadCount`, sender-only persistence acknowledgement, REST reconnection, STOMP `CONNECT` CSRF, creation eligibility versus historical retrieval, no status mutation in Stage 7, and the separate property `Message agent` action. Remaining deferred items are conversation status mutation (P-04/D-10), message editing/deletion/attachments/read and delivery receipts, a new V19 ordering-sequence column (D-11), exact STOMP-client version/license/integrity verification values (P-11), exact production hostnames (P-12), upload limits, notification delivery, and Stage 8/9 obligations. These remain future gates and are not Stage 7.1 implementation approvals.

## 25.5 Final Approval

The API specification is accepted as the baseline for EstateVista V2 implementation.

The clarifications above must be reflected in the relevant API documentation and implementation tasks.

No major API redesign is required.