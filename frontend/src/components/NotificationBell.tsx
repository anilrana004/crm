"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { api, type NotificationFeed, type NotificationItem } from "@/lib/api";

function timeAgo(iso: string | undefined): string {
  if (!iso) return "";
  const diff = Date.now() - new Date(iso).getTime();
  const mins = Math.floor(diff / 60_000);
  if (mins < 1) return "just now";
  if (mins < 60) return `${mins}m ago`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}h ago`;
  return `${Math.floor(hours / 24)}d ago`;
}

const EMPTY: NotificationFeed = { items: [], unread: 0 };

export function NotificationBell() {
  const router = useRouter();
  const [feed, setFeed] = useState<NotificationFeed>(EMPTY);
  const [open, setOpen] = useState(false);
  const panelRef = useRef<HTMLDivElement>(null);

  const load = useCallback(async () => {
    try {
      const data = await api.getNotifications();
      setFeed(data);
    } catch {
      /* 401 redirect handled inside api.request */
    }
  }, []);

  useEffect(() => {
    load();
    const timer = setInterval(load, 60_000);
    return () => clearInterval(timer);
  }, [load]);

  useEffect(() => {
    function onOutside(e: MouseEvent) {
      if (panelRef.current && !panelRef.current.contains(e.target as Node)) setOpen(false);
    }
    document.addEventListener("mousedown", onOutside);
    return () => document.removeEventListener("mousedown", onOutside);
  }, []);

  const openItem = async (item: NotificationItem) => {
    if (!item.read && item.id) {
      try {
        await api.markNotificationRead(item.id);
        setFeed((f) => ({
          items: f.items.map((i) => (i.id === item.id ? { ...i, read: true } : i)),
          unread: Math.max(0, f.unread - 1),
        }));
      } catch {
        /* ignore */
      }
    }
    setOpen(false);
    if (item.link?.startsWith("/")) router.push(item.link);
  };

  const markAll = async () => {
    try {
      await api.markAllNotificationsRead();
      setFeed((f) => ({ items: f.items.map((i) => ({ ...i, read: true })), unread: 0 }));
    } catch {
      /* ignore */
    }
  };

  return (
    <div ref={panelRef} className="relative">
      <button
        onClick={() => setOpen((o) => !o)}
        aria-label="Notifications"
        className="relative rounded-md p-1.5 text-slate-500 hover:bg-slate-100 hover:text-slate-800"
      >
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <path d="M18 8a6 6 0 1 0-12 0c0 7-3 9-3 9h18s-3-2-3-9" />
          <path d="M13.7 21a2 2 0 0 1-3.4 0" />
        </svg>
        {feed.unread > 0 && (
          <span className="absolute -top-0.5 -right-0.5 min-w-4 rounded-full bg-red-600 px-1 text-center text-[10px] font-bold leading-4 text-white">
            {feed.unread > 99 ? "99+" : feed.unread}
          </span>
        )}
      </button>

      {open && (
        <div className="absolute bottom-full right-0 z-50 mb-2 flex max-h-[70vh] w-80 flex-col overflow-hidden rounded-xl border border-slate-200 bg-white shadow-lg">
          <div className="flex shrink-0 items-center justify-between border-b border-slate-100 px-4 py-2.5">
            <span className="text-sm font-semibold text-slate-900">Notifications</span>
            {feed.unread > 0 && (
              <button onClick={markAll} className="text-xs font-medium text-slate-500 hover:text-slate-800">
                Mark all read
              </button>
            )}
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto">
            {feed.items.length === 0 ? (
              <p className="px-4 py-8 text-center text-sm text-slate-400">You&apos;re all caught up.</p>
            ) : (
              feed.items.map((item) => (
                <button
                  key={item.id}
                  onClick={() => openItem(item)}
                  className={`block w-full border-b border-slate-50 px-4 py-3 text-left hover:bg-slate-50 ${
                    item.read ? "opacity-70" : ""
                  }`}
                >
                  <div className="flex items-start gap-2">
                    {!item.read && <span className="mt-1.5 h-2 w-2 shrink-0 rounded-full bg-red-500" />}
                    <div className="min-w-0 flex-1">
                      <div className={`text-sm text-slate-900 ${item.read ? "font-normal" : "font-semibold"}`}>
                        {item.title}
                      </div>
                      {item.body && <div className="mt-0.5 text-xs text-slate-500">{item.body}</div>}
                      <div className="mt-0.5 text-xs text-slate-400">{timeAgo(item.createdAt)}</div>
                    </div>
                  </div>
                </button>
              ))
            )}
          </div>
        </div>
      )}
    </div>
  );
}