# Backoff for stalled attachment transfer

An intermittent gateway ACK lost after upload completion led the field app to retry the same picture. The objective is at-most-once object creation even though requests can be repeated by the transport.

Reuse the same idempotency key on each retry so the service returns the outcome of the original attachment upload.

The retry budget is per item and does not restart when a network callback fires.
