// Smoke test - quick validation that system works
// Run: k6 run scenarios/smoke.js

import { options as baseOptions } from '../load-test.js';
export { default, setup, teardown } from '../load-test.js';

export const options = {
    ...baseOptions,
    scenarios: {
        smoke: {
            executor: 'constant-vus',
            vus: 1,
            duration: '30s',
        },
    },
    thresholds: {
        'http_req_duration{name:POST /api/v1/orders}': ['p(99)<1000'],
        'success_rate': ['rate>0.99'],
    },
};
