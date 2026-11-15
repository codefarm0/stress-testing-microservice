// Sanity check that the whole pipeline works (app, DB, metrics, dashboard). 5 req/s for 30s.
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, reserveSync } from '../lib/common.js';

export const options = {
  scenarios: {
    smoke: { executor: 'constant-arrival-rate', rate: 5, timeUnit: '1s', duration: '30s', preAllocatedVUs: 5, maxVUs: 20 },
  },
  thresholds: { http_req_failed: ['rate==0'], checks: ['rate==1'] },
};

export default function () {
  const res = reserveSync();
  if (res.status === 200) {
    const order = res.json();
    const got = http.get(`${BASE_URL}/orders/${order.orderId}`, { tags: { name: 'get-order' } });
    check(got, { 'order readable': (r) => r.status === 200 && r.json('status') === 'CONFIRMED' });
  }
}
