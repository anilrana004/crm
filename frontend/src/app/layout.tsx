import type { Metadata } from "next";
import "@/app/globals.css";
import { AuthProvider } from "@/lib/auth";

export const metadata: Metadata = {
  title: "SecureTravels CRM",
  description: "CRM for SecureTravels — Himalayan trekking, adventure tours and pilgrimages",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>
        <AuthProvider>{children}</AuthProvider>
      </body>
    </html>
  );
}