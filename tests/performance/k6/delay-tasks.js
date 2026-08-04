import http from 'k6/http';
export const options = { vus: 10, duration: '30m' };
export default function () { http.post(`${__ENV.BASE_URL}/api/v1/workflows/${__ENV.DELAY_WORKFLOW_ID}/instances`, JSON.stringify({ input: { kind: 'delay' } }), { headers: { 'Content-Type': 'application/json', 'Idempotency-Key': `delay-${__VU}-${__ITER}` } }); }
