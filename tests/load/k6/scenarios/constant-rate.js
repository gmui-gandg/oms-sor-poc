// Constant rate test - fixed orders per second
// Run: k6 run scenarios/constant-rate.js
// Use for precise throughput testing

import { options as baseOptions } from '../load-test.js';
export { default, setup, teardown } from '../load-test.js';

export const options = {
    ...baseOptions,
    scenarios: {
        constant_rate: {
            executor: 'constant-arrival-rate',
            rate: 100,              // 100 orders per second
            timeUnit: '1s',
            duration: '5m',
            preAllocatedVUs: 50,    // Pre-allocate VUs
            maxVUs: 200,            // Max VUs if needed
        },
    },
    thresholds: {
        'http_req_duration{name:POST /api/v1/orders}': ['p(95)<500', 'p(99)<1000'],
        'success_rate': ['rate>0.99'],
        'dropped_iterations': ['count<10'],  // Should not drop requests
    },
};
