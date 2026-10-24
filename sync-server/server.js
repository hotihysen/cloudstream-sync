#!/usr/bin/env node
/*
 * CloudStream Sync Server
 * Speichert Wiedergabepositionen und «Weiterschauen»-Einträge und verteilt sie an alle
 * Geräte mit demselben Token. Keine Abhängigkeiten, nur Node.js (ab Version 18).
 *
 * Umgebungsvariablen
 *   SYNC_TOKENS   Pflicht. Kommagetrennt «name:token», z.B. "hysen:AbC123xyz,familie:Q9w8e7"
 *                 Jeder Name hat seinen eigenen, getrennten Datenbestand.
 *   PORT          Standard 8080
 *   DATA_DIR      Standard ./data
 *
 * API (alle Aufrufe mit Header «Authorization: Bearer <token>»)
 *   GET  /v1/ping                 → { ok, user }
 *   POST /v1/push  { device, records:[{k,v,t,d}] }  → { ok, accepted, seq }
 *   GET  /v1/pull?since=<seq>     → { seq, records:[{k,v,t,d}] }
 */
'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

if (process.argv.includes('--gen-token')) {
  // Gut auf der TV-Fernbedienung tippbar: nur Kleinbuchstaben und Ziffern, ohne 0/o/1/l
  const alphabet = 'abcdefghijkmnpqrstuvwxyz23456789';
  const bytes = crypto.randomBytes(20);
  let t = '';
  for (const b of bytes) t += alphabet[b % alphabet.length];
  console.log(t);
  process.exit(0);
}

const PORT = parseInt(process.env.PORT || '8080', 10);
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, 'data');
const DB_FILE = path.join(DATA_DIR, 'db.json');
const MAX_BODY = 5 * 1024 * 1024;
const MAX_PULL = 5000;
const MAX_VALUE = 64 * 1024;
const KEY_RE = /^(pos|resume|header|ws)\/-?\d{1,11}$/;

const tokens = new Map(); // token -> user
for (const part of (process.env.SYNC_TOKENS || '').split(',')) {
  const i = part.indexOf(':');
  if (i <= 0) continue;
  const user = part.slice(0, i).trim();
  const token = part.slice(i + 1).trim();
  if (user && token.length >= 12) tokens.set(token, user);
}
if (tokens.size === 0) {
  console.error('SYNC_TOKENS fehlt oder Token zu kurz (mind. 12 Zeichen). Beispiel: SYNC_TOKENS="hysen:$(node server.js --gen-token)"');
  process.exit(1);
}

// ------------------------------------------------------------------ Speicher
fs.mkdirSync(DATA_DIR, { recursive: true });
let db = { users: {} };
try {
  db = JSON.parse(fs.readFileSync(DB_FILE, 'utf8'));
  if (!db.users) db.users = {};
} catch (e) {
  if (e.code !== 'ENOENT') {
    console.error('db.json unlesbar, lege Sicherung an und starte leer:', e.message);
    try { fs.copyFileSync(DB_FILE, DB_FILE + '.broken-' + Date.now()); } catch (_) {}
  }
}

let saveTimer = null;
function scheduleSave() {
  if (saveTimer) return;
  saveTimer = setTimeout(saveNow, 1000);
}
function saveNow() {
  saveTimer = null;
  const tmp = DB_FILE + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(db));
  fs.renameSync(tmp, DB_FILE); // atomar: nie eine halb geschriebene Datei
}
function shutdown() {
  if (saveTimer) { clearTimeout(saveTimer); saveNow(); }
  process.exit(0);
}
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);

function userData(user) {
  if (!db.users[user]) db.users[user] = { seq: 0, records: {} };
  return db.users[user];
}

// ------------------------------------------------------------------ HTTP
function send(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'Content-Length': Buffer.byteLength(body),
  });
  res.end(body);
}

function auth(req) {
  const h = req.headers['authorization'] || '';
  const m = /^Bearer\s+(.+)$/i.exec(h);
  if (!m) return null;
  const given = Buffer.from(m[1].trim());
  for (const [token, user] of tokens) {
    const t = Buffer.from(token);
    if (t.length === given.length && crypto.timingSafeEqual(t, given)) return user;
  }
  return null;
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY) { reject(Object.assign(new Error('zu gross'), { status: 413 })); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

function handlePush(user, body) {
  const data = userData(user);
  const device = typeof body.device === 'string' ? body.device.slice(0, 64) : '';
  const records = Array.isArray(body.records) ? body.records : [];
  let accepted = 0;
  for (const r of records) {
    if (!r || typeof r.k !== 'string' || !KEY_RE.test(r.k)) continue;
    const t = Number(r.t);
    if (!Number.isFinite(t) || t <= 0) continue;
    const deleted = r.d === true || r.v === null || r.v === undefined;
    const v = deleted ? null : String(r.v);
    if (v !== null && v.length > MAX_VALUE) continue;
    // Uhren der Geräte laufen nie ganz gleich: Zeiten aus der Zukunft kappen
    const tt = Math.min(t, Date.now() + 60_000);
    const cur = data.records[r.k];
    if (cur && cur.t >= tt) continue; // vorhandener Stand ist neuer oder gleich alt
    data.seq += 1;
    data.records[r.k] = { v, t: tt, d: deleted, s: data.seq, dev: device };
    accepted++;
  }
  if (accepted) scheduleSave();
  return { ok: true, accepted, seq: data.seq };
}

function handlePull(user, since) {
  const data = userData(user);
  const out = [];
  for (const [k, r] of Object.entries(data.records)) {
    if (r.s > since) out.push([k, r]);
  }
  out.sort((a, b) => a[1].s - b[1].s);
  const page = out.slice(0, MAX_PULL);
  const seq = page.length < out.length ? page[page.length - 1][1].s : data.seq;
  return {
    seq,
    records: page.map(([k, r]) => ({ k, v: r.v, t: r.t, d: r.d })),
  };
}

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://localhost');
    if (req.method === 'GET' && url.pathname === '/health') return send(res, 200, { ok: true });

    const user = auth(req);
    if (!user) return send(res, 401, { error: 'unauthorized' });

    if (req.method === 'GET' && url.pathname === '/v1/ping') {
      return send(res, 200, { ok: true, user, seq: userData(user).seq });
    }
    if (req.method === 'POST' && url.pathname === '/v1/push') {
      let body;
      try { body = JSON.parse(await readBody(req)); } catch (e) {
        return send(res, e.status || 400, { error: e.status ? e.message : 'invalid json' });
      }
      return send(res, 200, handlePush(user, body || {}));
    }
    if (req.method === 'GET' && url.pathname === '/v1/pull') {
      const since = Math.max(0, parseInt(url.searchParams.get('since') || '0', 10) || 0);
      return send(res, 200, handlePull(user, since));
    }
    return send(res, 404, { error: 'not found' });
  } catch (e) {
    console.error(e);
    send(res, 500, { error: 'server error' });
  }
});

server.listen(PORT, () => {
  console.log(`CloudStream Sync Server läuft auf Port ${PORT}, Benutzer: ${[...new Set(tokens.values())].join(', ')}`);
});
