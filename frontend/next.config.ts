import type { NextConfig } from "next";

// Local / Docker only: proxy /api → Spring Boot. On Vercel, top-level
// vercel.json rewrites send /api/* to the `backend` service, so Next must
// not also rewrite those paths (VERCEL=1 is set in Vercel builds & runtime).
const onVercel = process.env.VERCEL === "1";

const RAW_API_TARGET = process.env.NEXT_PUBLIC_API_BASE_URL || "http://localhost:8080/api";
const API_ORIGIN = RAW_API_TARGET.replace(/\/+$/, "").replace(/\/api$/, "");

const nextConfig: NextConfig = {
  async rewrites() {
    if (onVercel) return [];
    return [{ source: "/api/:path*", destination: `${API_ORIGIN}/api/:path*` }];
  },
};

export default nextConfig;
