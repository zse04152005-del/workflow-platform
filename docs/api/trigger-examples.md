# Trigger and event examples

```http
POST /api/v1/workflows/0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c496/instances
Idempotency-Key: demo-order-20260731
Traceparent: 0123456789abcdef0123456789abcdef
Content-Type: application/json

{"input":{"orderId":"O-88421"},"businessKey":"order/O-88421"}
```

The first response is `202` with an instance reference. Repeating the exact
request replays that response; reusing the key with a different body is `409`.
The `workflow.instance.created` event contains only IDs, status/version and
trace metadata; payloads are referenced by digest.
