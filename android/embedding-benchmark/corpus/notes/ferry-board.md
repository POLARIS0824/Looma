# Ferry board offline handover

The harbour desk keeps a small timetable on two devices. A counter tablet normally presents the day's sailings together with a whiteboard number. The boat coordinator carries another tablet outside to scan passenger codes. The devices sometimes share a connection through an office repeater, so timetable snapshots may arrive out of order. On a foggy morning the repeater reset twice. The counter still had the morning sailing while the scanner displayed its afternoon replacement. One traveller scanned a printed ticket for the morning boat after the coordinator had announced the cancellation.

## Sources and responsibilities

The staffing sheet assigns two staff members to each pier. The departure board carries sailings, planned capacity, and a live stop message. The scanner ties boarding attempts to the sailing ID and keeps a short queue while offline. A message can use the same public route number as yesterday's departure, so the route number only appears on the support screen. The number printed on a ticket is the sailing ID.

| Artifact | Edited by | Read by |
| --- | --- | --- |
| Timetable | Dispatch supervisor | Counter and gate |
| Stop message | Shift lead | Counter and gate |
| Manifest | Ticket desk | Gate and reconciliation |
| Boarding attempts | Gate | Reconciliation |

### Ferry routes during fog

Service north of the drawbridge uses the outdoor gate, which means the coordinator can lose sight of the desk. People waiting under the awning get weather announcements every twenty minutes. When a boat is diverted to the opposite pier, the desk announces both pier labels and helps transfer luggage already tagged for the former pier. During a diversion, orders already checked in can be moved to the replacement sailing through reconciliation.

> Our fallback should explain that the answer is unknown. A stale yes lets a traveller onto a boat using a ticket the desk can no longer honour.

## Offline behaviour

Opening the gate shows the last timetable version and its fetched time. The app may present it to help answer where a boat normally docks. Boarding is different. A stale copy can contain a replacement record with the wrong capacity or a departed boat that still looks open. We store each failed attempt with its public reason and never increment the headcount until acceptance is confirmed.

1. Start a shift while reachable and receive the current sailing information.
2. Leave the repeater service area and wait past the cached manifest expiry time.
3. Scan an unused ticket, then reconnect while the ferry is marked cancelled.
4. Check that the passenger record stays unboarded and the waiting attempt retains a reason.

### Dispatch after a missed update

On reconnect we apply the server's complete sailing snapshot before replaying queued events. An event id prevents a duplicated submission when the client retries the same attempt. Internal notes may still mention stale route codes for staff context. The ticket gate refuses to confirm boarding offline when the passenger manifest has expired, even if yesterday's departure board still opens.
