# EstateVista V2 — Product Requirements Document

**Version:** 1.0  
**Status:** Draft — product decisions PD-01–PD-11 approved; document approval metadata not recorded  
**Project:** EstateVista V2  
**Project Type:** Full-Stack Real Estate Platform

---

## 1. Product Overview

EstateVista is a full-stack real estate platform designed to connect property seekers with property agents through a centralized property discovery and communication system.

The platform supports both residential and commercial properties. Visitors can discover properties, search using relevant filters, and view detailed property information. Registered buyers can save properties, submit inquiries, and communicate directly with assigned agents through property-specific conversations.

Property agents can manage their assigned listings and respond to buyer inquiries. Administrators oversee users, agents, properties, assignments, and platform activity.

EstateVista V2 will evolve the existing static EstateVista frontend into a functional full-stack application while retaining Vanilla JavaScript as the frontend foundation.

## 2. Product Vision

Build a reliable, accessible, and user-friendly real estate platform where:

- Buyers can discover suitable properties.
- Agents can manage listings and communicate with interested buyers.
- Administrators can oversee the entire platform.
- Property-related conversations remain organized and accessible.
- Property and communication data are stored persistently.

## 3. Product Objectives

1. Convert the existing static property discovery website into a full-stack platform.
2. Support residential and commercial property listings.
3. Provide secure authentication and role-based access.
4. Enable agents to manage their assigned properties.
5. Allow buyers to maintain favourites and submit inquiries.
6. Implement persistent, real-time property-specific messaging.
7. Provide role-specific dashboards.
8. Maintain reliable data integrity, security, and usability.

## 4. Target Users

### 4.1 Visitor

An unauthenticated user who wants to explore available properties.

### 4.2 Buyer

A registered user interested in purchasing, renting, or exploring properties.

### 4.3 Property Agent

A registered platform user responsible for managing assigned property listings and communicating with interested buyers.

### 4.4 Administrator

A platform administrator responsible for managing users, agents, properties, assignments, and platform-level operations.

## 5. Property Categories

EstateVista supports two primary property categories.

### 5.1 Residential Properties

Examples:
- Apartments and flats
- Villas
- Independent houses
- Residential plots
- Duplexes
- Penthouses

Relevant information may include:
- Bedrooms
- Bathrooms
- Carpet area
- Built-up area
- Furnishing status
- Parking availability
- Floor information
- Amenities

### 5.2 Commercial Properties

Examples:
- Office spaces
- Retail shops
- Showrooms
- Warehouses
- Industrial properties
- Commercial plots
- Co-working spaces

Relevant information may include:
- Commercial property subtype
- Total area
- Carpet area
- Floor information
- Parking availability
- Furnishing status
- Power backup
- Loading/unloading facilities
- Business amenities

Property-specific fields will be finalized during database and functional design.

## 6. Core Functional Modules

### 6.1 Public Property Discovery

Visitors and authenticated users can:
- Browse property listings.
- Search by keywords.
- Filter by property category and subtype.
- Filter by location.
- Filter by price range.
- Filter by sale or rental availability.
- Sort properties.
- Navigate through paginated results.
- View detailed property information.

Property details should display relevant images, pricing, specifications, descriptions, amenities, location information, and agent contact options.

### 6.2 Authentication and User Management

The platform will support:
- Buyer registration.
- Buyer login and logout.
- Agent login and logout.
- Administrator login and logout.
- Role-based access control.
- User profile management.
- Account status management.

Agent and administrator accounts will be created and managed by administrators rather than through unrestricted public registration.

### 6.3 Property Management

Agents can:
- Update assigned property information.
- Upload and manage property images.
- Change property availability status.
- View inquiries associated with their properties.

Administrators can:
- Create, update, and delete property records.
- Assign properties to agents.
- Reassign properties.
- View and manage all platform listings.

Every property will have one primary assigned agent. Only administrators create properties and assign that agent; agents do not create or reassign properties (PD-03, PD-04, PD-05).

### 6.4 Favorites

Authenticated buyers can:
- Add properties to favourites.
- Remove properties from favourites.
- View their saved properties.
- Open saved property details.

Favorites must be associated with the buyer's account and persist across sessions.

**Stage 6 scope note:** Owner-approved favorite behavior is recorded in `docs/STAGE_6_DECISION_REGISTER.md` §3 Decisions 3–5 and §5.1. New favorites require a `PUBLISHED` property; existing favorites are retained through non-public property states with a generic unavailable presentation, remain removable, and are listed with approved pagination and batched card-state requests.

### 6.5 Inquiry Management

Buyers can submit inquiries regarding specific properties.

The platform will:
- Associate each inquiry with a buyer and property.
- Make inquiries accessible to the responsible agent.
- Allow agents to review and manage inquiries.
- Allow buyers to view their inquiry history.
- Maintain inquiry status and timestamps.

The exact inquiry status workflow will be defined in the functional requirements document.

**Stage 6 scope note:** The inquiry workflow is now approved for Stage 6 architecture in `docs/STAGE_6_DECISION_REGISTER.md` §3 Decisions 1, 2, and 6–9. Stage 6 provides buyer submission and read-only status history, historically assigned AGENT and ADMIN status management, one active inquiry per buyer/property, immutable assignment history, and no chat surface or inquiry↔conversation FK. This scoped approval does not approve the whole draft UI/UX specification.

### 6.6 Property-Specific Chat

The platform will provide real-time messaging between buyers and property agents.

Core requirements:
- Buyers can initiate conversations regarding properties.
- Conversations are associated with a specific buyer and property.
- Each conversation records an immutable snapshot of the original assigned agent at creation; that original agent remains the conversation participant even after the property is reassigned (P-02).
- Messages are persisted in the database.
- Participants can access previous messages.
- Conversations support timestamps and message history.
- Unread message indicators are supported.
- Users cannot access conversations in which they are not authorized participants.

A buyer contacting multiple properties will have separate conversations for each property.

**Conversation reassignment policy:**

Changing a property's primary assigned agent will not automatically transfer existing private conversations to the new agent.

Existing conversations retain their original participant relationship permanently: reassignment neither transfers a conversation to the newly assigned agent nor creates a second conversation for the same buyer and property with that agent. The limitation is that the buyer continues to see the original agent in that conversation, and no transfer or fallback-agent conversation is available; conversation history remains readable to the original participants (P-02, P-03).

Conversation history must remain available even when the associated property is no longer available.

### 6.7 Dashboards

**Buyer Dashboard**
- Profile information.
- Favourite properties.
- Submitted inquiries.
- Active conversations.
- Recent messages.

**Agent Dashboard**
- Assigned properties.
- Property management.
- Received inquiries.
- Buyer conversations.
- Unread messages.

**Administrator Dashboard**
- Platform overview.
- User management.
- Agent management.
- Property management.
- Agent assignments.
- Platform activity overview.

### 6.8 Notifications

The platform will support notifications for relevant events, including:
- New inquiries.
- New chat messages.
- Property assignment changes.
- Important property status changes.
- Account-related updates.

Notification delivery methods and preferences will be finalized during functional and technical design.

## 7. User Journeys

### 7.1 Visitor to Buyer

1. Visitor opens EstateVista.
2. Browses available properties.
3. Applies search and filters.
4. Opens a property details page.
5. Registers or logs in to access buyer features.
6. Saves a property or submits an inquiry.
7. Starts a conversation with the assigned agent.

### 7.2 Agent Workflow

1. Agent logs in.
2. Opens the agent dashboard.
3. Reviews assigned properties.
4. Updates assigned property listings.
5. Reviews buyer inquiries.
6. Responds to buyer conversations.
7. Monitors relevant notifications.

### 7.3 Administrator Workflow

1. Administrator logs in.
2. Reviews the platform dashboard.
3. Creates or manages agent accounts.
4. Manages buyer accounts when necessary.
5. Creates and manages property records.
6. Assigns properties to agents.
7. Monitors platform operations.

## 8. Business Rules

1. Every user must have an assigned platform role.
2. Public visitors do not require authentication to browse public listings.
3. Buyers can manage only their own favourites, inquiries, and conversations.
4. Agents can manage only properties assigned to them.
5. Administrators have platform-wide management permissions.
6. Each property has one primary assigned agent.
7. Each buyer-property combination can have only one conversation.
8. Conversation messages must be stored persistently.
9. Unauthorized users cannot access private conversations.
10. Existing conversations must not be silently transferred following agent reassignment.
11. Property availability changes must not automatically erase related inquiries or conversations.
12. Only authorized users can create or modify protected platform data.

## 9. MVP Scope

The first full-stack release will include:

| Module | MVP |
|---|---|
| Public property discovery | Yes |
| Residential properties | Yes |
| Commercial properties | Yes |
| Search and filtering | Yes |
| Property details | Yes |
| Buyer authentication | Yes |
| Agent authentication | Yes |
| Admin authentication | Yes |
| Role-based permissions | Yes |
| Property management | Yes |
| Agent assignment | Yes |
| Favorites | Yes |
| Inquiry management | Yes |
| Real-time chat | Yes |
| Persistent chat history | Yes |
| Buyer dashboard | Yes |
| Agent dashboard | Yes |
| Admin dashboard | Yes |
| Notifications | Yes |
| Responsive interface | Yes |

## 10. Out of Scope

The following features are excluded from the initial MVP:

- Online payment processing.
- Property booking and transaction processing.
- Mortgage and loan applications.
- AI-based property recommendations.
- AI property valuation.
- Native Android and iOS applications.
- Video calling.
- Voice calling.
- Public property reviews and ratings.
- Advanced CRM integrations.
- Automated legal documentation.
- Third-party property listing synchronization.

These may be considered for future versions.

## 11. Non-Functional Requirements

### Performance
- Public pages should load efficiently.
- Database queries should support pagination.
- Images should be optimized.
- Chat should provide responsive message delivery.

### Security
- Passwords must be securely hashed.
- Protected APIs must enforce authorization.
- User data must not be exposed to unauthorized users.
- Chat access must be restricted to authorized participants.
- Sensitive configuration must not be committed to Git.

### Reliability
- Property, inquiry, and message data must persist in PostgreSQL.
- Application failures should not silently corrupt stored data.
- The system should handle temporary WebSocket disconnections.

### Usability
- The interface must be responsive.
- Forms should provide useful validation feedback.
- Navigation should be consistent across the platform.
- Dashboards should present relevant information according to user roles.

### Maintainability
- Backend modules should have clear responsibilities.
- Frontend JavaScript should use modular organization.
- Database changes should be version-controlled.
- APIs should be documented.

## 12. Success Criteria

The MVP will be considered functionally successful when:

1. Visitors can browse residential and commercial properties.
2. Buyers can register and authenticate successfully.
3. Agents and administrators can authenticate securely.
4. Agents can manage assigned properties.
5. Administrators can manage users, agents, properties, and assignments.
6. Buyers can save and remove favourites.
7. Buyers can submit property inquiries.
8. Buyers and assigned agents can exchange real-time messages.
9. Chat history remains available after logout and reconnection.
10. Users cannot access unauthorized platform resources.
11. All three dashboards display role-appropriate information.
12. The platform operates successfully in its deployed environment.

## 13. Technical Constraints

The approved technology direction is:

| Layer | Technology |
|---|---|
| Frontend | HTML5, CSS3, Vanilla JavaScript |
| CSS Framework | Existing Bootstrap 5.3.3; Tailwind optional |
| Backend | Java, Spring Boot |
| Security | Spring Security |
| API | Spring MVC REST |
| Realtime Communication | WebSocket + STOMP |
| Database | PostgreSQL |
| ORM | Spring Data JPA / Hibernate |
| Database Migrations | Flyway |
| Frontend Hosting | Vercel |
| Backend Hosting | Render |
| Database Hosting | Neon |
| Image Storage | Cloudinary or compatible object storage |
| Testing | JUnit, Spring Boot Test, Playwright |

The backend will follow a modular monolithic architecture.

The frontend will remain based on Vanilla JavaScript. React will not be introduced.

## 14. Approved Product Decisions

| ID | Decision | Status |
|---|---|---|
| PD-01 | Retain the existing EstateVista repository for V2 development. | Approved |
| PD-02 | Use individual agent accounts. | Approved |
| PD-03 | Each property has one primary assigned agent. | Approved |
| PD-04 | Administrators create and manage agent accounts. | Approved |
| PD-05 | Agents manage assigned properties. | Approved |
| PD-06 | Buyer-agent conversations are property-specific. | Approved |
| PD-07 | One conversation exists per buyer-property combination. | Approved |
| PD-08 | Chat history persists even if a property becomes unavailable. | Approved |
| PD-09 | Agent reassignment does not silently transfer existing conversations. | Approved |
| PD-10 | Support both residential and commercial properties. | Approved |
| PD-11 | Keep the frontend based on Vanilla JavaScript. | Approved |

Stage 6 decisions D1–D12 are approved only in `docs/STAGE_6_DECISION_REGISTER.md`. They are Stage 6 architecture decisions, are not additional `PD-…` items, and do not change PD-01–PD-11 or this document's Draft status.

**Stage 7.1 resolution note:** Owner-approved chat decisions P-01–P-13 and findings F-7.1-01–F-7.1-08 are recorded in `docs/STAGE_7_DECISION_REGISTER.md`. They refine the chat interpretation of this PRD without adding `PD-…` items or changing this document's Draft status. Approval is documentation and contract only: Stage 7.1 implements no chat code, migration, dependency or deployment change and executes no acceptance scenario. The three formerly open chat questions in §15 are resolved below by P-01, P-03 and A-06.

## 15. Assumptions and Pending Decisions

The following details require further specification in subsequent Stage 1 activities:

- Exact property listing statuses and lifecycle.
- Exact residential and commercial property fields.
- Inquiry status workflow.
- Resolved by P-01: authenticated active buyers use a separate `Message agent` action on a `PUBLISHED` property; an inquiry or favorite is not a prerequisite.
- Resolved by P-03: if the original agent is inactive, conversation history is preserved and remains readable to the buyer, but messaging in that conversation is disabled and no fallback or newly assigned agent is offered.
- Resolved by A-06: conversation transcripts are private to the original buyer and original assigned agent; administrators have no list, detail, history, send, read-state or status access.
- Notification delivery and preferences.
- Image upload limits and storage policies.
- Property location and address representation.
- Account suspension and deletion policies.

Authentication session and token strategy is no longer a pending product decision. It is settled as a technical design by `SECURITY_ARCHITECTURE.md` SD-01 (server-side session with an opaque host-only `HttpOnly` cookie; no JWT) and is recorded in `SYSTEM_ARCHITECTURE.md` §9.3 and `API_SPECIFICATION.md` §3. Implementation and deployment prerequisites remain outstanding and are not claimed here.

The Stage 6 inquiry workflow item above is resolved for Stage 6 architecture by `docs/STAGE_6_DECISION_REGISTER.md`; no other pending item in this section is changed by that approval.

These items are not considered approved merely because they appear in this document.

---