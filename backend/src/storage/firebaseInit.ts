import { applicationDefault, cert, getApps, initializeApp } from "firebase-admin/app";
import { getFirestore, type Firestore } from "firebase-admin/firestore";

export function initFirebaseAdmin(): Firestore | null {
  const projectId = process.env.FIREBASE_PROJECT_ID?.trim();
  const clientEmail = process.env.FIREBASE_CLIENT_EMAIL?.trim();
  const privateKey = process.env.FIREBASE_PRIVATE_KEY?.trim();

  if (getApps().length > 0) {
    return getFirestore();
  }

  // 1. Direct credentials matching earn-share-admin-panel
  if (projectId && clientEmail && privateKey) {
    initializeApp({
      credential: cert({
        projectId,
        clientEmail,
        privateKey: privateKey.replace(/\\n/g, "\n"),
      }),
      projectId,
    });
    console.log(`[firebase-admin] Initialized with service account for project: ${projectId}`);
    return getFirestore();
  }

  // 2. Google Application Default Credentials fallback (e.g. GOOGLE_APPLICATION_CREDENTIALS)
  if (process.env.GOOGLE_APPLICATION_CREDENTIALS || projectId) {
    try {
      initializeApp({
        credential: applicationDefault(),
        projectId: projectId || undefined,
      });
      console.log(`[firebase-admin] Initialized with applicationDefault credentials`);
      return getFirestore();
    } catch (err: any) {
      console.warn(`[firebase-admin] Could not initialize applicationDefault: ${err.message}`);
    }
  }

  return null;
}
