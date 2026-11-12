import type { NextFunction, Request, Response } from "express";
import { getAuth } from "firebase-admin/auth";

export interface AuthenticatedRequest extends Request {
  uid?: string;
}

export function createAuthMiddleware(devBypassAuth = process.env.DEV_BYPASS_AUTH === "true") {
  return async function authMiddleware(
    req: AuthenticatedRequest,
    res: Response,
    next: NextFunction
  ): Promise<void> {
    if (devBypassAuth) {
      const devUid = req.header("x-dev-uid") || "dev-local-user";
      req.uid = devUid;
      next();
      return;
    }

    const authHeader = req.header("authorization") || "";
    const token = authHeader.startsWith("Bearer ") ? authHeader.slice(7).trim() : "";

    if (!token) {
      res.status(401).json({ error: "Missing Bearer token" });
      return;
    }

    try {
      const decoded = await getAuth().verifyIdToken(token);
      req.uid = decoded.uid;
      next();
    } catch {
      res.status(401).json({ error: "Invalid or expired Firebase ID token" });
    }
  };
}
