import bcrypt from 'bcryptjs';
import { fileURLToPath } from 'node:url';
import { oneOrNull, q, pool } from '../src/db.js';
import { firstOfMonth } from '../src/services/roundRobin.js';
import {
  onLeadCreated,
  onLeadInterested,
  onLeadQuotationSent,
  onLeadBookingConfirmed,
} from '../src/services/automation.js';

const hash = (p) => bcrypt.hashSync(p, 10);

export async function seed() {
  console.log('[seed] checking if already seeded...');
  const existing = await oneOrNull(`SELECT count(*)::int AS n FROM users`);
  if ((existing?.n || 0) > 0) {
    console.log('[seed] users present — skipping. Use npm run setup for a clean re-seed.');
    return;
  }

  // ----- users -----
  const users = await q(
    `INSERT INTO users (email, password_hash, full_name, role, phone) VALUES
      ('admin@securetravels.in', '${hash('admin123')}', 'Rahul Verma (Admin)', 'admin', '9999900001'),
      ('manager@securetravels.in', '${hash('manager123')}', 'Suman Kaul (Manager)', 'manager', '9999900002'),
      ('sales.ravi@securetravels.in', '${hash('sales123')}', 'Ravi Singh', 'sales', '9999900003'),
      ('sales.meera@securetravels.in', '${hash('sales123')}', 'Meera Joshi', 'sales', '9999900004'),
      ('ops.suresh@securetravels.in', '${hash('ops123')}', 'Suresh Rawat', 'ops', '9999900005')
    RETURNING id, email, role, full_name`
  );
  const byRole = (role) => users.rows.filter((u) => u.role === role);
  const byEmail = (email) => users.rows.find((u) => u.email === email);
  console.log('[seed] users created');

  // ----- drivers -----
  await q(
    `INSERT INTO drivers (full_name, phone, vehicle_type, vehicle_number, license_number) VALUES
      ('Dinesh Kumar', '9811000111', 'Tempo Traveller', 'UK07 1234', 'DL-2021-4455'),
      ('Mohan Bisht', '9811000222', 'SUV (Bolero)', 'UK07 5678', 'DL-2022-8899')`
  );
  console.log('[seed] drivers created');

  // ----- packages -----
  const packages = await q(
    `INSERT INTO packages (name, slug, cost, duration_days, itinerary, inclusions, exclusions, departure_date) VALUES
      ('Kedarnath Yatra', 'kedarnath', 15999, 5,
       E'<p><b>Day 1:</b> Dehradun → Sonprayag drive. <b>Day 2:</b> Gaurikund → Kedarnath walk. <b>Day 3:</b> Kedarnath darshan. <b>Day 4:</b> Return. <b>Day 5:</b> Dehradun drop.</p>',
       E'<ul><li>Stay at Gaurikund/Kedarnath</li><li>Breakfast + dinner</li><li>Transport by Tempo Traveller</li></ul>',
       E'<ul><li>Pony/palki charges</li><li>Lunch en route</li></ul>',
       '2026-05-05'),
      ('Char Dham Yatra', 'char-dham', 55999, 11,
       E'<p>Yamunotri → Gangotri → Kedarnath → Badrinath sequence with all transfers.</p>',
       E'<ul><li>All darshan logistics</li><li>Full board meals</li><li>Volvo + Tempo Traveller</li></ul>',
       E'<ul><li>Pony/chopper for Kedarnath</li><li>Personal expenses</li></ul>',
       '2026-06-15'),
      ('Kashmir Delight', 'kashmir', 28500, 7,
       E'<p>Srinagar → Gulmarg → Pahalgam → Sonamarg with houseboat stay on Dal Lake.</p>',
       E'<ul><li>Houseboat + hotel stay</li><li>Daily breakfast</li><li>All transfers</li></ul>',
       E'<ul><li>Shikara/pony rides</li><li>Lunch</li></ul>',
       '2026-05-20')
    RETURNING id, name`
  );
  const pkgByName = (name) => packages.rows.find((p) => p.name === name);
  console.log('[seed] packages created');

  // ----- leads -----
  const ravi = byEmail('sales.ravi@securetravels.in');
  const meera = byEmail('sales.meera@securetravels.in');

  const leads = await q(
    `INSERT INTO leads
      (customer_name, mobile_number, whatsapp_number, email, source, destination, package_id, travel_date, num_persons, budget, lead_owner_id, status, follow_up_date, remarks)
     VALUES
      ('Ankit Sharma', '9876510001', '9876510001', 'ankit@example.com', 'website', 'Kedarnath', $1, '2026-06-10', 2, 40000, $2, 'new', NULL, 'Asked about Kedarnath in June'),
      ('Priya Patel', '9876510002', '9876510002', 'priya@example.com', 'google_ads', 'Kashmir', $3, '2026-06-20', 4, 120000, $4, 'interested', NULL, 'Family of 4, wants houseboat'),
      ('Vikram Singh', '9876510003', NULL, NULL, 'whatsapp', 'Char Dham', $5, '2026-07-01', 6, 350000, $2, 'quotation_sent', NULL, 'Corporate group — ask for group rate'),
      ('Neha Gupta', '9876510004', '9876510004', 'neha@example.com', 'instagram', 'Kedarnath', $1, '2026-06-05', 3, 58000, $4, 'booking_confirmed', NULL, 'Advance paid, balance due'),
      ('Rohit Kumar', '9876510005', NULL, 'rohit@example.com', 'referral', 'Ladakh', NULL, NULL, 2, 90000, $2, 'lost', NULL, 'Budget not matching — follow up next season')
     RETURNING id, customer_name, source, status, lead_owner_id`,
    [pkgByName('Kedarnath Yatra').id, ravi.id, pkgByName('Kashmir Delight').id, meera.id, pkgByName('Char Dham Yatra').id]
  );
  const leadByName = (n) => leads.rows.find((l) => l.customer_name === n);
  console.log('[seed] leads created');

  // ----- run automation to demo the chains -----
  await onLeadCreated(leadByName('Ankit Sharma'));
  await onLeadInterested(leadByName('Priya Patel').id, meera.id);
  await onLeadQuotationSent(leadByName('Vikram Singh').id, ravi.id);
  await onLeadBookingConfirmed(leadByName('Neha Gupta').id);
  console.log('[seed] automation chains ran (5-min task, +1/2/5/7d follow-ups, quotation task, ops record)');

  // ----- payment for confirmed lead -----
  await q(
    `INSERT INTO payments (lead_id, booking_amount, advance_amount, advance_status, due_date, payment_status, notes, created_by)
     SELECT l.id, 58000, 20000, 'received', CURRENT_DATE + 3, 'partial', 'Advance via bank transfer', m.id
     FROM leads l JOIN users m ON m.role='manager'
     WHERE l.customer_name='Neha Gupta'
     RETURNING id`
  );
  console.log('[seed] payment created (balance due in 3 days — reminder will fire)');

  // ----- customer database with trip history -----
  const cust = await oneOrNull(
    `INSERT INTO customers (full_name, mobile_number, whatsapp_number, email, suggest_offer, offer_tags, notes)
     VALUES ('Sunita Rana', '9865012001', '9865012001', 'sunita@example.com', true, '{char_dham, family_tour}', 'Completed Kedarnath in May — interested in family trips')
     RETURNING id`
  );
  await q(
    `INSERT INTO customer_trips (customer_id, package_name, travel_date, booking_amount, pax)
     VALUES ($1, 'Kedarnath Yatra', '2026-05-12', 31998, 2)`,
    [cust.id]
  );
  await q(
    `UPDATE customers SET total_trips = 1, last_trip_date = '2026-05-12', total_spent = 31998 WHERE id=$1`,
    [cust.id]
  );
  console.log('[seed] customer + trip history created');

  // ----- targets for current month -----
  const month = firstOfMonth();
  await q(
    `INSERT INTO sales_targets (user_id, month, target_bookings, target_revenue) VALUES
      (NULL, $1, 50, 2000000),
      ($2, $1, 25, 1000000),
      ($3, $1, 25, 1000000)`,
    [month, ravi.id, meera.id]
  );
  console.log('[seed] targets created');

  // ----- whatsapp templates -----
  await q(
    `INSERT INTO whatsapp_templates (template_key, name, message_template) VALUES
      ('package_details', 'Package Details',
       E'Namaste {{customer}} Ji!\n\nThanks for your interest in {{package}} with {{company}}.\n\nHere are the details:\n- Package: {{package}}\n- Duration: see itinerary\n- Cost: {{budget}} per person (approx)\n\nShall I share the full itinerary?'),
      ('itinerary', 'Itinerary',
       E'Namaste {{customer}} Ji,\n\nHere is the itinerary for {{package}}:\n\nWe will share the day-wise plan on WhatsApp/call shortly. Day 1 starts from Dehradun. Please share your travel dates ({{travel_date}}) and group size ({{pax}} pax) to firm it up.\n\n{{company}} Team'),
      ('price', 'Price',
       E'Namaste {{customer}} Ji,\n\nBest price for {{package}} for {{pax}} pax:\n- Total: {{budget}}\n\nThis includes stay, meals and transport as per plan. Shall we proceed with booking?'),
      ('payment_details', 'Payment Details',
       E'Namaste {{customer}} Ji,\n\nBooking summary:\n- Total: {{booking_amount}}\n- Paid (advance): {{advance}}\n- Balance: {{balance}}\n- Due by: {{due_date}}\n\nPlease share your balance at the earliest to confirm your seat.\n\n{{company}} Team'),
      ('hotel_details', 'Hotel Details',
       E'Namaste {{customer}} Ji,\n\nYour stay for {{package}} has been confirmed. We will share the hotel names and check-in details once finalised. Stay tuned!\n\n{{company}} Team'),
      ('driver_details', 'Driver Details',
       E'Namaste {{customer}} Ji,\n\nYour driver for the {{package}} trip is confirmed. We will share the driver name and vehicle number 1 day before departure.\n\n{{company}} Team'),
      ('pickup_details', 'Pickup Details',
       E'Namaste {{customer}} Ji,\n\nPickup for your {{package}} trip:\n- Date: {{travel_date}}\n- Time: 6:00 AM\n- From: Dehradun Railway Station / your address\n\nPlease confirm your pickup point.\n\n{{company}} Team'),
      ('booking_confirmation', 'Booking Confirmation',
       E'Congratulations {{customer}} Ji! 🎉\n\nYour booking for {{package}} is CONFIRMED.\n- Travel date: {{travel_date}}\n- Pax: {{pax}}\n\nWe will share all trip details soon.\n\nThank you for choosing {{company}}!'),
      ('reminder', 'Payment Reminder',
       E'Namaste {{customer}} Ji,\n\nGentle reminder — your balance payment of {{balance}} for {{package}} is due on {{due_date}}.\n\nKindly complete it to keep your booking confirmed.\n\n{{company}} Team')`
  );
  console.log('[seed] whatsapp templates created');

  console.log('\n==== SEED COMPLETE ====');
  console.log('Login: admin@securetravels.in / admin123 | manager@securetravels.in / manager123');
  console.log('Sales: sales.ravi@securetravels.in / sales123 | sales.meera@securetravels.in / sales123');
  console.log('Ops:   ops.suresh@securetravels.in / ops123');
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  await seed();
  await pool.end();
}