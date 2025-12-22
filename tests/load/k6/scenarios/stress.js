// Stress test - find the breaking point
// Run: k6 run scenarios/stress.js

import { options as baseOptions } from '../load-test.js';
export { default, setup, teardown } from '../load-test.js';

export const options = {
    ...baseOptions,
    scenarios: {
        stress: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '1m', target: 50 },    // Ramp to 50
                { duration: '2m', target: 50 },    // Hold
                { duration: '1m', target: 100 },   // Ramp to 100
                { duration: '2m', target: 100 },   // Hold
                { duration: '1m', target: 200 },   // Ramp to 200
                { duration: '2m', target: 200 },   // Hold
                { duration: '1m', target: 300 },   // Ramp to 300
                { duration: '2m', target: 300 },   // Hold - likely breaking point
                { duration: '2m', target: 0 },     // Ramp down
            ],
            gracefulRampDown: '30s',
        },
    },
    thresholds: {
        // More lenient thresholds for stress test - we expect some failures
        'http_req_duration{name:POST /api/v1/orders}': ['p(95)<2000'],
        'success_rate': ['rate>0.80'],
    },
};
