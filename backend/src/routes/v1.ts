import { randomUUID } from "node:crypto";
import { Router, type Response } from "express";
import { z } from "zod";
import type { AuthenticatedRequest } from "../middleware/auth.js";
import {
  clampBudgetToTier,
  evaluateTelemetrySafety,
  initialTierForAccount,
} from "../safety/safetyCeilings.js";
import type { PlannerService } from "../services/geminiPlanner.js";
import type { GrowthStore } from "../storage/store.js";
import {
  ApprovePlanInputSchema,
  type GrowthPlan,
  type IgAccountRecord,
  RegisterAccountInputSchema,
  TelemetryBatchInputSchema,
} from "../types/growth.js";

const GeneratePlanBodySchema = z.object({
  dsUserId: z.string().min(1),
});

export function createV1Router(store: GrowthStore, planner: PlannerService): Router {
  const router = Router();

  router.post("/account/register", async (req: AuthenticatedRequest, res: Response) => {
    const parsed = RegisterAccountInputSchema.safeParse(req.body);
    if (!parsed.success) {
      res.status(400).json({ error: "Invalid account payload", details: parsed.error.flatten() });
      return;
    }

    const existing = await store.getAccount(parsed.data.dsUserId);
    if (existing && existing.ownerUid !== req.uid) {
      res.status(403).json({ error: "Account is registered to another user" });
      return;
    }

    const tier = existing?.tier ?? initialTierForAccount(parsed.data.accountAgeDays);
    const record: IgAccountRecord = {
      ...parsed.data,
      ownerUid: req.uid!,
      tier,
      status: existing?.status ?? "WARMING_UP",
      cleanDaysStreak: existing?.cleanDaysStreak ?? 0,
      pausedUntil: existing?.pausedUntil ?? null,
      activePlanId: existing?.activePlanId ?? null,
      updatedAt: new Date().toISOString(),
    };

    await store.upsertAccount(record);
    res.status(200).json({ account: record });
  });

  router.post("/plan/generate", async (req: AuthenticatedRequest, res: Response) => {
    const parsed = GeneratePlanBodySchema.safeParse(req.body);
    if (!parsed.success) {
      res.status(400).json({ error: "Invalid request", details: parsed.error.flatten() });
      return;
    }

    const account = await store.getAccount(parsed.data.dsUserId);
    if (!account || account.ownerUid !== req.uid) {
      res.status(404).json({ error: "Instagram account not found" });
      return;
    }

    const generated = await planner.generateSevenDayPlan(account);
    const plan: GrowthPlan = {
      planId: randomUUID(),
      dsUserId: account.dsUserId,
      ownerUid: req.uid!,
      status: "DRAFT",
      strategySummary: generated.strategySummary,
      dailySchedules: generated.dailySchedules.map((d) => ({
        ...d,
        actionBudgets: clampBudgetToTier(d.actionBudgets, account.tier),
      })),
      commentTemplates: generated.commentTemplates,
      createdAt: new Date().toISOString(),
      approvedAt: null,
    };

    await store.savePlan(plan);
    res.status(201).json({ plan });
  });

  router.post("/plan/approve", async (req: AuthenticatedRequest, res: Response) => {
    const parsed = ApprovePlanInputSchema.safeParse(req.body);
    if (!parsed.success) {
      res.status(400).json({ error: "Invalid approval payload", details: parsed.error.flatten() });
      return;
    }

    const account = await store.getAccount(parsed.data.dsUserId);
    if (!account || account.ownerUid !== req.uid) {
      res.status(404).json({ error: "Instagram account not found" });
      return;
    }

    const plan = await store.getPlan(parsed.data.planId);
    if (!plan || plan.dsUserId !== account.dsUserId || plan.ownerUid !== req.uid) {
      res.status(404).json({ error: "Growth plan not found" });
      return;
    }

    const approvedSchedules = (parsed.data.dailySchedules ?? plan.dailySchedules).map((d) => ({
      ...d,
      actionBudgets: clampBudgetToTier(d.actionBudgets, account.tier),
    }));

    const updatedPlan: GrowthPlan = {
      ...plan,
      status: "APPROVED",
      commentTemplates: parsed.data.commentTemplates ?? plan.commentTemplates,
      dailySchedules: approvedSchedules,
      approvedAt: new Date().toISOString(),
    };

    await store.savePlan(updatedPlan);
    await store.upsertAccount({
      ...account,
      activePlanId: updatedPlan.planId,
      status: account.tier === "TIER_1_COLD" ? "WARMING_UP" : "ACTIVE",
      updatedAt: new Date().toISOString(),
    });

    res.status(200).json({ plan: updatedPlan });
  });

  router.get("/tasks/daily", async (req: AuthenticatedRequest, res: Response) => {
    const dsUserId = typeof req.query.dsUserId === "string" ? req.query.dsUserId : "";
    if (!dsUserId) {
      res.status(400).json({ error: "dsUserId query parameter is required" });
      return;
    }

    const account = await store.getAccount(dsUserId);
    if (!account || account.ownerUid !== req.uid) {
      res.status(404).json({ error: "Instagram account not found" });
      return;
    }

    if (!account.activePlanId) {
      res.status(404).json({ error: "No approved growth plan for this account" });
      return;
    }

    const plan = await store.getPlan(account.activePlanId);
    if (!plan || plan.status !== "APPROVED") {
      res.status(404).json({ error: "Active plan not found or not approved" });
      return;
    }

    const approvedAtMs = plan.approvedAt ? Date.parse(plan.approvedAt) : Date.now();
    const daysElapsed = Math.max(0, Math.floor((Date.now() - approvedAtMs) / (24 * 60 * 60 * 1000)));
    const dayIndex = daysElapsed % 7;
    const todayPlan = plan.dailySchedules[dayIndex] ?? plan.dailySchedules[0];

    res.status(200).json({
      dsUserId: account.dsUserId,
      accountStatus: account.status,
      tier: account.tier,
      pausedUntil: account.pausedUntil,
      activeHours: {
        start: account.activeHoursStart,
        end: account.activeHoursEnd,
        timezone: account.timezone,
      },
      dayNumber: todayPlan.dayNumber,
      focus: todayPlan.focus,
      discoverySources: todayPlan.discoverySources,
      actionBudgets: clampBudgetToTier(todayPlan.actionBudgets, account.tier),
      commentTemplates: plan.commentTemplates,
    });
  });

  router.post("/telemetry/batch", async (req: AuthenticatedRequest, res: Response) => {
    const parsed = TelemetryBatchInputSchema.safeParse(req.body);
    if (!parsed.success) {
      res.status(400).json({ error: "Invalid telemetry payload", details: parsed.error.flatten() });
      return;
    }

    const account = await store.getAccount(parsed.data.dsUserId);
    if (!account || account.ownerUid !== req.uid) {
      res.status(404).json({ error: "Instagram account not found" });
      return;
    }

    await store.appendTelemetry({
      dsUserId: account.dsUserId,
      ownerUid: req.uid!,
      events: parsed.data.events,
      receivedAt: new Date().toISOString(),
    });

    const safety = evaluateTelemetrySafety(
      account.status,
      account.tier,
      account.cleanDaysStreak,
      parsed.data.events
    );

    const updatedAccount: IgAccountRecord = {
      ...account,
      followersCount: parsed.data.followersCount ?? account.followersCount,
      status: safety.nextStatus,
      tier: safety.nextTier,
      cleanDaysStreak: safety.nextCleanDaysStreak,
      pausedUntil: safety.pausedUntil,
      updatedAt: new Date().toISOString(),
    };

    await store.upsertAccount(updatedAccount);

    res.status(200).json({
      accepted: parsed.data.events.length,
      accountStatus: updatedAccount.status,
      tier: updatedAccount.tier,
      pausedUntil: updatedAccount.pausedUntil,
      safetyReason: safety.reason,
    });
  });

  const SuggestTargetsSchema = z.object({
    niche: z.string().min(1),
    goal: z.string().optional(),
  });

  router.post("/ai/suggest-targets", async (req: AuthenticatedRequest, res: Response) => {
    const parsed = SuggestTargetsSchema.safeParse(req.body);
    if (!parsed.success) {
      res.status(400).json({ error: "Invalid niche payload", details: parsed.error.flatten() });
      return;
    }
    try {
      const suggestions = await planner.suggestGrowthTargets(parsed.data.niche, parsed.data.goal);
      res.status(200).json(suggestions);
    } catch (err: any) {
      res.status(500).json({ error: "Failed to generate target suggestions", message: err.message });
    }
  });

  const GenerateCommentSchema = z.object({
    postCaption: z.string(),
    niche: z.string().optional(),
    tone: z.string().optional(),
  });

  router.post("/ai/generate-comment", async (req: AuthenticatedRequest, res: Response) => {
    const parsed = GenerateCommentSchema.safeParse(req.body);
    if (!parsed.success) {
      res.status(400).json({ error: "Invalid payload", details: parsed.error.flatten() });
      return;
    }
    try {
      const comment = await planner.generateContextualComment(
        parsed.data.postCaption,
        parsed.data.niche,
        parsed.data.tone
      );
      res.status(200).json({ comment });
    } catch (err: any) {
      res.status(500).json({ error: "Failed to generate contextual comment", message: err.message });
    }
  });

  return router;
}
