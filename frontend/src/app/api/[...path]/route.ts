import { NextRequest, NextResponse } from "next/server";
import {
  DEMO_LEADS,
  DEMO_TRIPS,
  DEMO_USERS,
  isDemoMode,
  issueTokens,
  publicUser,
  userFromAuthHeader,
} from "@/lib/demoData";

export const dynamic = "force-dynamic";

type Ctx = { params: Promise<{ path?: string[] }> };

function json(data: unknown, status = 200) {
  return NextResponse.json(data, { status });
}

function unauthorized() {
  return json({ message: "Unauthorized", code: "UNAUTHORIZED" }, 401);
}

function notFound(message = "Not found") {
  return json({ message, code: "NOT_FOUND" }, 404);
}

async function proxyToBackend(req: NextRequest, pathParts: string[]) {
  const raw = process.env.NEXT_PUBLIC_API_BASE_URL || "http://localhost:8080/api";
  const origin = raw.replace(/\/+$/, "").replace(/\/api$/, "");
  const suffix = pathParts.join("/");
  const url = new URL(req.url);
  const target = `${origin}/api/${suffix}${url.search}`;

  const headers = new Headers(req.headers);
  headers.delete("host");

  const init: RequestInit = {
    method: req.method,
    headers,
    redirect: "manual",
  };
  if (req.method !== "GET" && req.method !== "HEAD") {
    init.body = await req.arrayBuffer();
  }

  const res = await fetch(target, init);
  const body = await res.arrayBuffer();
  const out = new NextResponse(body, { status: res.status });
  const ct = res.headers.get("content-type");
  if (ct) out.headers.set("content-type", ct);
  return out;
}

async function handleDemo(req: NextRequest, parts: string[]) {
  const path = parts.join("/");
  const method = req.method.toUpperCase();
  const auth = req.headers.get("authorization");

  // ---- Auth (public) ----
  if (path === "auth/login" && method === "POST") {
    const body = (await req.json().catch(() => ({}))) as { email?: string; password?: string };
    const user = DEMO_USERS.find(
      (u) => u.email.toLowerCase() === (body.email || "").toLowerCase() && u.password === body.password,
    );
    if (!user) return json({ message: "Invalid email or password", code: "INVALID_CREDENTIALS" }, 401);
    return json(issueTokens(user));
  }

  if (path === "auth/refresh" && method === "POST") {
    const body = (await req.json().catch(() => ({}))) as { refreshToken?: string };
    const id = body.refreshToken?.split(".")[1];
    const user = DEMO_USERS.find((u) => u.id === id);
    if (!user) return unauthorized();
    return json(issueTokens(user));
  }

  if (path === "auth/logout" && method === "POST") {
    return new NextResponse(null, { status: 204 });
  }

  if (path === "auth/me" && method === "GET") {
    const user = userFromAuthHeader(auth);
    if (!user) return unauthorized();
    return json(publicUser(user));
  }

  // Everything else requires a demo session
  const session = userFromAuthHeader(auth);
  if (!session && path !== "health") {
    // health is public on real API; mirror that
    if (path === "health" && method === "GET") {
      return json({ status: "UP", service: "securetravels-crm-demo", mode: "demo" });
    }
    return unauthorized();
  }

  if (path === "health" && method === "GET") {
    return json({ status: "UP", service: "securetravels-crm-demo", mode: "demo" });
  }

  if (path === "leads" && method === "GET") {
    const url = new URL(req.url);
    const status = url.searchParams.get("status") || "";
    const search = (url.searchParams.get("search") || "").toLowerCase();
    const page = Number(url.searchParams.get("page") || "0");
    const size = Number(url.searchParams.get("size") || "20");
    let rows = [...DEMO_LEADS];
    if (status) rows = rows.filter((l) => l.status === status);
    if (search) {
      rows = rows.filter(
        (l) =>
          l.customerName.toLowerCase().includes(search) ||
          l.mobileNumber.includes(search),
      );
    }
    const start = page * size;
    const content = rows.slice(start, start + size);
    return json({
      content,
      totalElements: rows.length,
      totalPages: Math.ceil(rows.length / size) || 1,
      number: page,
      size,
    });
  }

  if (path.startsWith("leads/") && method === "GET") {
    const id = parts[1];
    if (parts[2] === "activity") return json([]);
    const lead = DEMO_LEADS.find((l) => l.id === id);
    if (!lead) return notFound("Lead not found");
    return json(lead);
  }

  if (path === "trips" && method === "GET") {
    const active = new URL(req.url).searchParams.get("active");
    let rows = [...DEMO_TRIPS];
    if (active === "true") rows = rows.filter((t) => t.active);
    if (active === "false") rows = rows.filter((t) => !t.active);
    return json(rows);
  }

  if (path.startsWith("trips/") && method === "GET") {
    const trip = DEMO_TRIPS.find((t) => t.id === parts[1]);
    if (!trip) return notFound("Trip not found");
    return json({ ...trip, batches: [], itinerary: "", inclusions: "", exclusions: "" });
  }

  if (path === "notifications" && method === "GET") {
    return json({
      items: [
        {
          id: "44444444-4444-4444-4444-444444444401",
          title: "Call within 5 minutes",
          body: "New demo lead assigned — call the customer now.",
          read: false,
          createdAt: new Date().toISOString(),
          link: `/leads/${DEMO_LEADS[0].id}`,
        },
      ],
      unread: 1,
    });
  }

  if (path.startsWith("notifications") && (method === "POST" || method === "PATCH")) {
    return json({ ok: true });
  }

  if (path === "vendors" && method === "GET") {
    return json([]);
  }

  if (path === "bookings" && method === "GET") return json([]);
  if (path === "payments" && method === "GET") return json([]);
  if (path === "operations" && method === "GET") return json([]);
  if (path === "customers" && method === "GET") return json([]);
  if (path === "tasks" && method === "GET") return json([]);

  if (path === "dashboard/summary" && method === "GET") {
    return json({
      period: "month",
      totalLeads: DEMO_LEADS.length,
      newLeads: DEMO_LEADS.filter((l) => l.status === "NEW").length,
      followUpDue: 2,
      interested: DEMO_LEADS.filter((l) => l.status === "INTERESTED").length,
      quotationSent: DEMO_LEADS.filter((l) => l.status === "QUOTATION_SENT").length,
      bookingConfirmed: DEMO_LEADS.filter((l) => l.status === "BOOKING_CONFIRMED").length,
      lost: DEMO_LEADS.filter((l) => l.status === "LOST").length,
      newBookings: 1,
      confirmedBookings: 1,
      revenue: 185000,
    });
  }

  if (path === "dashboard/performance" && method === "GET") {
    return json({
      employees: DEMO_USERS.filter((u) => u.role === "SALES").map((u) => ({
        userId: u.id,
        fullName: u.fullName,
        email: u.email,
        leadsAssigned: 3,
        followUpsCompleted: 2,
        bookingsClosed: 1,
        paymentsCount: 1,
        revenue: 45000,
      })),
      totals: {
        leadsAssigned: 6,
        followUpsCompleted: 4,
        bookingsClosed: 2,
        revenue: 90000,
      },
    });
  }

  if (path === "targets" && method === "GET") {
    return json({ targets: [], overall: null });
  }

  // Mutations / unknown demo endpoints: accept soft success so the UI stays usable
  if (method === "POST" || method === "PATCH" || method === "PUT" || method === "DELETE") {
    return json({ ok: true, demo: true, message: "Demo mode — change not persisted" });
  }

  return json([]);
}

async function dispatch(req: NextRequest, ctx: Ctx) {
  const { path = [] } = await ctx.params;
  if (isDemoMode()) return handleDemo(req, path);
  return proxyToBackend(req, path);
}

export const GET = dispatch;
export const POST = dispatch;
export const PATCH = dispatch;
export const PUT = dispatch;
export const DELETE = dispatch;
