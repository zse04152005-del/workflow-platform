import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  scenarios: { trigger: { executor: 'constant-arrival-rate', rate: 50, timeUnit: '1s', duration: '30m', preAllocatedVUs: 20, maxVUs: 100 } },
  thresholds: { http_req_failed: ['rate<0.01'], http_req_duration: ['p(95)<500'] },
};

export default function () {
  const key = `k6-${__VU}-${__ITER}`;
  const response = http.post(`${__ENV.BASE_URL}/api/v1/workflows/${__ENV.WORKFLOW_ID}/instances`, JSON.stringify({ input: { source: 'k6' } }), { headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key } });
  check(response, { 'accepted': (res) => res.status === 202 });
  sleep(0.1);
}
