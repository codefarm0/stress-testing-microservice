// Experiment 1 — Baseline: steady 100 req/s for 5 minutes. Establish "normal".
import { num, reserveSync, SYNC_THRESHOLDS } from '../lib/common.js';

export const options = {
  scenarios: {
    baseline: {
      executor: 'constant-arrival-rate',
      rate: num('RATE', 100),
      timeUnit: '1s',
      duration: __ENV.DURATION || '5m',
      preAllocatedVUs: 50,
      maxVUs: 500,
    },
  },
  thresholds: SYNC_THRESHOLDS,
};

export default function () {
  reserveSync();
}
