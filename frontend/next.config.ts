import type { NextConfig } from "next";

const API_TARGET = process.env.NEXT_PUBLIC_API_BASE_URL || "http://localhost:8080/api";

const nextConfig: NextConfig = {
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${API_TARGET}/:path*` }];
  },
};

export default nextConfig;