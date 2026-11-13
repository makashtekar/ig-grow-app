import express, { type Express } from "express";
import { createAuthMiddleware } from "./middleware/auth.js";
import { createAdminRouter } from "./routes/admin.js";
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

  // CORS support for Admin Panel and local dev
  app.use((req, res, next) => {
    res.header("Access-Control-Allow-Origin", "*");
    res.header(
      "Access-Control-Allow-Headers",
      "Origin, X-Requested-With, Content-Type, Accept, Authorization, x-dev-uid"
    );
    res.header("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS");
    if (req.method === "OPTIONS") {
      res.sendStatus(200);
      return;
    }
    next();
  });

  app.get("/health", (_req, res) => {
    res.status(200).json({ ok: true, service: "ig-grow-api" });
  });

  // Client V1 routes
  app.use("/v1", createAuthMiddleware(devBypassAuth), createV1Router(store, planner));

  // Admin routes
  app.use("/admin", createAdminRouter(store));

  return app;
}
