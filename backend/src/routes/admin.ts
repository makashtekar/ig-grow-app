import { Router, type Response } from "express";
import { z } from "zod";
import {
  createAdminAuthMiddleware,
  signAdminToken,
  type AdminAuthRequest,
} from "../middleware/adminAuth.js";
import { DailyActionBudgetSchema, WarmupTierSchema } from "../types/growth.js";
import type { GrowthStore } from "../storage/store.js";

const LoginSchema = z.object({
  email: z.string().email(),
  password: z.string().min(1),
});

const UpdateAccountSchema = z.object({
  status: z
    .enum([
      "ACTIVE",
      "WARMING_UP",
      "PAUSED_USER",
      "PAUSED_RATE_LIMIT",
      "PAUSED_CHALLENGE",
      "PAUSED_LOGIN_REQUIRED",
    ])
    .optional(),
  tier: WarmupTierSchema.optional(),
  clearCooldown: z.boolean().optional(),
});

const UpdateSettingsSchema = z.object({
  tierCeilings: z
    .record(DailyActionBudgetSchema)
    .optional(),
});

export function createAdminRouter(store: GrowthStore): Router {
  const router = Router();

  // Public admin login
  router.post("/auth/login", (req, res) => {
    const parsed = LoginSchema.safeParse(req.body);
    if (!parsed.success) {
      res.status(400).json({ error: "Invalid credentials format" });
      return;
    }

    const expectedEmail = (process.env.ADMIN_EMAIL || "admin@iggrow.studio").trim().toLowerCase();
    const expectedPassword = (process.env.ADMIN_PASSWORD || "admin123456").trim();

    if (
      parsed.data.email.trim().toLowerCase() !== expectedEmail ||
      parsed.data.password !== expectedPassword
    ) {
      res.status(401).json({ error: "Invalid admin email or password" });
      return;
    }

    const token = signAdminToken(parsed.data.email);
    res.status(200).json({
      token,
      email: parsed.data.email,
      expiresInDays: 7,
    });
  });

  // Protected admin routes below
  const adminGuard = createAdminAuthMiddleware();
  router.use(adminGuard);

  // Overview KPIs
  router.get("/overview", async (_req: AdminAuthRequest, res: Response) => {
    const accounts = await store.listAccounts();
    const plans = await store.listPlans();
    const recentLogs = await store.listRecentTelemetry(100);

    let activeCount = 0;
    let warmingCount = 0;
    let pausedCount = 0;

    for (const acc of accounts) {
      if (acc.status === "ACTIVE") activeCount++;
      else if (acc.status === "WARMING_UP") warmingCount++;
      else if (acc.status.startsWith("PAUSED")) pausedCount++;
    }

    let errorCount = 0;
    for (const log of recentLogs) {
      for (const ev of log.events) {
        if (ev.outcome !== "SUCCESS") errorCount++;
      }
    }

    res.status(200).json({
      totalAccounts: accounts.length,
      activeAccounts: activeCount,
      warmingAccounts: warmingCount,
      pausedAccounts: pausedCount,
      totalPlans: plans.length,
      recentErrorsCount: errorCount,
      recentLogsCount: recentLogs.length,
    });
  });

  // List Accounts
  router.get("/accounts", async (_req: AdminAuthRequest, res: Response) => {
    const accounts = await store.listAccounts();
    res.status(200).json({ accounts });
  });

  // Update Account Status / Tier
  router.patch("/accounts/:dsUserId", async (req: AdminAuthRequest, res: Response) => {
    const dsUserId = Array.isArray(req.params.dsUserId) ? req.params.dsUserId[0] : req.params.dsUserId;
    const parsed = UpdateAccountSchema.safeParse(req.body);
    if (!parsed.success) {
      res.status(400).json({ error: "Invalid patch format", details: parsed.error.flatten() });
      return;
    }

    const patch: any = {};
    if (parsed.data.status) patch.status = parsed.data.status;
    if (parsed.data.tier) patch.tier = parsed.data.tier;
    if (parsed.data.clearCooldown) patch.pausedUntil = null;

    const updated = await store.updateAccountAdmin(dsUserId, patch);
    if (!updated) {
      res.status(404).json({ error: "Account not found" });
      return;
    }

    res.status(200).json({ account: updated });
  });

  // List Plans
  router.get("/plans", async (_req: AdminAuthRequest, res: Response) => {
    const plans = await store.listPlans();
    res.status(200).json({ plans });
  });

  // List Telemetry Action Logs
  router.get("/logs", async (req: AdminAuthRequest, res: Response) => {
    const limit = Math.min(200, Math.max(1, Number(req.query.limit || 100)));
    const logs = await store.listRecentTelemetry(limit);
    res.status(200).json({ logs });
  });

  // Global Settings
  router.get("/settings", async (_req: AdminAuthRequest, res: Response) => {
    const settings = await store.getGlobalSettings();
    res.status(200).json({ settings });
  });

  router.put("/settings", async (req: AdminAuthRequest, res: Response) => {
    const parsed = UpdateSettingsSchema.safeParse(req.body);
    if (!parsed.success) {
      res.status(400).json({ error: "Invalid settings format", details: parsed.error.flatten() });
      return;
    }

    const updated = await store.saveGlobalSettings(parsed.data);
    res.status(200).json({ settings: updated });
  });

  return router;
}
