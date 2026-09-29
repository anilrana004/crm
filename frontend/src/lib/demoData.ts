/**
 * In-memory demo API for client walkthroughs on Vercel (no Spring Boot / DB).
 * Enabled when VERCEL=1 (unless DEMO_MODE=false) or DEMO_MODE=true.
 */

export type DemoUser = {
  id: string;
  email: string;
  password: string;
  fullName: string;
  role: "SALES" | "OPS" | "MANAGER" | "ADMIN" | "CEO";
};

export const DEMO_USERS: DemoUser[] = [
  {
    id: "11111111-1111-1111-1111-111111111101",
    email: "admin@securetravels.in",
    password: "admin123",
    fullName: "Admin User",
    role: "ADMIN",
  },
  {
    id: "11111111-1111-1111-1111-111111111102",
    email: "manager@securetravels.in",
    password: "manager123",
    fullName: "Manager User",
    role: "MANAGER",
  },
  {
    id: "11111111-1111-1111-1111-111111111103",
    email: "sales.ravi@securetravels.in",
    password: "sales123",
    fullName: "Ravi Sales",
    role: "SALES",
  },
  {
    id: "11111111-1111-1111-1111-111111111104",
    email: "sales.meera@securetravels.in",
    password: "sales123",
    fullName: "Meera Sales",
    role: "SALES",
  },
  {
    id: "11111111-1111-1111-1111-111111111105",
    email: "ops.suresh@securetravels.in",
    password: "ops123",
    fullName: "Suresh Ops",
    role: "OPS",
  },
];

export function isDemoMode(): boolean {
  if (process.env.DEMO_MODE === "true") return true;
  if (process.env.DEMO_MODE === "false") return false;
  return process.env.VERCEL === "1";
}

export function publicUser(u: DemoUser) {
  return { id: u.id, email: u.email, fullName: u.fullName, role: u.role };
}

/** Opaque demo tokens — enough for the UI session, not real JWTs. */
export function issueTokens(user: DemoUser) {
  const accessToken = `demo-access.${user.id}.${Date.now()}`;
  const refreshToken = `demo-refresh.${user.id}.${Date.now()}`;
  return {
    accessToken,
    refreshToken,
    tokenType: "Bearer",
    expiresInSeconds: 60 * 60 * 8,
    user: publicUser(user),
  };
}

export function userFromAuthHeader(auth: string | null): DemoUser | null {
  if (!auth?.startsWith("Bearer ")) return null;
  const token = auth.slice(7);
  const parts = token.split(".");
  if (parts[0] !== "demo-access" || !parts[1]) return null;
  return DEMO_USERS.find((u) => u.id === parts[1]) ?? null;
}

export const DEMO_TRIPS = [
  {
    id: "22222222-2222-2222-2222-222222222201",
    name: "Kedarnath Yatra",
    slug: "kedarnath-yatra",
    category: "PILGRIMAGE",
    bookingType: "FIXED_BATCH",
    difficulty: "MODERATE",
    baseCost: 18500,
    durationDays: 6,
    active: true,
  },
  {
    id: "22222222-2222-2222-2222-222222222202",
    name: "Kedarkantha Trek",
    slug: "kedarkantha-trek",
    category: "TREK",
    bookingType: "FIXED_BATCH",
    difficulty: "MODERATE",
    baseCost: 9999,
    durationDays: 5,
    active: true,
  },
];

export const DEMO_LEADS = [
  {
    id: "33333333-3333-3333-3333-333333333301",
    customerName: "Amit Sharma",
    mobileNumber: "9876543210",
    email: "amit@example.com",
    source: "WEBSITE",
    status: "NEW",
    heat: "HOT",
    destination: "Kedarnath",
    tripId: DEMO_TRIPS[0].id,
    travelDate: "2026-10-15",
    budget: 40000,
    numPersons: 2,
    ownerId: DEMO_USERS[2].id,
    ownerName: DEMO_USERS[2].fullName,
    consentGiven: true,
  },
  {
    id: "33333333-3333-3333-3333-333333333302",
    customerName: "Neha Gupta",
    mobileNumber: "9876501234",
    email: "neha@example.com",
    source: "INSTAGRAM",
    status: "INTERESTED",
    heat: "WARM",
    destination: "Kedarkantha",
    tripId: DEMO_TRIPS[1].id,
    travelDate: "2026-11-02",
    budget: 25000,
    numPersons: 1,
    ownerId: DEMO_USERS[3].id,
    ownerName: DEMO_USERS[3].fullName,
    consentGiven: true,
  },
  {
    id: "33333333-3333-3333-3333-333333333303",
    customerName: "Rahul Verma",
    mobileNumber: "9988776655",
    source: "WHATSAPP",
    status: "QUOTATION_SENT",
    heat: "HOT",
    destination: "Kedarnath",
    tripId: DEMO_TRIPS[0].id,
    travelDate: "2026-10-20",
    budget: 55000,
    numPersons: 3,
    ownerId: DEMO_USERS[2].id,
    ownerName: DEMO_USERS[2].fullName,
    consentGiven: true,
  },
  {
    id: "33333333-3333-3333-3333-333333333304",
    customerName: "Priya Singh",
    mobileNumber: "9123456780",
    source: "REFERRAL",
    status: "BOOKING_CONFIRMED",
    heat: "HOT",
    destination: "Kedarkantha",
    tripId: DEMO_TRIPS[1].id,
    travelDate: "2026-12-01",
    budget: 22000,
    numPersons: 2,
    ownerId: DEMO_USERS[3].id,
    ownerName: DEMO_USERS[3].fullName,
    consentGiven: true,
  },
  {
    id: "33333333-3333-3333-3333-333333333305",
    customerName: "Vikas Patel",
    mobileNumber: "9090909090",
    source: "GOOGLE_ADS",
    status: "LOST",
    heat: "COLD",
    destination: "Kedarnath",
    tripId: DEMO_TRIPS[0].id,
    lostReason: "BUDGET",
    ownerId: DEMO_USERS[2].id,
    ownerName: DEMO_USERS[2].fullName,
    consentGiven: true,
  },
  {
    id: "33333333-3333-3333-3333-333333333306",
    customerName: "Sana Khan",
    mobileNumber: "9811122233",
    source: "FACEBOOK_ADS",
    status: "NEW",
    heat: "WARM",
    destination: "Char Dham",
    travelDate: "2026-10-28",
    budget: 80000,
    numPersons: 4,
    ownerId: DEMO_USERS[3].id,
    ownerName: DEMO_USERS[3].fullName,
    consentGiven: true,
  },
];
