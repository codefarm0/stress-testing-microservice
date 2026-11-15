// Experiment 3 — Hold the sync service ABOVE capacity, then drop back and measure recovery.
//   RATE      overload rate (set to ~1.5x the max sustainable rate you found in experiment 2)
//   HOLD      how long to stay overloaded            (default 10m)
//   RECOVERY  rate after the overload                 (default 100 req/s for 5m)
import { num, reserveSync, SYNC_THRESHOLDS } from '../lib/common.js';

const RATE = num('RATE', 1500);
const RECOVERY = num('RECOVERY', 100);

export const options = {
  scenarios: {
    overload: {
      executor: 'ramping-arrival-rate',
      startRate: RECOVERY,
      timeUnit: '1s',
      stages: [
        { target: RECOVERY, duration: '1m' },                    // warm, healthy reference
        { target: RATE, duration: '30s' },                       // step into overload
        { target: RATE, duration: __ENV.HOLD || '10m' },         // sustained overload
        { target: RECOVERY, duration: '10s' },                   // load drops
        { target: RECOVERY, duration: __ENV.RECOVERY_TIME || '5m' }, // does it recover? how fast?
      ],
      preAllocatedVUs: 200,
      maxVUs: num('MAX_VUS', 3000),
    },
  },
  thresholds: SYNC_THRESHOLDS,
};

export default function () {
  reserveSync();
}
