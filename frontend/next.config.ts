import type { NextConfig } from "next";

// On Vercel, /api is handled by app/api/[...path] (demo mode).
// Locally the catch-all proxies to Spring; this rewrite remains as fallback.
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
