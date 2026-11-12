import express, { type Express } from "express";
import { createAuthMiddleware } from "./middleware/auth.js";
import { createV1Router } from "./routes/v1.js";
import type { PlannerService } from "./services/geminiPlanner.js";
import type { GrowthStore } from "./storage/store.js";

export interface AppDependencies {
  store: GrowthStore;
  planner: PlannerService;
  devBypassAuth?: boolean;
}

export function createApp({
  store,
  planner,
  devBypassAuth = process.env.DEV_BYPASS_AUTH === "true",
}: AppDependencies): Express {
  const app = express();
  app.use(express.json({ limit: "1mb" }));

  app.get("/health", (_req, res) => {
    res.status(200).json({ ok: true, service: "ig-grow-api" });
  });

  app.use("/v1", createAuthMiddleware(devBypassAuth), createV1Router(store, planner));

  return app;
}
