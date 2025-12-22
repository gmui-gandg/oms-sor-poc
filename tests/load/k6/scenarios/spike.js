// Spike test - sudden traffic surge (market open scenario)
// Run: k6 run scenarios/spike.js

import { options as baseOptions } from '../load-test.js';
export { default, setup, teardown } from '../load-test.js';

export const options = {
    ...baseOptions,
    scenarios: {
        spike: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 10 },   // Normal load
                { duration: '1m', target: 10 },    // Steady
                { duration: '10s', target: 200 },  // SPIKE! (market open)
                { duration: '2m', target: 200 },   // High load
                { duration: '10s', target: 10 },   // Drop back
                { duration: '1m', target: 10 },    // Recovery
                { duration: '30s', target: 0 },    // Ramp down
            ],
            gracefulRampDown: '10s',
        },
    },
    thresholds: {
        // Allow some degradation during spike
        'http_req_duration{name:POST /api/v1/orders}': ['p(95)<2000'],
        'success_rate': ['rate>0.90'],
    },
};
