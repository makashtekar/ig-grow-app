import { getFirestore } from "firebase-admin/firestore";
import type { GrowthPlan, IgAccountRecord, TelemetryEvent } from "../types/growth.js";

export interface TelemetryLogEntry {
  dsUserId: string;
  ownerUid: string;
  events: TelemetryEvent[];
  receivedAt: string;
}

export interface GrowthStore {
  upsertAccount(account: IgAccountRecord): Promise<void>;
  getAccount(dsUserId: string): Promise<IgAccountRecord | null>;
  savePlan(plan: GrowthPlan): Promise<void>;
  getPlan(planId: string): Promise<GrowthPlan | null>;
  appendTelemetry(entry: TelemetryLogEntry): Promise<void>;
}

export class InMemoryGrowthStore implements GrowthStore {
  private readonly accounts = new Map<string, IgAccountRecord>();
  private readonly plans = new Map<string, GrowthPlan>();
  private readonly telemetry: TelemetryLogEntry[] = [];

  async upsertAccount(account: IgAccountRecord): Promise<void> {
    this.accounts.set(account.dsUserId, structuredClone(account));
  }

  async getAccount(dsUserId: string): Promise<IgAccountRecord | null> {
    const found = this.accounts.get(dsUserId);
    return found ? structuredClone(found) : null;
  }

  async savePlan(plan: GrowthPlan): Promise<void> {
    this.plans.set(plan.planId, structuredClone(plan));
  }

  async getPlan(planId: string): Promise<GrowthPlan | null> {
    const found = this.plans.get(planId);
    return found ? structuredClone(found) : null;
  }

  async appendTelemetry(entry: TelemetryLogEntry): Promise<void> {
    this.telemetry.push(structuredClone(entry));
  }
}

export class FirestoreGrowthStore implements GrowthStore {
  private readonly db = getFirestore();

  async upsertAccount(account: IgAccountRecord): Promise<void> {
    await this.db.collection("ig_accounts").doc(account.dsUserId).set(account, { merge: true });
  }

  async getAccount(dsUserId: string): Promise<IgAccountRecord | null> {
    const snap = await this.db.collection("ig_accounts").doc(dsUserId).get();
    return snap.exists ? (snap.data() as IgAccountRecord) : null;
  }

  async savePlan(plan: GrowthPlan): Promise<void> {
    await this.db.collection("growth_plans").doc(plan.planId).set(plan, { merge: true });
  }

  async getPlan(planId: string): Promise<GrowthPlan | null> {
    const snap = await this.db.collection("growth_plans").doc(planId).get();
    return snap.exists ? (snap.data() as GrowthPlan) : null;
  }

  async appendTelemetry(entry: TelemetryLogEntry): Promise<void> {
    await this.db.collection("action_logs").add(entry);
  }
}
