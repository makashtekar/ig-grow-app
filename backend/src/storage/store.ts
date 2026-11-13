import { getFirestore, type Firestore } from "firebase-admin/firestore";
import { TIER_CEILINGS } from "../safety/safetyCeilings.js";
import type { DailyActionBudget, GrowthPlan, IgAccountRecord, TelemetryEvent } from "../types/growth.js";

export interface TelemetryLogEntry {
  id?: string;
  dsUserId: string;
  ownerUid: string;
  events: TelemetryEvent[];
  receivedAt: string;
}

export interface GlobalSettings {
  schemaVersion: number;
  tierCeilings: Record<string, DailyActionBudget>;
  updatedAt: string;
}

export interface GrowthStore {
  upsertAccount(account: IgAccountRecord): Promise<void>;
  getAccount(dsUserId: string): Promise<IgAccountRecord | null>;
  listAccounts(): Promise<IgAccountRecord[]>;
  updateAccountAdmin(dsUserId: string, patch: Partial<IgAccountRecord>): Promise<IgAccountRecord | null>;

  savePlan(plan: GrowthPlan): Promise<void>;
  getPlan(planId: string): Promise<GrowthPlan | null>;
  listPlans(): Promise<GrowthPlan[]>;

  appendTelemetry(entry: TelemetryLogEntry): Promise<void>;
  listRecentTelemetry(limit?: number): Promise<TelemetryLogEntry[]>;

  getGlobalSettings(): Promise<GlobalSettings>;
  saveGlobalSettings(settings: Partial<GlobalSettings>): Promise<GlobalSettings>;
}

export class InMemoryGrowthStore implements GrowthStore {
  private readonly accounts = new Map<string, IgAccountRecord>();
  private readonly plans = new Map<string, GrowthPlan>();
  private readonly telemetry: TelemetryLogEntry[] = [];
  private settings: GlobalSettings = {
    schemaVersion: 1,
    tierCeilings: structuredClone(TIER_CEILINGS),
    updatedAt: new Date().toISOString(),
  };

  async upsertAccount(account: IgAccountRecord): Promise<void> {
    this.accounts.set(account.dsUserId, structuredClone(account));
  }

  async getAccount(dsUserId: string): Promise<IgAccountRecord | null> {
    const found = this.accounts.get(dsUserId);
    return found ? structuredClone(found) : null;
  }

  async listAccounts(): Promise<IgAccountRecord[]> {
    return Array.from(this.accounts.values()).map((a) => structuredClone(a));
  }

  async updateAccountAdmin(
    dsUserId: string,
    patch: Partial<IgAccountRecord>
  ): Promise<IgAccountRecord | null> {
    const existing = this.accounts.get(dsUserId);
    if (!existing) return null;
    const updated: IgAccountRecord = {
      ...existing,
      ...patch,
      updatedAt: new Date().toISOString(),
    };
    this.accounts.set(dsUserId, updated);
    return structuredClone(updated);
  }

  async savePlan(plan: GrowthPlan): Promise<void> {
    this.plans.set(plan.planId, structuredClone(plan));
  }

  async getPlan(planId: string): Promise<GrowthPlan | null> {
    const found = this.plans.get(planId);
    return found ? structuredClone(found) : null;
  }

  async listPlans(): Promise<GrowthPlan[]> {
    return Array.from(this.plans.values()).map((p) => structuredClone(p));
  }

  async appendTelemetry(entry: TelemetryLogEntry): Promise<void> {
    this.telemetry.unshift({
      ...structuredClone(entry),
      id: entry.id || `mem_${Date.now()}_${Math.random().toString(36).substring(2, 7)}`,
    });
  }

  async listRecentTelemetry(limit = 100): Promise<TelemetryLogEntry[]> {
    return this.telemetry.slice(0, limit).map((t) => structuredClone(t));
  }

  async getGlobalSettings(): Promise<GlobalSettings> {
    return structuredClone(this.settings);
  }

  async saveGlobalSettings(patch: Partial<GlobalSettings>): Promise<GlobalSettings> {
    this.settings = {
      ...this.settings,
      ...patch,
      updatedAt: new Date().toISOString(),
    };
    return structuredClone(this.settings);
  }
}

export class FirestoreGrowthStore implements GrowthStore {
  private readonly db: Firestore;

  constructor(dbInstance?: Firestore) {
    this.db = dbInstance || getFirestore();
  }

  private accountsCol() {
    return this.db.collection("ig_grow_accounts");
  }

  private plansCol() {
    return this.db.collection("ig_grow_plans");
  }

  private logsCol() {
    return this.db.collection("ig_grow_action_logs");
  }

  private settingsDoc() {
    return this.db.collection("ig_grow_settings").doc("global");
  }

  async upsertAccount(account: IgAccountRecord): Promise<void> {
    await this.accountsCol().doc(account.dsUserId).set(account, { merge: true });
  }

  async getAccount(dsUserId: string): Promise<IgAccountRecord | null> {
    const snap = await this.accountsCol().doc(dsUserId).get();
    return snap.exists ? (snap.data() as IgAccountRecord) : null;
  }

  async listAccounts(): Promise<IgAccountRecord[]> {
    const snap = await this.accountsCol().get();
    return snap.docs.map((doc) => doc.data() as IgAccountRecord);
  }

  async updateAccountAdmin(
    dsUserId: string,
    patch: Partial<IgAccountRecord>
  ): Promise<IgAccountRecord | null> {
    const ref = this.accountsCol().doc(dsUserId);
    const snap = await ref.get();
    if (!snap.exists) return null;
    const updated = {
      ...snap.data(),
      ...patch,
      updatedAt: new Date().toISOString(),
    } as IgAccountRecord;
    await ref.set(updated, { merge: true });
    return updated;
  }

  async savePlan(plan: GrowthPlan): Promise<void> {
    await this.plansCol().doc(plan.planId).set(plan, { merge: true });
  }

  async getPlan(planId: string): Promise<GrowthPlan | null> {
    const snap = await this.plansCol().doc(planId).get();
    return snap.exists ? (snap.data() as GrowthPlan) : null;
  }

  async listPlans(): Promise<GrowthPlan[]> {
    const snap = await this.plansCol().get();
    return snap.docs.map((doc) => doc.data() as GrowthPlan);
  }

  async appendTelemetry(entry: TelemetryLogEntry): Promise<void> {
    await this.logsCol().add(entry);
  }

  async listRecentTelemetry(limit = 100): Promise<TelemetryLogEntry[]> {
    const snap = await this.logsCol()
      .orderBy("receivedAt", "desc")
      .limit(limit)
      .get();
    return snap.docs.map((doc) => ({
      id: doc.id,
      ...(doc.data() as Omit<TelemetryLogEntry, "id">),
    }));
  }

  async getGlobalSettings(): Promise<GlobalSettings> {
    const snap = await this.settingsDoc().get();
    if (snap.exists) {
      return snap.data() as GlobalSettings;
    }
    const defaults: GlobalSettings = {
      schemaVersion: 1,
      tierCeilings: structuredClone(TIER_CEILINGS),
      updatedAt: new Date().toISOString(),
    };
    await this.settingsDoc().set(defaults);
    return defaults;
  }

  async saveGlobalSettings(patch: Partial<GlobalSettings>): Promise<GlobalSettings> {
    const current = await this.getGlobalSettings();
    const updated: GlobalSettings = {
      ...current,
      ...patch,
      updatedAt: new Date().toISOString(),
    };
    await this.settingsDoc().set(updated, { merge: true });
    return updated;
  }
}
