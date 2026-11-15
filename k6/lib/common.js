import http from 'k6/http';
import { check } from 'k6';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

const CUSTOMERS = num('CUSTOMERS', 100000);
const PRODUCTS = num('PRODUCTS', 10000);
// HOT_PRODUCTS=5 -> every order hits one of 5 products -> row-lock contention on inventory
const HOT_PRODUCTS = num('HOT_PRODUCTS', 0);
const TIMEOUT = __ENV.REQ_TIMEOUT || '10s';

// 200 (sync) and 202 (async) are success; everything else counts in http_req_failed
http.setResponseCallback(http.expectedStatuses(200, 202));

export function num(name, def) {
  const v = __ENV[name];
  return v === undefined || v === '' ? def : Number(v);
}

function rnd(max) {
  return Math.floor(Math.random() * max) + 1;
}

export function randomOrder() {
  return {
    customerId: rnd(CUSTOMERS),
    productId: HOT_PRODUCTS > 0 ? rnd(HOT_PRODUCTS) : rnd(PRODUCTS),
    quantity: rnd(3),
  };
}

const JSON_HEADERS = { 'Content-Type': 'application/json' };

export function reserveSync() {
  const res = http.post(`${BASE_URL}/orders/reserve-sync`, JSON.stringify(randomOrder()), {
    headers: JSON_HEADERS,
    timeout: TIMEOUT,
    tags: { name: 'reserve-sync' },
  });
  check(res, { 'sync: 200 confirmed': (r) => r.status === 200 });
  return res;
}

/** Thresholds = the "healthy" definition. They do NOT abort the run: we want to watch it break. */
export const SYNC_THRESHOLDS = {
  http_req_failed: ['rate<0.01'],
  http_req_duration: ['p(95)<500', 'p(99)<1000'],
  dropped_iterations: ['count<1'],
};
