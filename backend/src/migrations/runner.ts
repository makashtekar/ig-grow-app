import type { Firestore } from "firebase-admin/firestore";

export interface Migration {
  id: string;
  name: string;
  up: (db: Firestore) => Promise<void>;
}

export class MigrationRunner {
  private readonly db: Firestore;
  private readonly migrationsCollection = "_ig_grow_migrations";

  constructor(db: Firestore) {
    this.db = db;
  }

  async run(migrations: Migration[]): Promise<{ applied: string[]; skipped: string[] }> {
    const applied: string[] = [];
    const skipped: string[] = [];

    // Ensure tracker collection exists and fetch applied IDs
    const snap = await this.db.collection(this.migrationsCollection).get();
    const appliedIds = new Set(snap.docs.map((doc) => doc.id));

    // Sort migrations deterministically by ID
    const sorted = [...migrations].sort((a, b) => a.id.localeCompare(b.id));

    for (const m of sorted) {
      if (appliedIds.has(m.id)) {
        skipped.push(m.id);
        continue;
      }

      console.log(`[migration] Applying ${m.id}: ${m.name}...`);
      await m.up(this.db);
      await this.db.collection(this.migrationsCollection).doc(m.id).set({
        id: m.id,
        name: m.name,
        appliedAt: new Date().toISOString(),
      });
      applied.push(m.id);
      console.log(`[migration] ✓ Applied ${m.id}`);
    }

    return { applied, skipped };
  }
}
