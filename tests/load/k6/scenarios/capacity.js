// Capacity test - find maximum throughput
// Run: k6 run scenarios/capacity.js

import { options as baseOptions } from '../load-test.js';
export { default, setup, teardown } from '../load-test.js';

export const options = {
    ...baseOptions,
    scenarios: {
        capacity: {
            executor: 'ramping-arrival-rate',
            startRate: 10,           // Start at 10 orders/sec
            timeUnit: '1s',
            preAllocatedVUs: 100,    // Pre-allocate VUs
            maxVUs: 500,             // Max VUs available
            stages: [
                { duration: '30s', target: 20 },   // Warm up to 20/sec
                { duration: '30s', target: 50 },   // Ramp to 50/sec
                { duration: '30s', target: 100 },  // Ramp to 100/sec
                { duration: '30s', target: 150 },  // Push to 150/sec
                { duration: '1m', target: 200 },   // Push to 200/sec
                { duration: '1m', target: 200 },   // Hold at 200/sec
                { duration: '30s', target: 100 },  // Step down
                { duration: '30s', target: 0 },    // Ramp down
            ],
        },
    },
    thresholds: {
        // Track but don't fail - we want to see the limits
        'http_req_duration{name:POST /api/v1/orders}': ['p(95)<2000'],
        'success_rate': ['rate>0.80'],  // Allow some failures at capacity
        'http_req_failed': ['rate<0.20'],
    },
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};
