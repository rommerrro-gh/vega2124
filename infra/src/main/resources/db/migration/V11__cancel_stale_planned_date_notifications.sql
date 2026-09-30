-- These notifications were queued by older versions but could never be delivered.
-- Do not send historical date changes when the delivery query is corrected.
UPDATE notification_outbox
SET cancelled_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
WHERE kind GLOB 'PLANNED_DATE_*' AND sent_at IS NULL AND cancelled_at IS NULL;
