import { initFirebaseAdmin } from "../storage/firebaseInit.js";
import { migration001 } from "./001_init_collections.js";
import { MigrationRunner } from "./runner.js";

async function main() {
  console.log("[migrations-cli] Starting Firestore migrations...");
  const db = initFirebaseAdmin();
  if (!db) {
    console.error(
      "[migrations-cli] ❌ Firestore could not be initialized. Please check FIREBASE_PROJECT_ID, FIREBASE_CLIENT_EMAIL, and FIREBASE_PRIVATE_KEY in .env."
    );
    process.exit(1);
  }

  const runner = new MigrationRunner(db);
  const result = await runner.run([migration001]);

  console.log(
    `[migrations-cli] Done. Applied: ${result.applied.length}, Skipped (already applied): ${result.skipped.length}`
  );
  process.exit(0);
}

main().catch((err) => {
  console.error("[migrations-cli] Error running migrations:", err);
  process.exit(1);
});
