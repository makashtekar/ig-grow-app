import { createHmac } from "node:crypto";
import type { NextFunction, Request, Response } from "express";

export interface AdminAuthRequest extends Request {
  adminEmail?: string;
}

export function signAdminToken(email: string, secret = getAdminSecret()): string {
  const payload = {
    email,
    iat: Math.floor(Date.now() / 1000),
    exp: Math.floor(Date.now() / 1000) + 7 * 24 * 60 * 60, // 7 days
  };
  const str = Buffer.from(JSON.stringify(payload)).toString("base64url");
  const sig = createHmac("sha256", secret).update(str).digest("base64url");
  return `${str}.${sig}`;
}

export function verifyAdminToken(token: string, secret = getAdminSecret()): { email: string } | null {
  try {
    const parts = token.split(".");
    if (parts.length !== 2) return null;
    const [str, sig] = parts;
    const expectedSig = createHmac("sha256", secret).update(str).digest("base64url");
    if (sig !== expectedSig) return null;
    const payload = JSON.parse(Buffer.from(str, "base64url").toString("utf8"));
    if (payload.exp && payload.exp < Math.floor(Date.now() / 1000)) {
      return null;
    }
    return { email: payload.email };
  } catch {
    return null;
  }
}

export function getAdminSecret(): string {
  return process.env.ADMIN_JWT_SECRET?.trim() || "default-ig-grow-admin-secret-key-12345";
}

export function createAdminAuthMiddleware(secret = getAdminSecret()) {
  return function adminAuthMiddleware(
    req: AdminAuthRequest,
    res: Response,
    next: NextFunction
  ): void {
    const authHeader = req.header("authorization") || "";
    const token = authHeader.startsWith("Bearer ") ? authHeader.slice(7).trim() : "";

    if (!token) {
      res.status(401).json({ error: "Missing admin Bearer token" });
      return;
    }

    const verified = verifyAdminToken(token, secret);
    if (!verified) {
      res.status(401).json({ error: "Invalid or expired admin token" });
      return;
    }

    req.adminEmail = verified.email;
    next();
  };
}
