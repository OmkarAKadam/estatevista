# EstateVista V2 — Functional Requirements Document

**Version:** 1.0  
**Status:** Draft — functional baseline reviewed; document approval metadata not recorded  
**Parent Document:** PRD.md

**Stage 6 scope note:** Favorites and inquiry architecture decisions approved by the project owner on 24 November 2026 are recorded in `docs/STAGE_6_DECISION_REGISTER.md`. This document remains Draft; the Stage 6 register adds scoped cross-references only and does not approve this document or change existing FR IDs.

## 1. Purpose

This document defines the functional requirements, user permissions, workflows, validations, and expected system behaviour for EstateVista V2.

Every requirement has a unique identifier and should be independently testable.

## 2. User Roles

### 2.1 Visitor
An unauthenticated user who can:
- Browse public property listings.
- Search and filter properties.
- View property details.
- Register or log in.

### 2.2 Buyer
An authenticated user who can:
- Manage their profile.
- Browse properties.
- Manage favourites.
- Submit and track inquiries.
- Initiate property-specific conversations.
- Exchange messages with authorized agents.
- View notifications.

### 2.3 Property Agent
An authenticated user who can:
- Manage their profile.
- View assigned properties.
- Update assigned property listings.
- Manage property images.
- View inquiries associated with assigned properties.
- Communicate with buyers.
- View relevant notifications.

Agents cannot manage other agents or access unrelated properties' private information.

### 2.4 Administrator
An authenticated user with platform-wide administrative permissions.

Administrators can:
- Manage buyers.
- Create, update, deactivate, and manage agent accounts.
- Manage properties.
- Assign and reassign agents.
- Monitor inquiries and platform activity.
- Perform authorized administrative operations.

## 3. Authentication and Account Management

### FR-AUTH-001 — Buyer Registration
**Actor:** Visitor

The system shall allow visitors to create buyer accounts.

Acceptance criteria:
- Registration requires the configured mandatory fields.
- Email addresses must be unique.
- Passwords must satisfy the security policy.
- Invalid input must produce clear validation errors.
- Successful registration creates a buyer account.

### FR-AUTH-002 — User Login
**Actor:** Buyer, Agent, Administrator

The system shall authenticate registered users using their credentials.

Acceptance criteria:
- Valid credentials establish an authenticated session.
- Invalid credentials are rejected.
- The authenticated user's role determines permitted access.
- Authentication failures must not expose sensitive account information.

### FR-AUTH-003 — Logout
The system shall allow authenticated users to terminate their sessions.

Acceptance criteria:
- The session becomes invalid.
- Protected resources require authentication again.
- The frontend clears relevant authentication state.

### FR-AUTH-004 — Role-Based Access
The system shall enforce permissions according to the authenticated user's role.

Acceptance criteria:
- Buyers cannot access agent or administrator operations.
- Agents cannot access administrator operations.
- Administrators can access authorized platform-management operations.
- Unauthorized requests are rejected by the backend.

### FR-AUTH-005 — Account Profile
Authenticated users shall be able to view and update permitted profile information.

Acceptance criteria:
- Users can access only their own profiles.
- Protected fields such as role and account status cannot be changed by ordinary users.
- Invalid profile updates are rejected.

### FR-AUTH-006 — Account Status
The system shall support active and inactive account states.

Acceptance criteria:
- Inactive accounts cannot authenticate.
- Existing sessions must be invalidated or denied access after account deactivation.
- Account status changes must be restricted to authorized administrators.

### FR-AUTH-007 — Password Management
The system shall provide a secure password-change process.

Acceptance criteria:
- The current password must be verified when changing a password.
- New passwords must satisfy the configured password policy.
- Passwords must never be stored in plaintext.

### FR-AUTH-008 — Agent Account Creation
**Actor:** Administrator

Administrators shall be able to create agent accounts.

Acceptance criteria:
- Agent accounts are created through an administrative operation.
- Email addresses must be unique.
- The assigned role must be Agent.
- Agents cannot register themselves through buyer registration.

### FR-AUTH-009 — Administrative Account Protection
The system shall prevent public registration from creating administrator accounts.

Only authorized administrative processes may create or modify administrator accounts.

## 4. User and Role Management

### FR-USER-001 — Buyer Management
Administrators shall be able to view, search, and manage buyer accounts.

### FR-USER-002 — Agent Management
Administrators shall be able to:
- Create agent accounts.
- View agent details.
- Update agent information.
- Activate or deactivate agents.

### FR-USER-003 — Role Integrity
Users shall not be able to modify their own roles or grant permissions to themselves.

### FR-USER-004 — User Search
Administrators shall be able to search and filter users by relevant attributes, including role and account status.

## 5. Property Management

### FR-PROP-001 — Create Property
Administrators shall be able to create property listings and assign one primary agent. Authorized agents shall be able to update property listings assigned to them. Agents shall not create or reassign properties (PRD PD-03, PD-04, PD-05; API §25.1.G).

A property must contain the required identification, classification, location, pricing, and descriptive information.

### FR-PROP-002 — Property Categories
The system shall support:
- Residential properties.
- Commercial properties.

Each category shall support relevant property subtypes.

### FR-PROP-003 — Property Details
The system shall store and display property information, including:
- Title.
- Description.
- Property category and subtype.
- Sale or rental listing type.
- Price.
- Location.
- Area measurements.
- Images.
- Amenities.
- Availability status.

Additional category-specific fields shall be defined in the database design.

### FR-PROP-004 — Update Property
Authorized users shall be able to update permitted property information.

Agents may update only their assigned properties.

### FR-PROP-005 — Delete Property
Administrators shall be able to remove property listings.

Deletion must not unintentionally erase related inquiry or conversation history.

### FR-PROP-006 — Property Images
Authorized users shall be able to upload, replace, reorder, and remove property images.

The system shall validate supported formats and configured upload limits.

### FR-PROP-007 — Property Assignment
Administrators shall be able to assign one primary agent to each property.

### FR-PROP-008 — Agent Reassignment
Administrators shall be able to reassign properties.

Reassignment shall not automatically transfer existing private conversations.

### FR-PROP-009 — Property Status
The system shall support property availability states.

The exact status lifecycle will be finalized during detailed design.

### FR-PROP-010 — Agent Property Access
Agents shall only be able to manage properties assigned to them.

## 6. Property Discovery

### FR-DISC-001 — Property Listing
Visitors and authenticated users shall be able to browse public property listings.

### FR-DISC-002 — Keyword Search
Users shall be able to search properties using supported text fields.

### FR-DISC-003 — Category Filtering
Users shall be able to filter residential and commercial properties.

### FR-DISC-004 — Location Filtering
Users shall be able to filter properties by supported location attributes.

### FR-DISC-005 — Price Filtering
Users shall be able to specify minimum and maximum price values.

### FR-DISC-006 — Listing Type Filtering
Users shall be able to filter properties by sale or rental availability.

### FR-DISC-007 — Sorting
Users shall be able to sort listings using supported criteria.

### FR-DISC-008 — Pagination
The system shall return property listings in paginated results.

### FR-DISC-009 — Property Details
Users shall be able to open individual property details pages.

### FR-DISC-010 — Unavailable Properties
Unavailable properties shall not appear as available listings but may remain accessible through appropriate historical references.

## 7. Favorites

### FR-FAV-001 — Add Favorite
Authenticated buyers shall be able to add properties to their favourites.

### FR-FAV-002 — Remove Favorite
Buyers shall be able to remove properties from their favourites.

### FR-FAV-003 — View Favorites
Buyers shall be able to view their saved properties.

### FR-FAV-004 — Duplicate Prevention
The system shall prevent duplicate favourite records for the same buyer-property combination.

### FR-FAV-005 — Access Control
Buyers shall not be able to view or modify another buyer's favourites.

**Stage 6 clarification:** Buyer identity is principal-derived. Favorite mutations return `201` for a new favorite, `200` for an existing favorite on a `PUBLISHED` property, `204` for removal of an existing or nonexistent favorite, and generic `404` for a nonexistent or non-public property on add. Existing favorites are retained when a property becomes non-public and render only a generic unavailable placeholder; they remain removable and regain normal presentation on republish. The list is paginated and collection card state uses the approved batched status route, with no personalized flags in public property responses.

## 8. Inquiry Management

### FR-INQ-001 — Submit Inquiry
Authenticated buyers shall be able to submit inquiries regarding individual properties.

### FR-INQ-002 — Inquiry Association
Each inquiry shall be associated with:
- One buyer.
- One property.
- Relevant timestamps.
- An inquiry status.

### FR-INQ-003 — Buyer Inquiry History
Buyers shall be able to view their submitted inquiries.

### FR-INQ-004 — Agent Inquiry Access
Agents shall be able to view inquiries associated with their assigned properties.

### FR-INQ-005 — Inquiry Management
Authorized agents shall be able to update inquiry statuses.

**Stage 6 clarification:** Status authority belongs to historically assigned active AGENT accounts and authorized ADMIN accounts; buyers have read-only status access. The approved statuses are `NEW`, `IN_PROGRESS`, `RESOLVED`, and `CLOSED`, with the transition matrix, same-status idempotence, `resolved_at` rules, audit behavior, and `409` conflict handling in `docs/STAGE_6_DECISION_REGISTER.md` §3 Decision 1. At most one active inquiry (`NEW` or `IN_PROGRESS`) may exist per buyer/property; history is never merged, overwritten, or deleted.

### FR-INQ-006 — Inquiry Access Control
Users shall not be able to access unauthorized inquiry records.

## 9. Property-Specific Chat

### FR-CHAT-001 — Initiate Conversation
Authenticated buyers shall be able to initiate a conversation concerning a property.

### FR-CHAT-002 — Conversation Association
Each conversation shall be associated with one buyer, one property, and one responsible agent.

### FR-CHAT-003 — Unique Conversation
The system shall maintain one conversation per buyer-property combination.

### FR-CHAT-004 — Send Message
Authorized conversation participants shall be able to send messages.

### FR-CHAT-005 — Persistent Messages
Messages shall be stored in PostgreSQL.

### FR-CHAT-006 — Message History
Authorized participants shall be able to retrieve previous conversation messages.

### FR-CHAT-007 — Real-Time Delivery
The system shall deliver new messages to connected participants through WebSocket communication.

### FR-CHAT-008 — Message Timestamps
Every message shall have a server-generated timestamp.

### FR-CHAT-009 — Unread Messages
The system shall maintain unread message information for conversation participants.

### FR-CHAT-010 — Conversation Access Control
Only authorized participants may access private conversations.

### FR-CHAT-011 — Property Availability
Property unavailability shall not automatically delete or terminate conversation history.

### FR-CHAT-012 — Agent Reassignment
Changing a property's assigned agent shall not silently change existing conversation participants.

### FR-CHAT-013 — Message Ordering
Messages shall be retrievable in a consistent chronological order.

### FR-CHAT-014 — Connection Recovery
Users reconnecting after temporary disconnection shall be able to retrieve messages they missed.

## 10. Notifications

### FR-NOTIF-001 — New Inquiry Notification
The responsible agent shall receive a notification when a buyer submits an inquiry.

### FR-NOTIF-002 — New Message Notification
The receiving participant shall receive a notification when a new message arrives.

### FR-NOTIF-003 — Property Assignment Notification
Agents shall receive notifications about relevant property assignments.

### FR-NOTIF-004 — Notification Listing
Authenticated users shall be able to view their notifications.

### FR-NOTIF-005 — Read Status
Users shall be able to mark notifications as read.

### FR-NOTIF-006 — Notification Access
Users shall only be able to access their own notifications.

## 11. Dashboards

### FR-DASH-001 — Buyer Dashboard
The buyer dashboard shall display:
- Profile summary.
- Favourite properties.
- Inquiry history.
- Conversations.
- Relevant notifications.

### FR-DASH-002 — Agent Dashboard
The agent dashboard shall display:
- Assigned properties.
- Inquiry information.
- Active conversations.
- Unread messages.
- Relevant notifications.

### FR-DASH-003 — Administrator Dashboard
The administrator dashboard shall display:
- User statistics.
- Agent statistics.
- Property statistics.
- Inquiry statistics.
- Relevant platform activity.

### FR-DASH-004 — Role-Specific Access
Users shall only access the dashboard associated with their authorized role.

## 12. Audit and Administrative Operations

### FR-AUDIT-001 — Administrative Activity
The system shall maintain records of important administrative operations.

### FR-AUDIT-002 — Property Changes
Important property creation, modification, deletion, and assignment operations shall be auditable.

### FR-AUDIT-003 — Account Changes
Important account status and role changes shall be auditable.

### FR-AUDIT-004 — Audit Access
Audit records shall be accessible only to authorized administrators.

## 13. Global Validation and Error Handling

### FR-VAL-001 — Input Validation
All user-submitted data shall be validated on the backend.

### FR-VAL-002 — Consistent Errors
The backend shall return consistent error responses.

### FR-VAL-003 — Unauthorized Requests
Unauthorized operations shall be rejected.

### FR-VAL-004 — Missing Resources
Requests for nonexistent resources shall return appropriate not-found responses.

### FR-VAL-005 — Duplicate Operations
The system shall prevent duplicate records where uniqueness is required.

## 14. Acceptance Principles

A functional requirement is considered implemented only when:

1. Its expected behaviour is implemented.
2. Backend validation is present where applicable.
3. Authorization is enforced.
4. Relevant success and failure cases are tested.
5. Data persistence is verified where applicable.
6. Its acceptance criteria pass testing.

---