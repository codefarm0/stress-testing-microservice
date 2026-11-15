// Experiment 2 — Push the sync service: step the ARRIVAL RATE up and hold each step.
// Defaults: 100 -> 250 -> 500 -> 750 -> 1000 -> 1500 -> 2000 req/s, 2 min per step (~16 min).
//   -e LEVELS=100,200,300,400   -e STEP=1m   -e MAX_VUS=5000
import { num, reserveSync, SYNC_THRESHOLDS } from '../lib/common.js';

const LEVELS = (__ENV.LEVELS || '100,250,500,750,1000,1500,2000').split(',').map(Number);
const STEP = __ENV.STEP || '2m';

export const options = {
  scenarios: {
    ramp: {
      executor: 'ramping-arrival-rate',
      startRate: LEVELS[0],
      timeUnit: '1s',
      // 15s transition to each level, then hold it
      stages: LEVELS.flatMap((t) => [{ target: t, duration: '15s' }, { target: t, duration: STEP }]),
      preAllocatedVUs: 200,
      maxVUs: num('MAX_VUS', 3000),
    },
  },
  thresholds: SYNC_THRESHOLDS,
};

export default function () {
  reserveSync();
}
