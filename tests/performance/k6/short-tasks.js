import http from 'k6/http';
import { check } from 'k6';
export const options = { vus: 20, duration: '30m', thresholds: { http_req_duration: ['p(95)<1000'], http_req_failed: ['rate<0.01'] } };
export default function () { const response = http.post(`${__ENV.BASE_URL}/api/v1/workflows/${__ENV.SHORT_WORKFLOW_ID}/instances`, JSON.stringify({ input: { kind: 'short' } }), { headers: { 'Content-Type': 'application/json', 'Idempotency-Key': `short-${__VU}-${__ITER}` } }); check(response, { 'accepted': (res) => res.status === 202 }); }
