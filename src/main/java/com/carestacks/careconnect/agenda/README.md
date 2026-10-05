# Agenda Bounded Context

## Purpose

Agenda manages the lifecycle of patient health events: scheduling, conflict detection, calendar queries, confirmation, rescheduling, cancellation, deletion, and reminder calculation.

## Responsibilities From INFO.md

- Register health events with patient, type, date, time, and description.
- Prevent overlapping active events for the same patient.
- Generate a reminder 24 hours before each event when possible.
- Allow confirmation of scheduled events.
- Allow rescheduling while preserving conflict validation.
- Expose calendar queries by patient and date.
- Keep notification delivery outside this context; Notifications owns delivery.

## Internal Architecture

- `domain/agenda/entities`: `HealthEvent` and `Reminder` business rules.
- `domain/agenda/enums`: `EventType` and `EventStatus`.
- `application/abstractions`: `AgendaService` use-case contract.
- `application/agenda/dtos`: `HealthEventDto` and `ReminderDto`.
- `application/agenda/requests`: Create, update, and reschedule request models.
- `infrastructure`: Service implementation, JPA entities, Spring Data repositories, and mappers.
- `interfaces`: REST controller documented for Swagger/OpenAPI.

## Main Entities

- `HealthEvent`: Patient-owned appointment, medication, therapy, or care activity.
- `Reminder`: Reminder metadata scheduled 24 hours before the event.
- `EventStatus`: `PENDING`, `CONFIRMED`, `CANCELLED`, or `MISSED`.
- `EventType`: `APPOINTMENT`, `MEDICATION`, `THERAPY`, or `CARE_ACTIVITY`.

## Implemented Use Cases

- Create a health event.
- Detect overlapping active schedules.
- Generate and update reminders.
- List health events for the caller's patient profile.
- List patient events.
- List patient events by calendar date.
- Get a health event by ID.
- Update a health event.
- Confirm a health event.
- Reschedule a health event.
- Cancel a health event.
- Delete a health event and its reminder.

## Endpoints

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/api/agenda` | Creates a health event and reminder. |
| `GET` | `/api/agenda` | Lists the current patient's events or the caregiver's authorized patient profile. |
| `GET` | `/api/agenda/patient/{patientId}` | Lists health events for a patient. |
| `GET` | `/api/agenda/date?patientId={patientId}&date={yyyy-MM-dd}` | Lists patient events for one date. |
| `GET` | `/api/agenda/{id}` | Gets a health event by ID. |
| `PUT` | `/api/agenda/{id}` | Updates event details and schedule. |
| `PATCH` | `/api/agenda/{id}/confirm` | Confirms a health event. |
| `PATCH` | `/api/agenda/{id}/reschedule` | Reschedules a health event and reminder. |
| `PATCH` | `/api/agenda/{id}/cancel` | Cancels a health event. |
| `DELETE` | `/api/agenda/{id}` | Deletes a health event and its reminder. |

## Authorization

Every Agenda endpoint requires `Authorization: Bearer <token>` from IAM login. A patient can access their own agenda; a caregiver needs an active consent with `AGENDA` for that patient. Each read and mutation rechecks consent, so removing that view or revoking consent blocks the next request. Creating an event as caregiver records the session's caregiver ID and rejects another caregiver's ID.

Missing, fabricated, expired, or logged-out sessions return HTTP 401. A valid session without ownership or current consent returns HTTP 403. `/api/agenda` never returns the global event list. Flutter web and mobile callers send the session explicitly and discard cached lists on 401/403.

Before deploying the backend, every patient client must send its login token on Agenda calls. The native Android patient source was unavailable for this verification and still requires compatibility validation. IAM sessions are instance-local; see the IAM README for restart and multi-instance limitations.

## Pending Endpoints

- No endpoint is currently exposed for marking an event as missed. The domain supports the state, but alert escalation is owned by Notifications.

## Swagger/OpenAPI

`AgendaController` is annotated with `@Tag`, `@Operation`, and `@ApiResponses`. The endpoints are exposed under the `Agenda` tag in Swagger UI at `/swagger-ui.html`.

## How To Run Or Test

1. Run `mvn test`.
2. Start the application with `mvn spring-boot:run`.
3. Open `http://localhost:8080/swagger-ui.html`.
4. Register/login as patient, then create an event with `POST /api/agenda` and the returned bearer token. A caregiver must first receive Agenda consent from the patient.
5. Query the patient calendar with `/api/agenda/patient/{patientId}` or `/api/agenda/date`.
6. Confirm, reschedule, cancel, or delete the event through the corresponding endpoint.

## Changes In This Feature Branch

- Added explicit Swagger/OpenAPI documentation for all Agenda endpoints.
- Added this bounded-context-specific README.

## Technical Considerations

- Agenda owns schedule validation and reminder calculation only.
- Notification delivery is intentionally delegated to the Notifications bounded context.
- Conflict detection only considers active `PENDING` and `CONFIRMED` events.
