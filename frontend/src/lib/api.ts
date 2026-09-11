import type { components } from "@/openapi/generated";

export type Lead = components["schemas"]["LeadResponse"];
export type LeadCreatePayload = components["schemas"]["LeadCreateRequest"];
export type LeadUpdatePayload = components["schemas"]["LeadUpdateRequest"];
export type LeadStatusPayload = components["schemas"]["LeadStatusRequest"];
export type LeadActivity = components["schemas"]["LeadActivityResponse"];
export type Trip = components["schemas"]["TripResponse"];
export type TripDetail = components["schemas"]["TripDetailResponse"];
export type TripCreatePayload = components["schemas"]["TripCreateRequest"];
export type TripUpdatePayload = components["schemas"]["TripUpdateRequest"];
export type Batch = components["schemas"]["BatchResponse"];
export type BatchCreatePayload = components["schemas"]["BatchCreateRequest"];
export type BatchUpdatePayload = components["schemas"]["BatchUpdateRequest"];
export type Guide = components["schemas"]["GuideResponse"];
export type GuideCreatePayload = components["schemas"]["GuideCreateRequest"];
export type GuideUpdatePayload = components["schemas"]["GuideUpdateRequest"];
export type Booking = components["schemas"]["BookingResponse"];
export type BookingCreatePayload = components["schemas"]["BookingCreateRequest"];
export type BookingStatusPayload = components["schemas"]["BookingStatusRequest"];
export type Payment = components["schemas"]["PaymentResponse"];
export type PaymentCreatePayload = components["schemas"]["PaymentCreateRequest"];
export type PaymentStatusPayload = components["schemas"]["PaymentStatusRequest"];
export type PaymentSummary = components["schemas"]["PaymentSummaryResponse"];
export type OpsHandoff = components["schemas"]["OperationsHandoffResponse"];
export type OpsArrangementsPayload = components["schemas"]["OpsArrangementsRequest"];
export type OpsNotePayload = components["schemas"]["OpsNoteRequest"];
export type LoginResponse = components["schemas"]["AuthResponse"];
export type Task = components["schemas"]["TaskResponse"];
export type NotificationItem = components["schemas"]["NotificationResponse"];
export type CustomerListItem = components["schemas"]["CustomerListResponse"];
export type CustomerDetail = components["schemas"]["CustomerDetailResponse"];
export type CustomerTripRow = components["schemas"]["TripRow"];
export type CustomerUpdatePayload = components["schemas"]["CustomerUpdateRequest"];
export type DashboardSummary = components["schemas"]["DashboardSummaryResponse"];
export type DashboardPerformance = components["schemas"]["PerformanceResponse"];
export type TargetRow = components["schemas"]["TargetDto"];
export type TargetsList = components["schemas"]["TargetListResponse"];
export type TargetsProgress = components["schemas"]["TargetProgressResponse"];
export type TargetUpsertPayload = Omit<
  components["schemas"]["TargetUpsertRequest"],
  "userId"
> & { userId?: string | null };
export type SalesUser = { id: string; fullName: string; email: string };

export type NotificationFeed = {
  items: NotificationItem[];
  unread: number;
};

export type TaskListParams = {
  leadId?: string;
  status?: string;
};

export type LeadPage = {
  content: Lead[];
  totalElements: number;
  totalPages: number;
  number: number;
};

export type LeadListParams = {
  status?: string;
  source?: string;
  heat?: string;
  tripId?: string;
  from?: string;
  to?: string;
  travelFrom?: string;
  travelTo?: string;
  ownerId?: string;
  search?: string;
  page?: number;
  size?: number;
};

export type ApiErrorBody = {
  code?: string;
  message?: string;
  fieldErrors?: { field: string; message: string }[];
};

const TOKENS = {
  access: "st_access",
  refresh: "st_refresh",
};

export const tokenStore = {
  getAccess: () => (typeof window === "undefined" ? null : localStorage.getItem(TOKENS.access)),
  getRefresh: () => (typeof window === "undefined" ? null : localStorage.getItem(TOKENS.refresh)),
  set: (access: string, refresh: string) => {
    localStorage.setItem(TOKENS.access, access);
    localStorage.setItem(TOKENS.refresh, refresh);
  },
  clear: () => {
    localStorage.removeItem(TOKENS.access);
    localStorage.removeItem(TOKENS.refresh);
  },
};

export class ApiClientError extends Error {
  status: number;
  body: ApiErrorBody;
  constructor(status: number, body: ApiErrorBody) {
    super(body.message || `Request failed (${status})`);
    this.name = "ApiClientError";
    this.status = status;
    this.body = body;
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const access = tokenStore.getAccess();
  const headers = new Headers(init?.headers);
  headers.set("Content-Type", "application/json");
  if (access) headers.set("Authorization", `Bearer ${access}`);
  const res = await fetch(path, { ...init, headers });

  if (!res.ok) {
    if (res.status === 401) {
      tokenStore.clear();
      if (typeof window !== "undefined") window.location.href = "/login";
    }
    let body: ApiErrorBody = {};
    try {
      body = await res.json();
    } catch {
      /* ignore */
    }
    throw new ApiClientError(res.status, body);
  }
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

function qs(params: Record<string, string | number | boolean | undefined | null>): string {
  const parts = Object.entries(params)
    .filter(([, v]) => v !== undefined && v !== null && v !== "")
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`);
  return parts.length ? `?${parts.join("&")}` : "";
}

export const api = {
  login: (email: string, password: string) =>
    request<LoginResponse>("/api/auth/login", {
      method: "POST",
      body: JSON.stringify({ email, password }),
    }),

  me: () => request<components["schemas"]["UserInfo"]>("/api/auth/me"),

  getLeads: (params: LeadListParams = {}) =>
    request<LeadPage>(`/api/leads${qs({ ...params, size: params.size ?? 20 })}`),

  getLead: (id: string) => request<Lead>(`/api/leads/${id}`),

  createLead: (payload: LeadCreatePayload) =>
    request<Lead>("/api/leads", { method: "POST", body: JSON.stringify(payload) }),

  updateLead: (id: string, payload: LeadUpdatePayload) =>
    request<Lead>(`/api/leads/${id}`, { method: "PATCH", body: JSON.stringify(payload) }),

  updateLeadStatus: (id: string, payload: LeadStatusPayload) =>
    request<Lead>(`/api/leads/${id}/status`, { method: "PATCH", body: JSON.stringify(payload) }),

  getLeadActivity: (id: string) => request<LeadActivity[]>(`/api/leads/${id}/activity`),

  getTrips: (active = true) => request<Trip[]>(`/api/trips${qs({ active })}`),

  getTrip: (id: string) => request<TripDetail>(`/api/trips/${id}`),

  createTrip: (payload: TripCreatePayload) =>
    request<TripDetail>("/api/trips", { method: "POST", body: JSON.stringify(payload) }),

  updateTrip: (id: string, payload: TripUpdatePayload) =>
    request<TripDetail>(`/api/trips/${id}`, { method: "PATCH", body: JSON.stringify(payload) }),

  createBatch: (tripId: string, payload: BatchCreatePayload) =>
    request<Batch>(`/api/trips/${tripId}/batches`, { method: "POST", body: JSON.stringify(payload) }),

  updateBatch: (id: string, payload: BatchUpdatePayload) =>
    request<Batch>(`/api/batches/${id}`, { method: "PATCH", body: JSON.stringify(payload) }),

  getGuides: (active = true) => request<Guide[]>(`/api/guides${qs({ active })}`),

  createGuide: (payload: GuideCreatePayload) =>
    request<Guide>("/api/guides", { method: "POST", body: JSON.stringify(payload) }),

  updateGuide: (id: string, payload: GuideUpdatePayload) =>
    request<Guide>(`/api/guides/${id}`, { method: "PATCH", body: JSON.stringify(payload) }),

  getBookings: (params: { status?: string; tripId?: string; batchId?: string; customerId?: string } = {}) =>
    request<Booking[]>(`/api/bookings${qs({ ...params })}`),

  getBooking: (id: string) => request<Booking>(`/api/bookings/${id}`),

  createBooking: (payload: BookingCreatePayload) =>
    request<Booking>("/api/bookings", { method: "POST", body: JSON.stringify(payload) }),

  updateBookingStatus: (id: string, payload: BookingStatusPayload) =>
    request<Booking>(`/api/bookings/${id}/status`, { method: "PATCH", body: JSON.stringify(payload) }),

  getPayments: (params: { bookingId?: string; status?: string; amountType?: string } = {}) =>
    request<Payment[]>(`/api/payments${qs({ ...params })}`),

  getPaymentSummary: (bookingId: string) =>
    request<PaymentSummary>(`/api/payments/booking/${bookingId}/summary`),

  createPayment: (payload: PaymentCreatePayload) =>
    request<Payment>("/api/payments", { method: "POST", body: JSON.stringify(payload) }),

  updatePaymentStatus: (id: string, payload: PaymentStatusPayload) =>
    request<Payment>(`/api/payments/${id}/status`, { method: "PATCH", body: JSON.stringify(payload) }),

  getTasks: (params: TaskListParams = {}) =>
    request<Task[]>(`/api/tasks${qs({ ...params })}`),

  completeTask: (id: string) =>
    request<Task>(`/api/tasks/${id}/complete`, { method: "PATCH" }),

  getOperations: (params: {
    travelDateFrom?: string;
    travelDateTo?: string;
    hotelStatus?: string;
    transportStatus?: string;
    paymentStatus?: string;
  } = {}) => request<OpsHandoff[]>(`/api/operations${qs({ ...params })}`),

  getOperation: (id: string) => request<OpsHandoff>(`/api/operations/${id}`),

  updateOperationArrangements: (id: string, payload: OpsArrangementsPayload) =>
    request<OpsHandoff>(`/api/operations/${id}/arrangements`, {
      method: "PATCH",
      body: JSON.stringify(payload),
    }),

  addOperationNote: (id: string, payload: OpsNotePayload) =>
    request<OpsHandoff>(`/api/operations/${id}/notes`, {
      method: "POST",
      body: JSON.stringify(payload),
    }),

  generateTripSheet: (id: string) =>
    request<OpsHandoff>(`/api/operations/${id}/trip-sheet`, { method: "POST" }),

  getNotifications: (unreadOnly = false) =>
    request<NotificationFeed>(`/api/notifications${qs({ unreadOnly })}`),

  markNotificationRead: (id: string) =>
    request<NotificationItem>(`/api/notifications/${id}/read`, { method: "PATCH" }),

  markAllNotificationsRead: () =>
    request<{ updated: number }>("/api/notifications/read-all", { method: "POST" }),

  getCustomers: (search?: string) =>
    request<CustomerListItem[]>(`/api/customers${qs({ search })}`),

  getCustomer: (id: string) => request<CustomerDetail>(`/api/customers/${id}`),

  updateCustomer: (id: string, payload: CustomerUpdatePayload) =>
    request<CustomerDetail>(`/api/customers/${id}`, { method: "PATCH", body: JSON.stringify(payload) }),

  getDashboardSummary: (period?: "today" | "month") =>
    request<DashboardSummary>(`/api/dashboard/summary${qs({ period })}`),

  getDashboardPerformance: (month?: string) =>
    request<DashboardPerformance>(`/api/dashboard/performance${qs({ month })}`),

  getTargets: (month?: string) => request<TargetsList>(`/api/targets${qs({ month })}`),

  getTargetProgress: (month?: string) =>
    request<TargetsProgress>(`/api/targets/progress${qs({ month })}`),

  upsertTarget: (payload: TargetUpsertPayload) =>
    request<TargetRow>("/api/targets", { method: "PUT", body: JSON.stringify(payload) }),

  deleteTarget: (id: string) => request<void>(`/api/targets/${id}`, { method: "DELETE" }),

  getSalesUsers: () => request<SalesUser[]>("/api/users/sales"),
};