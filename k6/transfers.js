import http from "k6/http";
import { check } from "k6";
import exec from "k6/execution";
import { Counter, Rate } from "k6/metrics";

const BASE = __ENV.BASE_URL || "http://localhost:8080";
const RATE = Number(__ENV.RATE || 150); // transfers per second at peak
const ACCOUNTS = 100;
const ACCOUNT_FUNDS = 1_000_000; // minor units
const HOT_FUNDS = 10_000;
const HOT_AMOUNT = 100;
const HOT_ATTEMPTS = 500;

// Custom metrics.
const replayOk = new Rate("idempotent_replay_ok");
const conflictOk = new Rate("idempotency_conflict_ok");
const hotSucceeded = new Counter("hot_transfers_succeeded");
const invariantViolations = new Counter("invariant_violations");

export const options = {
  scenarios: {
    transfers: {
      executor: "ramping-arrival-rate",
      exec: "transfer",
      startRate: 10,
      timeUnit: "1s",
      preAllocatedVUs: 100,
      maxVUs: 400,
      stages: [
        { duration: "10s", target: RATE },
        { duration: "40s", target: RATE },
        { duration: "5s", target: 0 },
      ],
    },
    hot_account: {
      executor: "shared-iterations",
      exec: "hotAccount",
      vus: 20,
      iterations: HOT_ATTEMPTS,
      maxDuration: "60s",
      startTime: "15s", // overlaps with the steady state of the main scenario
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.001"],
    "http_req_duration{endpoint:transfer}": ["p(95)<300", "p(99)<750"],
    "http_req_duration{endpoint:transfer_retry}": ["p(95)<200"],
    checks: ["rate>0.999"],
    idempotent_replay_ok: ["rate==1"],
    idempotency_conflict_ok: ["rate==1"],
    hot_transfers_succeeded: [`count==${HOT_FUNDS / HOT_AMOUNT}`],
    invariant_violations: ["count==0"],
    dropped_iterations: ["count==0"],
  },
};

function idempotencyKey() {
  return `k6-${exec.vu.idInTest}-${exec.vu.iterationInScenario}-${Date.now()}-${Math.floor(Math.random() * 1e9)}`;
}

function params(key, endpoint, extra = {}) {
  return {
    headers: { "Content-Type": "application/json", "Idempotency-Key": key },
    tags: { endpoint },
    ...extra,
  };
}

function createFundedAccount(name, funds) {
  const created = http.post(
    `${BASE}/api/v1/accounts`,
    JSON.stringify({ name, currency: "EUR" }),
    params(idempotencyKey(), "setup"),
  );
  const id = created.json("id");
  http.post(
    `${BASE}/api/v1/accounts/${id}/deposits`,
    JSON.stringify({ amount: funds, currency: "EUR" }),
    params(idempotencyKey(), "setup"),
  );
  return id;
}

export function setup() {
  const accounts = [];
  for (let i = 0; i < ACCOUNTS; i++) {
    accounts.push(createFundedAccount(`load-${i}`, ACCOUNT_FUNDS));
  }
  const hot = createFundedAccount("hot", HOT_FUNDS);
  const sink = createFundedAccount("sink", 0);
  return { accounts, hot, sink };
}

function pick(list) {
  return list[Math.floor(Math.random() * list.length)];
}

export function transfer(data) {
  const from = pick(data.accounts);
  let to = pick(data.accounts);
  while (to === from) {
    to = pick(data.accounts);
  }
  const amount = 1 + Math.floor(Math.random() * 500);
  const payload = JSON.stringify({
    sourceAccountId: from,
    destinationAccountId: to,
    amount,
    currency: "EUR",
    reference: "k6",
  });
  const key = idempotencyKey();

  const res = http.post(
    `${BASE}/api/v1/transfers`,
    payload,
    params(key, "transfer"),
  );
  const created = check(res, {
    "transfer created (201)": (r) => r.status === 201,
  });
  if (!created) {
    return;
  }
  const id = res.json("id");

  const roll = Math.random();
  if (roll < 0.1) {
    // Client retries after a "timeout": must replay the same transfer, not create a new one.
    const retry = http.post(
      `${BASE}/api/v1/transfers`,
      payload,
      params(key, "transfer_retry"),
    );
    replayOk.add(
      retry.status === 201 &&
        retry.headers["Idempotent-Replayed"] === "true" &&
        retry.json("id") === id,
    );
  } else if (roll < 0.12) {
    // Same key, different payload: must be refused.
    const tampered = JSON.stringify({
      ...JSON.parse(payload),
      amount: amount + 1,
    });
    const conflict = http.post(
      `${BASE}/api/v1/transfers`,
      tampered,
      params(key, "transfer_conflict", {
        responseCallback: http.expectedStatuses(422),
      }),
    );
    conflictOk.add(
      conflict.status === 422 &&
        conflict.json("code") === "IDEMPOTENCY_KEY_REUSED",
    );
  }
}

export function hotAccount(data) {
  const res = http.post(
    `${BASE}/api/v1/transfers`,
    JSON.stringify({
      sourceAccountId: data.hot,
      destinationAccountId: data.sink,
      amount: HOT_AMOUNT,
      currency: "EUR",
    }),
    params(idempotencyKey(), "hot", {
      responseCallback: http.expectedStatuses(201, 422),
    }),
  );
  if (res.status === 201) {
    hotSucceeded.add(1);
  }
  check(res, {
    "hot: created or insufficient funds": (r) =>
      r.status === 201 ||
      (r.status === 422 && r.json("code") === "INSUFFICIENT_FUNDS"),
  });
}

export function teardown(data) {
  const report = http.get(`${BASE}/api/v1/reconciliation`).json();
  console.log(`reconciliation: ${JSON.stringify(report)}`);
  if (report.consistent !== true) {
    invariantViolations.add(1);
  }

  const hot = http.get(`${BASE}/api/v1/accounts/${data.hot}`).json("balance");
  const sink = http.get(`${BASE}/api/v1/accounts/${data.sink}`).json("balance");
  if (hot !== 0 || sink !== HOT_FUNDS) {
    console.error(`hot account drained incorrectly: hot=${hot} sink=${sink}`);
    invariantViolations.add(1);
  }
}
