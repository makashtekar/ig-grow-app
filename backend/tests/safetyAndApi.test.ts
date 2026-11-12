import assert from "node:assert/strict";
import type { AddressInfo } from "node:net";
import test from "node:test";
import { createApp } from "../src/app.js";
import {
  clampBudgetToTier,
  evaluateTelemetrySafety,
  TIER_CEILINGS,
} from "../src/safety/safetyCeilings.js";
import { HeuristicFallbackPlanner } from "../src/services/geminiPlanner.js";
import { InMemoryGrowthStore } from "../src/storage/store.js";

test("clampBudgetToTier enforces Tier 1 hard ceilings", () => {
  const clamped = clampBudgetToTier(
    {
      storyViews: 500,
      storyLikes: 100,
      postLikes: 200,
      comments: 50,
      follows: 99,
    },
    "TIER_1_COLD"
  );
  assert.deepEqual(clamped, TIER_CEILINGS.TIER_1_COLD);
});

test("evaluateTelemetrySafety demotes tier and pauses 24h on 429", () => {
  const now = new Date("2026-11-12T10:00:00.000Z");
  const result = evaluateTelemetrySafety(
    "ACTIVE",
    "TIER_2_WARMING",
    10,
    [
      {
        actionType: "POST_LIKE",
        outcome: "RATE_LIMITED_429",
        httpStatus: 429,
        executedAt: now.toISOString(),
      },
    ],
    now
  );

  assert.equal(result.nextStatus, "PAUSED_RATE_LIMIT");
  assert.equal(result.nextTier, "TIER_1_COLD");
  assert.equal(result.nextCleanDaysStreak, 0);
  assert.equal(result.pausedUntil, "2026-11-13T10:00:00.000Z");
});

test("evaluateTelemetrySafety pauses immediately on CHALLENGE_REQUIRED", () => {
  const result = evaluateTelemetrySafety("ACTIVE", "TIER_3_ESTABLISHED", 25, [
    {
      actionType: "FOLLOW",
      outcome: "CHALLENGE_REQUIRED",
      executedAt: new Date().toISOString(),
    },
  ]);
  assert.equal(result.nextStatus, "PAUSED_CHALLENGE");
  assert.equal(result.nextTier, "TIER_2_WARMING");
});

test("Full /v1 API lifecycle: register -> generate -> approve -> daily tasks -> telemetry", async () => {
  const store = new InMemoryGrowthStore();
  const planner = new HeuristicFallbackPlanner();
  const app = createApp({ store, planner, devBypassAuth: true });

  const server = app.listen(0);
  const { port } = server.address() as AddressInfo;
  const baseUrl = `http://127.0.0.1:${port}`;
  const headers = {
    "content-type": "application/json",
    "x-dev-uid": "user-123",
  };

  try {
    // 1. Health check
    const healthRes = await fetch(`${baseUrl}/health`);
    assert.equal(healthRes.status, 200);

    // 2. Register IG account
    const regRes = await fetch(`${baseUrl}/v1/account/register`, {
      method: "POST",
      headers,
      body: JSON.stringify({
        dsUserId: "10276130724",
        username: "fitness_creator",
        followersCount: 1200,
        followingCount: 450,
        postsCount: 42,
        accountAgeDays: 180,
        goal: "INCREASE_FOLLOWERS",
        niche: "home workouts",
        competitorHandles: ["nike", "gymshark"],
        targetHashtags: ["homeworkout", "fitnessmotivation"],
      }),
    });
    assert.equal(regRes.status, 200);
    const regBody = (await regRes.json()) as { account: { tier: string; status: string } };
    assert.equal(regBody.account.tier, "TIER_1_COLD");
    assert.equal(regBody.account.status, "WARMING_UP");

    // 3. Generate 7-day AI plan
    const genRes = await fetch(`${baseUrl}/v1/plan/generate`, {
      method: "POST",
      headers,
      body: JSON.stringify({ dsUserId: "10276130724" }),
    });
    assert.equal(genRes.status, 201);
    const genBody = (await genRes.json()) as {
      plan: { planId: string; status: string; dailySchedules: unknown[] };
    };
    assert.equal(genBody.plan.status, "DRAFT");
    assert.equal(genBody.plan.dailySchedules.length, 7);

    // 4. Approve plan
    const approveRes = await fetch(`${baseUrl}/v1/plan/approve`, {
      method: "POST",
      headers,
      body: JSON.stringify({
        dsUserId: "10276130724",
        planId: genBody.plan.planId,
        commentTemplates: ["Love this workout routine!"],
      }),
    });
    assert.equal(approveRes.status, 200);
    const approveBody = (await approveRes.json()) as { plan: { status: string } };
    assert.equal(approveBody.plan.status, "APPROVED");

    // 5. Fetch daily tasks
    const tasksRes = await fetch(`${baseUrl}/v1/tasks/daily?dsUserId=10276130724`, { headers });
    assert.equal(tasksRes.status, 200);
    const tasksBody = (await tasksRes.json()) as {
      dayNumber: number;
      actionBudgets: { follows: number };
      commentTemplates: string[];
    };
    assert.equal(tasksBody.dayNumber, 1);
    assert.ok(tasksBody.actionBudgets.follows <= TIER_CEILINGS.TIER_1_COLD.follows);
    assert.deepEqual(tasksBody.commentTemplates, ["Love this workout routine!"]);

    // 6. Report 429 telemetry and verify circuit breaker activates
    const telRes = await fetch(`${baseUrl}/v1/telemetry/batch`, {
      method: "POST",
      headers,
      body: JSON.stringify({
        dsUserId: "10276130724",
        followersCount: 1205,
        events: [
          {
            actionType: "POST_LIKE",
            outcome: "RATE_LIMITED_429",
            httpStatus: 429,
            executedAt: new Date().toISOString(),
          },
        ],
      }),
    });
    assert.equal(telRes.status, 200);
    const telBody = (await telRes.json()) as { accountStatus: string; pausedUntil: string | null };
    assert.equal(telBody.accountStatus, "PAUSED_RATE_LIMIT");
    assert.ok(telBody.pausedUntil !== null);
  } finally {
    server.close();
  }
});
