// The check of third-party resources (point 6): node tests/assets-check.test.mjs
// Cartridge headers are recognized by their checksums: they are built here from scratch (a made-up title, zeros
// elsewhere), never copied from a game. Then the check is run on the repository with a disguised ROM header and an
// unreviewed picture added for a moment: both must make it fail.
import { execFileSync } from 'node:child_process';
import { rmSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { cartridge } from './assets-check.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
let failed = 0;
const check = (ok, what) => { if (!ok) { failed++; console.log(`  ${what}`); } };
const ascii = (b, at, text) => { for (let i = 0; i < text.length; i++) b[at + i] = text.charCodeAt(i); };

function gameBoy() {
  const b = Buffer.alloc(0x8000);
  b[0x100] = 0x00; b[0x101] = 0xC3;
  ascii(b, 0x134, 'TESTGAME');
  let x = 0;
  for (let i = 0x134; i <= 0x14C; i++) x = (x - b[i] - 1) & 0xFF;
  b[0x14D] = x;
  return b;
}
function advance() {
  const b = Buffer.alloc(0x200);
  b[3] = 0xEA;
  ascii(b, 0xA0, 'TEST GAME');
  ascii(b, 0xAC, 'ZZZZ');
  b[0xB2] = 0x96;
  let c = 0;
  for (let i = 0xA0; i <= 0xBC; i++) c = (c - b[i]) & 0xFF;
  b[0xBD] = (c - 0x19) & 0xFF;
  return b;
}
function ds() {
  const b = Buffer.alloc(0x200);
  ascii(b, 0, 'TESTGAME');
  ascii(b, 12, 'ZZZZ');
  let crc = 0xFFFF;
  for (let i = 0; i < 0x15E; i++) {
    crc ^= b[i];
    for (let k = 0; k < 8; k++) crc = crc & 1 ? (crc >>> 1) ^ 0xA001 : crc >>> 1;
  }
  b[0x15E] = crc & 0xFF; b[0x15F] = crc >>> 8;
  return b;
}

check(cartridge(gameBoy(), 0x8000) === 'Game Boy', 'en-tête Game Boy reconnu');
check(cartridge(gameBoy(), 0x9000) === null, 'Game Boy : une taille qui n’est pas une puissance de deux n’est pas une ROM');
check(cartridge(advance(), 0x400000) === 'Game Boy Advance', 'en-tête Game Boy Advance reconnu');
check(cartridge(ds(), 0x1000000) === 'Nintendo DS', 'en-tête Nintendo DS reconnu');
const broken = ds(); broken[0x15E] ^= 1;
check(cartridge(broken, 0x1000000) === null, 'DS : une somme fausse n’est pas une ROM');
// ordinary files are not cartridges (text, zeros, pseudo-random bytes)
check(cartridge(Buffer.from('<!doctype html><html lang="fr">'.repeat(20)), 620) === null, 'un fichier texte n’est pas une ROM');
check(cartridge(Buffer.alloc(0x200), 0x8000) === null, 'des zéros ne sont pas une ROM');
let seed = 12345;
let hits = 0;
for (let n = 0; n < 20000; n++) {
  const b = Buffer.alloc(0x200);
  for (let i = 0; i < b.length; i++) { seed = (seed * 1103515245 + 12345) & 0x7FFFFFFF; b[i] = seed >>> 16 & 0xFF; }
  if (cartridge(b, 0x8000 << (n % 4))) hits++;
}
check(hits === 0, `${hits} faux positif(s) sur 20 000 fichiers aléatoires`);

// on the repository: a disguised ROM header and an unreviewed picture make the check fail
const run = () => { try { execFileSync('node', [path.join(ROOT, 'tests', 'assets-check.mjs')], { stdio: 'pipe' }); return 0; } catch (e) { return e.status; } };
check(run() === 0, 'le dépôt passe tel quel');
const disguised = path.join(ROOT, 'tests', 'zz-test-entete.txt');
const picture = path.join(ROOT, 'tests', 'zz-test-image.png');
try {
  writeFileSync(disguised, gameBoy());
  check(run() === 1, 'un en-tête de cartouche sous un autre nom est refusé');
  rmSync(disguised);
  writeFileSync(picture, Buffer.from('89504e470d0a1a0a', 'hex'));
  check(run() === 1, 'une image non vérifiée est refusée');
} finally {
  rmSync(disguised, { force: true });
  rmSync(picture, { force: true });
}

console.log(failed === 0 ? 'ok     contrôle des ressources tierces' : `ÉCHEC  contrôle des ressources tierces : ${failed} cas`);
process.exit(failed === 0 ? 0 : 1);
