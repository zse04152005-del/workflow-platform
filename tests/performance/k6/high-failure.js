import http from 'k6/http';
export const options = { vus: 20, duration: '30m', thresholds: { http_req_failed: ['rate<0.05'] } };
export default function () { http.post(`${__ENV.BASE_URL}/api/v1/workflows/${__ENV.FAILURE_WORKFLOW_ID}/instances`, JSON.stringify({ input: { failureRate: 0.5 } }), { headers: { 'Content-Type': 'application/json', 'Idempotency-Key': `failure-${__VU}-${__ITER}` } }); }
