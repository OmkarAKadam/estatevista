# ESTATEVISTA — API SPECIFICATION

**Project:** EstateVista V2  
**Stage:** 1.5 — API Specification  
**API Style:** REST  
**API Version:** v1  
**Backend:** Java + Spring Boot  
**Database:** PostgreSQL  
**Status:** Proposed baseline — §25 records APPROVED clarifications; headers reconciled during Stage 1.6; Stage 1.9 audit corrections applied

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
| GET | `/agents/me/conversations` | Agent | Retrieve assigned conversations |

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

**Stage 5.2 verified implementation notes.** Public search enforces the approved §11.10 keyword and `amenityIds` caps and returns `VALIDATION_ERROR` for violations. Invalid pages, sizes, sort values, ranges, bedroom filters, enum values, UUIDs, and numeric values use the standard error envelope. Blank string parameters are ignored, while blank numeric parameters fail Spring type conversion with `BAD_REQUEST`. Blank `sort` selects `newest`; sort names are case-sensitive; `keyword` values remain parameterized SQL `LIKE` patterns, so wildcard characters retain their existing behavior. Unknown query parameters are ignored. Public search, detail, related, recent, and reference endpoints are subject to the §11.9 HTTP rate limits and return `429 TOO_MANY_REQUESTS` with `Retry-After`.

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

| Method | Endpoint | Access | Description |
|---|---|---|---|
| POST | `/favorites/{propertyId}` | Buyer | Add favorite |
| DELETE | `/favorites/{propertyId}` | Buyer | Remove favorite |
| GET | `/favorites` | Buyer | Retrieve own favorites |
| GET | `/favorites/{propertyId}/status` | Buyer | Check favorite status |

## 9.1 Add Favorite

`POST /favorites/{propertyId}`

Success: `201 Created`

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

The backend must prevent duplicate buyer-property relationships.

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

# 11. Conversation APIs

Conversations are property-specific and persistent.

| Method | Endpoint | Access | Description |
|---|---|---|---|
| POST | `/properties/{propertyId}/conversations` | Buyer | Create or retrieve conversation |
| GET | `/conversations` | Buyer / Agent | Retrieve accessible conversations |
| GET | `/conversations/{conversationId}` | Participants | Retrieve conversation details |
| GET | `/conversations/{conversationId}/messages` | Participants | Retrieve message history |
| PATCH | `/conversations/{conversationId}/read-state` | Participants | Update read progress |
| PATCH | `/conversations/{conversationId}/status` | Original assigned agent | Update conversation status |

Administrators do not have conversation access or conversation-status authority in the MVP. They may view inquiry metadata and the inquiry message, but not conversations or message transcripts (Database Design §23.1.D; API §25.1.E; UI D-16).

## 11.1 Create or Retrieve Conversation

`POST /properties/{propertyId}/conversations`

Request body: none.

The backend must:

1. Identify the authenticated buyer.
2. Retrieve the property.
3. Determine its current assigned agent.
4. Check whether a conversation already exists for the buyer-property pair.
5. Return the existing conversation if found.
6. Otherwise, create a conversation with the current agent assignment.
7. Create participant read-state records.

The first message, like every subsequent message, is sent through the approved messaging mechanism (`/app/chat.send`). Conversation creation does not accept or persist an `initialMessage` (API §25.1.A).

Success: `200 OK` for an existing conversation or `201 Created` for a new conversation.

Response:

```json
{
  "success": true,
  "data": {
    "conversationId": "uuid",
    "propertyId": "uuid",
    "buyerId": "uuid",
    "assignedAgentId": "uuid",
    "createdAt": "2026-11-06T10:30:00Z"
  }
}
```

The database unique constraint on `(buyer_id, property_id)` must handle concurrent creation attempts safely.

## 11.2 Retrieve Messages

`GET /conversations/{conversationId}/messages?page=0&size=30`

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
      "sentAt": "2026-11-06T10:30:00Z"
    }
  ],
  "meta": {
    "page": 0,
    "size": 30,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

The exact message pagination direction and ordering strategy will be finalized during chat implementation.

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
- Large message histories must use pagination.

# 20. WebSocket and STOMP Architecture

REST handles normal resource operations. WebSocket/STOMP handles real-time messaging.

## 20.1 Connection Endpoint

```text
/ws
```

The endpoint is hosted by the Spring Boot backend. The approved design uses native WebSocket (no SockJS fallback) at `/ws`, with the same server-side session as REST, an exact `Origin` allowlist, and no query-string token (Security Architecture SD-01, SD-34, SD-48). Exact production origins and the hostnames in SD-48 remain deployment configuration. The handshake authentication and STOMP authorization are not implemented in Stage 1.8.

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
  "content": "I would like to schedule a visit."
}
```

The sender ID must be derived from the authenticated connection. The client must not be trusted to supply its own sender identity.

## 20.5 Incoming Message Event

Destination:

```text
/user/queue/messages
```

Payload:

```json
{
  "id": "uuid",
  "conversationId": "uuid",
  "senderId": "uuid",
  "messageType": "TEXT",
  "content": "I would like to schedule a visit.",
  "sentAt": "2026-11-06T10:30:00Z"
}
```

## 20.6 Read-State Updates

Read state is updated only through the REST endpoint:

```text
PATCH /conversations/{conversationId}/read-state
```

The `/app/chat.read` STOMP destination is not part of the initial contract. It may be introduced later only if real-time read-receipt behaviour requires it and the API change is approved (API §25.1.B).

## 20.7 Messaging Rules

1. Authenticate WebSocket connections.
2. Validate conversation participation for every send operation.
3. Persist messages before broadcasting them.
4. Broadcast only to authorized participants.
5. Maintain chronological message history.
6. Handle disconnected users through persistent message storage.
7. Update unread counts and notification records.
8. Never trust conversation IDs or sender IDs without authorization checks.
9. Prevent duplicate message processing where practical.
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
| Inquiries | Final workflow and status transitions |
| Conversations | Handling conversations when an agent is deactivated |
| Chat | Editing, deletion, attachments, and delivery acknowledgements |
| Chat Pagination | Cursor-based versus page-based history retrieval |
| Notifications | Email delivery and notification preferences |
| Dashboard | Final metrics and aggregation requirements |
| Audit | Retention period and exact audited operations |
| API Rate Limits | Endpoint-specific limits |
| API Documentation | OpenAPI/Swagger configuration |

These decisions must be approved before implementing the corresponding functionality.

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

The specification currently defines read-state updates through both REST and STOMP.

For the initial implementation, use:

`PATCH /conversations/{conversationId}/read-state`

as the authoritative read-state update operation.

The `/app/chat.read` STOMP destination is not required for the initial implementation. It may be introduced later if real-time read-receipt behavior requires it.

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

- Clients cannot choose their sender identity.
- Clients cannot subscribe to arbitrary private conversation destinations.
- Only the buyer and originally assigned agent can access a conversation.
- Message persistence must succeed before broadcasting.
- Reconnection must allow retrieval of missed messages from PostgreSQL.

The exact authentication handshake is defined as a server-side session with an exact WebSocket `Origin` allowlist and no query-string token (Security Architecture SD-01, SD-34, SD-48, approved as designs).

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
| Real-time messaging | WebSocket + STOMP |
| Identifiers | UUID |
| Pagination | Zero-based |
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

All decisions listed in Section 23 remain deferred unless explicitly resolved by this review.

Session authentication, the CSRF header contract, the password-reset token mechanics, agent activation, and public property visibility are resolved as designs (Security Architecture SD-01, SD-05, SD-11, SD-12, SD-53, and C-10/D-08); their implementation, email-provider, token-migration, hostname, and test prerequisites remain. The Spring Session migration exists, and the SD-53 recheck against Spring Security 7.1.2 is recorded in `DEVELOPMENT_SETUP.md` §12. Detailed status transitions, upload limits, notification delivery, and chat enhancements must be finalized before their respective implementations.

## 25.5 Final Approval

The API specification is accepted as the baseline for EstateVista V2 implementation.

The clarifications above must be reflected in the relevant API documentation and implementation tasks.

No major API redesign is required.