#!/usr/bin/env node
// Matches a recall's flagged batches against every store's stock.
//
//   node tools/match.mjs data/alerts/R-20261104-1.json
//
// Reads inventory/stores.json and inventory/stores/<store>.csv, writes data/matches/<recall>.json
// for sentinel, and prints a Markdown impact table for the room. The matcher agent runs this and
// then uses judgement on top: product names, OCR-like near misses, what to tell the pharmacist.
// Batch numbers are compared as normalised keys (letters and digits, upper case); a "probable"
// match folds look-alike characters (O/0, I/1, S/5, B/8), which catches typos in stock entry.

import { readFileSync, writeFileSync, mkdirSync, readdirSync } from 'node:fs';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const alertsPath = process.argv[2];
if (!alertsPath) {
  console.error('usage: node tools/match.mjs data/alerts/<recall>.json');
  process.exit(2);
}

const strict = (b) => String(b ?? '').toUpperCase().replace(/[^A-Z0-9]/g, '');
const LOOK_ALIKE = { O: '0', Q: '0', D: '0', I: '1', L: '1', Z: '2', S: '5', B: '8', G: '6' };
const loose = (b) => [...strict(b)].map((c) => LOOK_ALIKE[c] ?? c).join('');

function readCsv(path) {
  const [header, ...rows] = readFileSync(path, 'utf8').trim().split(/\r?\n/);
  const cols = header.split(',');
  return rows.filter(Boolean).map((line) => {
    const cells = line.split(',');
    return Object.fromEntries(cols.map((c, i) => [c, cells[i]?.trim() ?? '']));
  });
}

const alert = JSON.parse(readFileSync(resolve(root, alertsPath), 'utf8'));
const byStrict = new Map();
const byLoose = new Map();
for (const f of alert.batches) {
  if (!strict(f.batch)) continue;
  byStrict.set(strict(f.batch), f);
  byLoose.set(loose(f.batch), f);
}

const storesMeta = JSON.parse(readFileSync(join(root, 'inventory', 'stores.json'), 'utf8'));
const stores = [];
const sameProductOtherBatch = [];
for (const file of readdirSync(join(root, 'inventory', 'stores')).filter((f) => f.endsWith('.csv')).sort()) {
  const id = basename(file, '.csv');
  const meta = storesMeta[id] ?? { name: id, room: id };
  const lines = [];
  for (const item of readCsv(join(root, 'inventory', 'stores', file))) {
    const exact = byStrict.get(strict(item.batch));
    const probable = exact ? null : byLoose.get(loose(item.batch));
    const hit = exact ?? probable;
    if (hit) {
      lines.push({
        product: item.product,
        batch: item.batch,
        qty: Number(item.qty) || 0,
        unit: item.unit,
        shelf: item.shelf,
        match: exact ? 'exact' : `probable (stock says ${item.batch}, regulator says ${hit.batch})`,
        flagSource: hit.source,
        reason: String(hit.reason ?? '').slice(0, 140),
      });
    } else {
      const word = item.product.split(/\s+/)[0].toLowerCase();
      const generic = ['oral', 'tablets', 'tablet', 'capsules', 'syrup', 'injection'].includes(word) || word.length < 5;
      if (!generic && alert.batches.some((f) => String(f.product).toLowerCase().startsWith(word))) {
        sameProductOtherBatch.push(`${meta.name}: ${item.product} batch ${item.batch}`);
      }
    }
  }
  stores.push({ store: id, name: meta.name, room: meta.room, lines });
}

const out = { recall: alert.recall, flaggedTotal: alert.count, generatedAt: new Date().toISOString(), stores };
const outPath = join(root, 'data', 'matches', `${alert.recall}.json`);
mkdirSync(dirname(outPath), { recursive: true });
writeFileSync(outPath, JSON.stringify(out, null, 2));

const affected = stores.filter((s) => s.lines.length);
const units = affected.reduce((n, s) => n + s.lines.reduce((m, l) => m + l.qty, 0), 0);
console.log(`**${alert.recall}: ${alert.count} flagged batches checked against ${stores.length} stores.** ` +
  (affected.length ? `${affected.length} stores hold ${units} affected units.` : 'No store holds an affected batch.'));
if (affected.length) {
  console.log('\n| Store | Product | Batch | Qty | Shelf | Match | Why it was flagged |\n|---|---|---|---|---|---|---|');
  for (const s of affected) {
    for (const l of s.lines) {
      console.log(`| ${s.name} | ${l.product} | ${l.batch} | ${l.qty} ${l.unit} | ${l.shelf} | ${l.match} | ${l.reason} |`);
    }
  }
}
if (sameProductOtherBatch.length) {
  console.log(`\nSame product, different batch, so **not** affected: ${sameProductOtherBatch.join('; ')}.`);
}
console.log(`\nWritten to data/matches/${alert.recall}.json`);
