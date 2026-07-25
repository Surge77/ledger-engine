// k6 load profile for the ledger -> Kafka -> fraud platform.
//
//   k6 run -e API_KEY=$LEDGER_API_KEY scripts/load-test.js
//
// Drives real transfers so the Grafana dashboards have shape and the SLO alert
// rules have data to evaluate. The thresholds below mirror the recording rules in
// fraud-engine/monitoring/prometheus-rules.yml — if they disagree, the dashboard
// and the load test are measuring different things.

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8081';
const API_KEY = __ENV.API_KEY;

const conflicts = new Counter('idempotency_conflicts');

export const options = {
  stages: [
    { duration: '30s', target: 20 },  // ramp
    { duration: '2m', target: 20 },   // steady state — the window alerts evaluate
    { duration: '30s', target: 0 },   // drain
  ],
  thresholds: {
    // SLO: 99.5% of writes under 200ms.
    'http_req_duration{name:transfer}': ['p(99)<200'],
    'http_req_failed{name:transfer}': ['rate<0.005'],
  },
};

export function setup() {
  if (!API_KEY) {
    throw new Error('API_KEY is required — export LEDGER_API_KEY and pass it with -e');
  }
  const headers = { 'Content-Type': 'application/json', 'X-API-Key': API_KEY };

  // Two funded accounts to move value between. Created once so the steady-state
  // phase measures transfer latency, not account setup.
  const accounts = [];
  for (let i = 0; i < 2; i++) {
    const res = http.post(
      `${BASE}/accounts`,
      JSON.stringify({ currency: 'USD' }),
      { headers },
    );
    accounts.push(res.json('id'));
  }

  http.post(
    `${BASE}/accounts/${accounts[0]}/deposits`,
    JSON.stringify({ amountMinor: 100000000, currency: 'USD' }),
    { headers },
  );

  return { headers, from: accounts[0], to: accounts[1] };
}

export default function (data) {
  // Unique key per iteration: replaying one would exercise the idempotency path
  // rather than the write path, and quietly flatten the latency numbers.
  const idempotencyKey = `k6-${__VU}-${__ITER}-${Date.now()}`;

  const res = http.post(
    `${BASE}/transfers`,
    JSON.stringify({
      from: data.from,
      to: data.to,
      amountMinor: 100 + (__ITER % 500),
      currency: 'USD',
      idempotencyKey,
    }),
    { headers: data.headers, tags: { name: 'transfer' } },
  );

  if (res.status === 409) {
    conflicts.add(1);
  }

  check(res, {
    'transfer accepted': (r) => r.status === 200 || r.status === 201,
  });
}
