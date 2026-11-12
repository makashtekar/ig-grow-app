import { applicationDefault, getApps, initializeApp } from "firebase-admin/app";
import { createApp } from "./app.js";
import {
  GeminiGrowthPlanner,
  HeuristicFallbackPlanner,
  type PlannerService,
} from "./services/geminiPlanner.js";
import {
  FirestoreGrowthStore,
  type GrowthStore,
  InMemoryGrowthStore,
} from "./storage/store.js";

const storeMode = process.env.STORE_MODE || "memory";
const hasFirebaseConfig = Boolean(process.env.FIREBASE_PROJECT_ID?.trim());
const devBypassAuth =
  process.env.DEV_BYPASS_AUTH === "true" ||
  (process.env.DEV_BYPASS_AUTH !== "false" && !hasFirebaseConfig);

if (storeMode === "firestore" || !devBypassAuth) {
  if (getApps().length === 0) {
    initializeApp({
      credential: applicationDefault(),
      projectId: process.env.FIREBASE_PROJECT_ID || undefined,
    });
  }
}

const store: GrowthStore =
  storeMode === "firestore" ? new FirestoreGrowthStore() : new InMemoryGrowthStore();

const rawGeminiKey = (process.env.GEMINI_API_KEY || "").trim();
const hasValidGeminiKey =
  rawGeminiKey.length > 0 && rawGeminiKey !== "your-gemini-api-key";
const planner: PlannerService = hasValidGeminiKey
  ? new GeminiGrowthPlanner(rawGeminiKey)
  : new HeuristicFallbackPlanner();

if (!hasValidGeminiKey) {
  console.warn("[ig-grow-api] GEMINI_API_KEY not configured; using HeuristicFallbackPlanner (no billing required).");
}

const app = createApp({ store, planner, devBypassAuth });
const port = Number(process.env.PORT || 8080);

app.listen(port, () => {
  console.log(`[ig-grow-api] Listening on http://localhost:${port} (store=${storeMode})`);
});
