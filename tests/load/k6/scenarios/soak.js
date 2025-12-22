// Soak test - long-running stability test
// Run: k6 run scenarios/soak.js
// Typical duration: 1-4 hours

import { options as baseOptions } from '../load-test.js';
export { default, setup, teardown } from '../load-test.js';

export const options = {
    ...baseOptions,
    scenarios: {
        soak: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '5m', target: 25 },     // Ramp up
                { duration: '1h', target: 25 },     // Soak for 1 hour at moderate load
                { duration: '5m', target: 0 },      // Ramp down
            ],
            gracefulRampDown: '30s',
        },
    },
    thresholds: {
        'http_req_duration{name:POST /api/v1/orders}': ['p(95)<500', 'p(99)<1000'],
        'success_rate': ['rate>0.99'],
        'orders_failed': ['count<50'],
    },
};
