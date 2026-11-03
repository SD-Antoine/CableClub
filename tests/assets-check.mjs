// Keeps third-party resources out of Cable Club (point 6, legal guardrail 1): node tests/assets-check.mjs
//   (no argument)        the files of the repository (tracked by git, and new ones not ignored)
//   --package <folder>   a package built for a release (dist\CableClub): no game file, nothing that looks like a ROM
//   --list               prints the allowlist lines of the media and archives not in it yet (to review, then paste)
// Refused: ROMs, saves, BIOS and firmwares, ROM patches, emulator states; archives, pictures, sounds, videos and fonts
// not in tests/assets-allowlist.txt (path + SHA-256: a changed picture must be reviewed again); and any file whose
// header is the one of a Game Boy, Game Boy Advance or Nintendo DS cartridge, whatever its name. The headers are
// recognized by their checksums only: no byte of a game is written here.
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { closeSync, openSync, readFileSync, readSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/** Game files, saves, firmwares, ROM patches and emulator states: never in the repository nor in a release. */
export const GAME_FILES = new Set(['.gb', '.gbc', '.gba', '.agb', '.nds', '.dsi', '.srl', '.3ds', '.cia', '.cgb', '.sgb',
  '.sav', '.dsv', '.sgm', '.srm', '.duc', '.ips', '.ups', '.bps', '.xdelta', '.ppf', '.aps', '.state', '.mst', '.ml0']);
const GAME_NAME = /^(bios|firmware|gba_bios|gb_bios|gbc_bios|sgb_bios|dsi_|nds_)[^/]*\.bin$|\.ss\d$|\.st\d$|\.sa\d$|\.dst$/i;
/** Pictures, sounds, videos and fonts: only those reviewed (allowlist). */
export const MEDIA = new Set(['.png', '.jpg', '.jpeg', '.gif', '.webp', '.svg', '.ico', '.bmp', '.avif', '.tif', '.tiff',
  '.wav', '.mp3', '.ogg', '.oga', '.flac', '.m4a', '.aac', '.opus', '.mid', '.midi', '.mp4', '.webm', '.mov', '.avi',
  '.ttf', '.otf', '.woff', '.woff2']);
/** Archives and binary blobs: only those reviewed (allowlist). */
const ARCHIVES = new Set(['.zip', '.7z', '.rar', '.tar', '.gz', '.tgz', '.xz', '.bz2', '.zst', '.jar', '.apk', '.aab',
  '.bin', '.dat', '.img', '.iso', '.exe', '.dll', '.so']);
/** A file this big that is not reviewed has no place in the repository. */
const MAX_UNLISTED = 2 * 1024 * 1024;

// a title of a cartridge header: printable ASCII, or zeros after it
const title = (b, from, to) => b.subarray(from, to).every((v, i, a) => (v >= 0x20 && v < 0x7F) || (v === 0 && a.subarray(i).every((z) => z === 0)))
  && b[from] >= 0x20 && b[from] < 0x7F;
const code = (b, from) => b.subarray(from, from + 4).every((v) => (v >= 0x30 && v <= 0x39) || (v >= 0x41 && v <= 0x5A));

/** The header of a cartridge, by its checksums and its title ({@code b}: the first 0x200 bytes), or null. */
export function cartridge(b, size) {
  // Game Boy / Color: header checksum at 0x14D over 0x134-0x14C, entry point « nop; jp » or « jr », ROM size a power
  // of two of at least 32 KB
  if (b.length >= 0x150 && size >= 0x8000 && (size & (size - 1)) === 0 && b[0x100] === 0x00 && (b[0x101] === 0xC3 || b[0x101] === 0x18)
    && title(b, 0x134, 0x13F)) {
    let x = 0;
    for (let i = 0x134; i <= 0x14C; i++) x = (x - b[i] - 1) & 0xFF;
    if (x === b[0x14D]) return 'Game Boy';
  }
  // Game Boy Advance: fixed value 0x96 at 0xB2, branch instruction at 0, complement check at 0xBD over 0xA0-0xBC
  if (b.length >= 0xC0 && b[0xB2] === 0x96 && b[3] === 0xEA && title(b, 0xA0, 0xAC) && code(b, 0xAC)) {
    let c = 0;
    for (let i = 0xA0; i <= 0xBC; i++) c = (c - b[i]) & 0xFF;
    if (((c - 0x19) & 0xFF) === b[0xBD]) return 'Game Boy Advance';
  }
  // Nintendo DS: CRC-16 of the header (0x000-0x15D) stored at 0x15E
  if (b.length >= 0x160 && title(b, 0, 12) && code(b, 12)) {
    let crc = 0xFFFF;
    for (let i = 0; i < 0x15E; i++) {
      crc ^= b[i];
      for (let k = 0; k < 8; k++) crc = crc & 1 ? (crc >>> 1) ^ 0xA001 : crc >>> 1;
    }
    if (crc === (b[0x15E] | (b[0x15F] << 8))) return 'Nintendo DS';
  }
  return null;
}

function head(file, n = 0x200) {
  const fd = openSync(file, 'r');
  try {
    const b = Buffer.alloc(n);
    const read = readSync(fd, b, 0, n, 0);
    return b.subarray(0, read);
  } finally { closeSync(fd); }
}

const sha256 = (file) => createHash('sha256').update(readFileSync(file)).digest('hex');

function allowlist() {
  const list = new Map();
  const text = readFileSync(path.join(ROOT, 'tests', 'assets-allowlist.txt'), 'utf8');
  for (const line of text.split(/\r?\n/)) {
    const m = line.replace(/#.*/, '').trim().match(/^([0-9a-f]{64})\s+(\S+)$/);
    if (m) list.set(m[2], m[1]);
  }
  return list;
}

function repoFiles() {
  const out = execFileSync('git', ['-C', ROOT, 'ls-files', '-z', '--cached', '--others', '--exclude-standard'], { encoding: 'utf8' });
  return [...new Set(out.split('\0').filter(Boolean))].filter((f) => { try { return statSync(path.join(ROOT, f)).isFile(); } catch { return false; } });
}

function walk(dir, base = dir, out = []) {
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, base, out); else if (e.isFile()) out.push(path.relative(base, p).split(path.sep).join('/'));
  }
  return out;
}

/** What is wrong with a file of the repository (null: nothing). */
function checkRepoFile(rel, list) {
  const file = path.join(ROOT, rel);
  const ext = path.extname(rel).toLowerCase();
  if (GAME_FILES.has(ext) || GAME_NAME.test(path.basename(rel))) return 'fichier de jeu, de sauvegarde, de firmware, patch de ROM ou état d’émulateur';
  const size = statSync(file).size;
  const card = cartridge(head(file), size);
  if (card) return `en-tête de cartouche ${card} (une ROM, quel que soit son nom)`;
  const reviewed = MEDIA.has(ext) || ARCHIVES.has(ext) || size > MAX_UNLISTED;
  if (!reviewed) return null;
  const known = list.get(rel);
  if (!known) return `${MEDIA.has(ext) ? 'image, son, vidéo ou police' : ARCHIVES.has(ext) ? 'archive ou fichier binaire' : 'gros fichier'} absent de tests/assets-allowlist.txt`;
  if (known !== sha256(file)) return 'contenu changé depuis sa vérification (empreinte différente de tests/assets-allowlist.txt)';
  return null;
}

/** What is wrong with a file of a release package (the JRE, the cores and the jars are expected there). */
function checkPackageFile(dir, rel) {
  const file = path.join(dir, rel);
  if (GAME_FILES.has(path.extname(rel).toLowerCase()) || GAME_NAME.test(path.basename(rel))) return 'fichier de jeu, de sauvegarde ou de firmware';
  const card = cartridge(head(file), statSync(file).size);
  return card ? `en-tête de cartouche ${card}` : null;
}

function main(argv) {
  const problems = [];
  if (argv[0] === '--package') {
    const dir = path.resolve(argv[1] || '');
    const files = walk(dir);
    for (const rel of files) {
      const why = checkPackageFile(dir, rel);
      if (why) problems.push(`${rel} : ${why}`);
    }
    report(problems, `paquet ${dir} (${files.length} fichiers)`);
    return;
  }
  const list = allowlist();
  const files = repoFiles();
  if (argv[0] === '--list') {
    for (const rel of files) {
      const ext = path.extname(rel).toLowerCase();
      const big = statSync(path.join(ROOT, rel)).size > MAX_UNLISTED;
      if ((MEDIA.has(ext) || ARCHIVES.has(ext) || big) && list.get(rel) !== sha256(path.join(ROOT, rel))) console.log(`${sha256(path.join(ROOT, rel))}  ${rel}`);
    }
    return;
  }
  for (const rel of files) {
    const why = checkRepoFile(rel, list);
    if (why) problems.push(`${rel} : ${why}`);
  }
  for (const rel of list.keys()) {
    if (!files.includes(rel)) problems.push(`${rel} : dans tests/assets-allowlist.txt mais absent du dépôt (retirer la ligne)`);
  }
  report(problems, `dépôt (${files.length} fichiers)`);
}

function report(problems, what) {
  if (problems.length) {
    console.log(`ÉCHEC  ressources tierces, ${what} :`);
    for (const p of problems) console.log(`  ${p}`);
    process.exit(1);
  }
  console.log(`ok     ressources tierces : rien de suspect dans le ${what}`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main(process.argv.slice(2));
