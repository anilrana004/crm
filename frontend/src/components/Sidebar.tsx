"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { NotificationBell } from "@/components/NotificationBell";

const NAV = [
  { href: "/dashboard", label: "Dashboard" },
  { href: "/leads", label: "Leads" },
  { href: "/bookings", label: "Bookings" },
  { href: "/payments", label: "Payments" },
  { href: "/operations", label: "Operations" },
  { href: "/customers", label: "Customers" },
  { href: "/trips", label: "Trips & Batches" },
  { href: "/login", label: "Login" },
];

export function Sidebar() {
  const pathname = usePathname();
  const { user, logout } = useAuth();

  return (
    <aside className="flex w-56 shrink-0 flex-col border-r border-slate-200 bg-white">
      <div className="border-b border-slate-200 px-5 py-4">
        <div className="text-sm font-semibold text-slate-900">SecureTravels</div>
        <div className="text-xs text-slate-400">CRM · Phase 1</div>
      </div>
      <nav className="flex-1 space-y-1 p-3">
        {NAV.filter((n) => n.href !== "/login").map((item) => (
          <Link
            key={item.href}
            href={item.href}
            className={`block rounded-md px-3 py-2 text-sm font-medium ${
              pathname.startsWith(item.href)
                ? "bg-slate-900 text-white"
                : "text-slate-600 hover:bg-slate-100"
            }`}
          >
            {item.label}
          </Link>
        ))}
      </nav>
      <div className="border-t border-slate-200 p-3">
        <div className="mb-2 flex items-center justify-between px-2">
          {user && (
            <div className="text-xs">
              <div className="font-medium text-slate-800">{user.fullName || user.email}</div>
              <div className="uppercase tracking-wide text-slate-400">{user.role}</div>
            </div>
          )}
          <NotificationBell />
        </div>
        <button
          onClick={logout}
          className="w-full rounded-md px-3 py-2 text-left text-sm font-medium text-red-600 hover:bg-red-50"
        >
          Sign out
        </button>
      </div>
    </aside>
  );
}