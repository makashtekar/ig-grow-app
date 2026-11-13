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

test("Full /v1 API lifecycle and /admin management", async () => {
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

    // 3. Admin login test
    const adminLoginRes = await fetch(`${baseUrl}/admin/auth/login`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        email: "admin@iggrow.studio",
        password: "admin123456",
      }),
    });
    assert.equal(adminLoginRes.status, 200);
    const loginData = (await adminLoginRes.json()) as { token: string };
    assert.ok(loginData.token);

    const adminHeaders = {
      "content-type": "application/json",
      authorization: `Bearer ${loginData.token}`,
    };

    // 4. Admin Overview
    const overviewRes = await fetch(`${baseUrl}/admin/overview`, { headers: adminHeaders });
    assert.equal(overviewRes.status, 200);
    const overviewData = (await overviewRes.json()) as { totalAccounts: number };
    assert.equal(overviewData.totalAccounts, 1);

    // 5. Admin Accounts list & patch
    const accountsRes = await fetch(`${baseUrl}/admin/accounts`, { headers: adminHeaders });
    assert.equal(accountsRes.status, 200);
    const accountsData = (await accountsRes.json()) as { accounts: Array<{ dsUserId: string; tier: string }> };
    assert.equal(accountsData.accounts.length, 1);
    assert.equal(accountsData.accounts[0].tier, "TIER_1_COLD");

    const patchRes = await fetch(`${baseUrl}/admin/accounts/10276130724`, {
      method: "PATCH",
      headers: adminHeaders,
      body: JSON.stringify({
        tier: "TIER_2_WARMING",
        status: "ACTIVE",
      }),
    });
    assert.equal(patchRes.status, 200);
    const patchData = (await patchRes.json()) as { account: { tier: string; status: string } };
    assert.equal(patchData.account.tier, "TIER_2_WARMING");
    assert.equal(patchData.account.status, "ACTIVE");

    // 6. Admin Settings
    const settingsRes = await fetch(`${baseUrl}/admin/settings`, { headers: adminHeaders });
    assert.equal(settingsRes.status, 200);
    const settingsData = (await settingsRes.json()) as { settings: { schemaVersion: number } };
    assert.equal(settingsData.settings.schemaVersion, 1);
  } finally {
    server.close();
  }
});
