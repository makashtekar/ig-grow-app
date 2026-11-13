import type { Firestore } from "firebase-admin/firestore";
import { TIER_CEILINGS } from "../safety/safetyCeilings.js";
import type { Migration } from "./runner.js";

export const migration001: Migration = {
  id: "001_init_collections",
  name: "Initialize ig_grow_settings/global with default tier safety ceilings",
  up: async (db: Firestore) => {
    const settingsRef = db.collection("ig_grow_settings").doc("global");
    const doc = await settingsRef.get();
    if (!doc.exists) {
      await settingsRef.set({
        schemaVersion: 1,
        tierCeilings: TIER_CEILINGS,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      });
    }
  },
};
