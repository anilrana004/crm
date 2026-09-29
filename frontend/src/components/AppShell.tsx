"use client";

import { useEffect, useState, type ReactNode } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { NotificationBell } from "@/components/NotificationBell";

const NAV = [
  { href: "/dashboard", label: "Dashboard", short: "Home" },
  { href: "/leads", label: "Leads", short: "Leads" },
  { href: "/bookings", label: "Bookings", short: "Book" },
  { href: "/payments", label: "Payments", short: "Pay" },
  { href: "/operations", label: "Operations", short: "Ops" },
  { href: "/customers", label: "Customers", short: "Cust" },
  { href: "/trips", label: "Trips & Batches", short: "Trips" },
  { href: "/reports", label: "Reports", short: "Reports" },
];

const BOTTOM = [
  { href: "/dashboard", label: "Home" },
  { href: "/leads", label: "Leads" },
  { href: "/bookings", label: "Bookings" },
  { href: "/payments", label: "Payments" },
];

function navActive(pathname: string, href: string) {
  return pathname === href || pathname.startsWith(`${href}/`);
}

type AppShellProps = {
  children: ReactNode;
  /** Full-height main that scrolls internally (dashboard / customers). */
  flush?: boolean;
  mainClassName?: string;
};

export function AppShell({ children, flush = false, mainClassName = "" }: AppShellProps) {
  const pathname = usePathname();
  const { user, logout } = useAuth();
  const [open, setOpen] = useState(false);

  useEffect(() => {
    setOpen(false);
  }, [pathname]);

  useEffect(() => {
    if (!open) return;
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = prev;
    };
  }, [open]);

  const navLinks = (
    <nav className="flex-1 space-y-1 overflow-y-auto p-3">
      {NAV.map((item) => (
        <Link
          key={item.href}
          href={item.href}
          onClick={() => setOpen(false)}
          className={`block rounded-md px-3 py-2.5 text-sm font-medium ${
            navActive(pathname, item.href)
              ? "bg-slate-900 text-white"
              : "text-slate-600 hover:bg-slate-100"
          }`}
        >
          {item.label}
        </Link>
      ))}
    </nav>
  );

  const userBlock = (
    <div className="border-t border-slate-200 p-3">
      <div className="mb-2 flex items-center justify-between gap-2 px-2">
        {user && (
          <div className="min-w-0 text-xs">
            <div className="truncate font-medium text-slate-800">{user.fullName || user.email}</div>
            <div className="uppercase tracking-wide text-slate-400">{user.role}</div>
          </div>
        )}
        <NotificationBell placement="up" />
      </div>
      <button
        onClick={logout}
        className="w-full rounded-md px-3 py-2.5 text-left text-sm font-medium text-red-600 hover:bg-red-50"
      >
        Sign out
      </button>
    </div>
  );

  return (
    <div className={`flex bg-slate-50 ${flush ? "h-dvh overflow-hidden" : "min-h-dvh"}`}>
      {/* Desktop sidebar */}
      <aside className="hidden w-56 shrink-0 flex-col border-r border-slate-200 bg-white md:flex">
        <div className="border-b border-slate-200 px-5 py-4">
          <div className="text-sm font-semibold text-slate-900">SecureTravels</div>
          <div className="text-xs text-slate-400">CRM · Phase 3</div>
        </div>
        {navLinks}
        {userBlock}
      </aside>

      {/* Mobile top bar */}
      <header className="fixed inset-x-0 top-0 z-40 flex h-14 items-center gap-3 border-b border-slate-200 bg-white/95 px-3 backdrop-blur md:hidden safe-top">
        <button
          type="button"
          aria-label="Open menu"
          onClick={() => setOpen(true)}
          className="flex h-10 w-10 items-center justify-center rounded-md text-slate-700 hover:bg-slate-100"
        >
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
            <path d="M4 6h16M4 12h16M4 18h16" />
          </svg>
        </button>
        <div className="min-w-0 flex-1">
          <div className="truncate text-sm font-semibold text-slate-900">SecureTravels</div>
          <div className="truncate text-[11px] text-slate-400">CRM</div>
        </div>
        <NotificationBell placement="down" />
      </header>

      {/* Mobile drawer */}
      {open && (
        <div className="fixed inset-0 z-50 md:hidden">
          <button
            type="button"
            aria-label="Close menu"
            className="absolute inset-0 bg-slate-900/40"
            onClick={() => setOpen(false)}
          />
          <aside className="absolute inset-y-0 left-0 flex w-[min(20rem,88vw)] flex-col bg-white shadow-xl safe-top safe-bottom">
            <div className="flex items-center justify-between border-b border-slate-200 px-4 py-3">
              <div>
                <div className="text-sm font-semibold text-slate-900">SecureTravels</div>
                <div className="text-xs text-slate-400">CRM · Phase 3</div>
              </div>
              <button
                type="button"
                aria-label="Close"
                onClick={() => setOpen(false)}
                className="flex h-10 w-10 items-center justify-center rounded-md text-slate-500 hover:bg-slate-100"
              >
                ✕
              </button>
            </div>
            {navLinks}
            {userBlock}
          </aside>
        </div>
      )}

      {/* Mobile bottom nav */}
      <nav className="fixed inset-x-0 bottom-0 z-40 flex border-t border-slate-200 bg-white/95 backdrop-blur md:hidden safe-bottom">
        {BOTTOM.map((item) => {
          const active = navActive(pathname, item.href);
          return (
            <Link
              key={item.href}
              href={item.href}
              className={`flex min-h-12 flex-1 flex-col items-center justify-center gap-0.5 px-1 text-[11px] font-medium ${
                active ? "text-slate-900" : "text-slate-400"
              }`}
            >
              <span className={`h-1 w-1 rounded-full ${active ? "bg-slate-900" : "bg-transparent"}`} />
              {item.label}
            </Link>
          );
        })}
        <button
          type="button"
          onClick={() => setOpen(true)}
          className="flex min-h-12 flex-1 flex-col items-center justify-center gap-0.5 px-1 text-[11px] font-medium text-slate-400"
        >
          <span className="h-1 w-1 rounded-full bg-transparent" />
          More
        </button>
      </nav>

      <main
        className={[
          "min-w-0 flex-1",
          flush ? "flex flex-col overflow-hidden" : "",
          "pt-14 pb-[calc(3.5rem+env(safe-area-inset-bottom))] md:pt-0 md:pb-0",
          mainClassName,
        ]
          .filter(Boolean)
          .join(" ")}
      >
        {children}
      </main>
    </div>
  );
}
