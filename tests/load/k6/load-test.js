import { randomIntBetween, randomItem } from 'https://jslib.k6.io/k6-utils/1.2.0/index.js';
import { check, group, sleep } from 'k6';
import http from 'k6/http';
import { Counter, Rate, Trend } from 'k6/metrics';

// Custom metrics
const orderCreated = new Counter('orders_created');
const orderFailed = new Counter('orders_failed');
const orderLatency = new Trend('order_latency', true);
const successRate = new Rate('success_rate');

// Configuration
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// Test data pools
const SYMBOLS = [
    // High volume (60% of orders)
    { symbol: 'AAPL', weight: 15, priceRange: [170, 200] },
    { symbol: 'MSFT', weight: 15, priceRange: [380, 420] },
    { symbol: 'GOOGL', weight: 10, priceRange: [140, 160] },
    { symbol: 'AMZN', weight: 10, priceRange: [175, 195] },
    { symbol: 'NVDA', weight: 10, priceRange: [450, 550] },
    // Medium volume (30% of orders)
    { symbol: 'META', weight: 6, priceRange: [480, 550] },
    { symbol: 'TSLA', weight: 6, priceRange: [240, 280] },
    { symbol: 'JPM', weight: 6, priceRange: [180, 210] },
    { symbol: 'V', weight: 6, priceRange: [270, 300] },
    { symbol: 'JNJ', weight: 6, priceRange: [150, 170] },
    // Low volume (10% of orders)
    { symbol: 'WMT', weight: 2, priceRange: [160, 180] },
    { symbol: 'PG', weight: 2, priceRange: [150, 170] },
    { symbol: 'HD', weight: 2, priceRange: [350, 400] },
    { symbol: 'BAC', weight: 2, priceRange: [35, 45] },
    { symbol: 'DIS', weight: 2, priceRange: [90, 110] },
];

const ACCOUNTS = [
    'ACC-INST-001', 'ACC-INST-002', 'ACC-INST-003', // Institutional
    'ACC-HF-001', 'ACC-HF-002',                      // Hedge funds
    'ACC-RET-001', 'ACC-RET-002', 'ACC-RET-003', 'ACC-RET-004', 'ACC-RET-005', // Retail
];

const ORDER_TYPES = [
    { type: 'LIMIT', weight: 70 },
    { type: 'MARKET', weight: 30 },
];

const SIDES = ['BUY', 'SELL'];
const TIME_IN_FORCE = ['DAY', 'GTC', 'IOC', 'FOK'];

// Weighted random selection
function weightedRandom(items) {
    const totalWeight = items.reduce((sum, item) => sum + item.weight, 0);
    let random = Math.random() * totalWeight;
    
    for (const item of items) {
        random -= item.weight;
        if (random <= 0) return item;
    }
    return items[items.length - 1];
}

// Generate unique client order ID
function generateClientOrderId() {
    const timestamp = Date.now();
    const random = Math.random().toString(36).substring(2, 8);
    return `K6-${__VU}-${timestamp}-${random}`;
}

// Generate realistic order
function generateOrder() {
    const symbolData = weightedRandom(SYMBOLS);
    const orderTypeData = weightedRandom(ORDER_TYPES);
    const side = randomItem(SIDES);
    
    // Realistic quantity distribution (mostly small, some large)
    let quantity;
    const quantityRoll = Math.random();
    if (quantityRoll < 0.5) {
        quantity = randomIntBetween(10, 100);      // 50% small orders
    } else if (quantityRoll < 0.85) {
        quantity = randomIntBetween(100, 500);     // 35% medium orders
    } else if (quantityRoll < 0.97) {
        quantity = randomIntBetween(500, 2000);    // 12% large orders
    } else {
        quantity = randomIntBetween(2000, 10000);  // 3% block orders
    }
    
    // Round to lot size
    quantity = Math.round(quantity / 10) * 10;
    if (quantity === 0) quantity = 10;
    
    const order = {
        clientOrderId: generateClientOrderId(),
        accountId: randomItem(ACCOUNTS),
        symbol: symbolData.symbol,
        side: side,
        orderType: orderTypeData.type,
        quantity: quantity,
        timeInForce: randomItem(TIME_IN_FORCE),
    };
    
    // Add limit price for LIMIT orders
    if (orderTypeData.type === 'LIMIT') {
        const [minPrice, maxPrice] = symbolData.priceRange;
        const midPrice = (minPrice + maxPrice) / 2;
        
        // Offset from mid based on side
        let priceOffset = (Math.random() * 0.02 - 0.01) * midPrice; // ±1%
        if (side === 'BUY') {
            priceOffset -= Math.random() * 0.005 * midPrice; // Buyers bid lower
        } else {
            priceOffset += Math.random() * 0.005 * midPrice; // Sellers ask higher
        }
        
        order.limitPrice = Math.round((midPrice + priceOffset) * 100) / 100;
    }
    
    return order;
}

// Submit order
function submitOrder() {
    const order = generateOrder();
    const payload = JSON.stringify(order);
    
    const params = {
        headers: {
            'Content-Type': 'application/json',
        },
        tags: {
            name: 'POST /api/v1/orders',
        },
    };
    
    const startTime = Date.now();
    const response = http.post(`${BASE_URL}/api/v1/orders`, payload, params);
    const latency = Date.now() - startTime;
    
    orderLatency.add(latency);
    
    const success = check(response, {
        'status is 200 or 201': (r) => r.status === 200 || r.status === 201,
        'response has orderId': (r) => {
            try {
                const body = JSON.parse(r.body);
                return body.orderId !== null && body.orderId !== undefined;
            } catch {
                return false;
            }
        },
        'order was created': (r) => {
            try {
                const body = JSON.parse(r.body);
                return body.created === true;
            } catch {
                return false;
            }
        },
    });
    
    if (success) {
        orderCreated.add(1);
        successRate.add(1);
    } else {
        orderFailed.add(1);
        successRate.add(0);
        if (__ENV.DEBUG) {
            console.log(`Failed order: ${response.status} - ${response.body}`);
        }
    }
    
    return response;
}

// Health check
function healthCheck() {
    const response = http.get(`${BASE_URL}/actuator/health`, {
        tags: { name: 'GET /actuator/health' },
    });
    
    check(response, {
        'health check passed': (r) => r.status === 200,
    });
}

// Export scenarios
export const options = {
    scenarios: {
        // Default: ramping load test
        load_test: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 10 },   // Ramp up to 10 users
                { duration: '1m', target: 10 },    // Stay at 10
                { duration: '30s', target: 25 },   // Ramp up to 25
                { duration: '2m', target: 25 },    // Stay at 25
                { duration: '30s', target: 50 },   // Ramp up to 50
                { duration: '2m', target: 50 },    // Stay at 50
                { duration: '30s', target: 0 },    // Ramp down
            ],
            gracefulRampDown: '10s',
        },
    },
    thresholds: {
        'http_req_duration{name:POST /api/v1/orders}': ['p(95)<500', 'p(99)<1000'],
        'success_rate': ['rate>0.95'],
        'orders_failed': ['count<100'],
    },
};

// Main test function
export default function () {
    group('Order Submission', () => {
        submitOrder();
    });
    
    // Realistic think time between orders (100-500ms for high-frequency trading)
    sleep(randomIntBetween(100, 500) / 1000);
}

// Setup - verify system is healthy before load test
export function setup() {
    console.log(`Starting load test against ${BASE_URL}`);
    
    const healthResponse = http.get(`${BASE_URL}/actuator/health`);
    if (healthResponse.status !== 200) {
        throw new Error(`System not healthy: ${healthResponse.status}`);
    }
    
    console.log('System health check passed');
    return { startTime: Date.now() };
}

// Teardown - summary
export function teardown(data) {
    const duration = (Date.now() - data.startTime) / 1000;
    console.log(`Load test completed in ${duration.toFixed(2)}s`);
}
