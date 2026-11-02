// Garde-fous juridiques de Cable Club pour Claude Code (voir CLAUDE.md, section « Garde-fous »).
//   node guard.mjs pre   avant un outil : bloque l'écriture d'une ROM, d'une sauvegarde ou d'un BIOS
//                        dans le dépôt, et leur ajout ou commit avec git
//   node guard.mjs post  après une écriture : avertit sur les fichiers sensibles
// Lit sur l'entrée standard le JSON que Claude Code envoie aux hooks.
import { execFileSync } from 'node:child_process';
import { readFileSync, statSync } from 'node:fs';
import path from 'node:path';

const GAME_FILES = new Set(['.gb', '.gbc', '.gba', '.nds', '.sav', '.dsv', '.sgm', '.srm']);
const MEDIA = new Set(['.png', '.jpg', '.jpeg', '.gif', '.webp', '.svg', '.ico', '.bmp', '.avif', '.tif', '.tiff',
    '.wav', '.mp3', '.ogg', '.oga', '.flac', '.m4a', '.aac', '.opus', '.mid', '.midi', '.mp4', '.webm']);

const ROM_PATCH = 'SltPatches.java et Rando.java modifient une copie de la ROM : statut juridique ⚠️. '
    + 'Préférer une modification de la RAM (CJUE Sony c. Datel, C-159/23) et signaler à l\'utilisateur '
    + 'tout changement de statut juridique avant de l\'implémenter.';
const NETWORK = 'RoomRoms.java, ServerLink.java et ServerApi.java touchent au réseau : aucune ROM, aucun BIOS, '
    + 'aucune sauvegarde ni aucune ressource extraite d\'un jeu ne doit jamais être envoyée. '
    + 'Vérifier que la modification n\'envoie pas de fichier de jeu.';
const MEDIA_NOTE = 'Image, son ou vidéo dans web/, site/ ou docs/ : vérifier que ce n\'est pas une ressource '
    + 'd\'un jeu (sprite, musique, capture d\'écran, logo Nintendo ou Pokémon…).';

const phase = process.argv[2];
const input = JSON.parse(readFileSync(0, 'utf8') || '{}');
const root = path.resolve(process.env.CLAUDE_PROJECT_DIR || input.cwd || process.cwd());
const tool = input.tool_name || '';
const args = input.tool_input || {};

/** Chemin relatif au dépôt avec des « / », ou null s'il est en dehors. */
function inRepo(p) {
    if (!p) return null;
    const r = path.relative(root, path.resolve(input.cwd || root, p));
    if (!r || r.startsWith('..') || path.isAbsolute(r)) return null;
    return r.split(path.sep).join('/');
}

/** ROM, sauvegarde ou BIOS d'après le nom du fichier. */
function gameFile(name) {
    const base = path.basename(name.replace(/^["']|["']$/g, '')).toLowerCase();
    return GAME_FILES.has(path.extname(base))
        || /^(bios|firmware).*\.bin$/.test(base) || base.startsWith('gba_bios');
}

function warnings(rel) {
    const out = [];
    const base = path.posix.basename(rel);
    if (base === 'SltPatches.java' || base === 'Rando.java') out.push(ROM_PATCH);
    if (base === 'RoomRoms.java' || base === 'ServerLink.java' || base === 'ServerApi.java') out.push(NETWORK);
    if (/^(web|site|docs)\//.test(rel) && MEDIA.has(path.posix.extname(base).toLowerCase())) out.push(MEDIA_NOTE);
    return out;
}

function git(...a) {
    try {
        return execFileSync('git', ['-C', root, ...a], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] })
            .split('\0').filter(Boolean);
    } catch {
        return [];
    }
}

function deny(reason) {
    console.log(JSON.stringify({ hookSpecificOutput: {
        hookEventName: 'PreToolUse', permissionDecision: 'deny', permissionDecisionReason: reason } }));
    process.exit(0);
}

function words(segment) {
    return (segment.match(/"[^"]*"|'[^']*'|\S+/g) || []).map(w => w.replace(/^["']|["']$/g, ''));
}

/** Vérifie une commande shell : git add et git commit de fichiers de jeu. */
function checkShell(command) {
    const notes = [];
    for (const segment of command.split(/&&|\|\||[;|\r\n]/)) {
        const w = words(segment.trim());
        const g = w.indexOf('git');
        if (g < 0) continue;
        let i = g + 1;
        while (i < w.length && w[i].startsWith('-')) i += w[i] === '-C' || w[i] === '-c' ? 2 : 1;
        const sub = w[i];
        const rest = w.slice(i + 1);
        if (sub === 'add') {
            const named = rest.filter(a => !a.startsWith('-'));
            const bad = named.filter(gameFile);
            if (bad.length) deny(`Ajout refusé : ${bad.join(', ')} est une ROM, une sauvegarde ou un BIOS. `
                + 'Ces fichiers ne doivent jamais entrer dans le dépôt.');
            const force = rest.some(a => a === '-f' || a === '--force' || /^-[a-zA-Z]*f/.test(a));
            const wide = rest.some(a => a === '-A' || a === '--all') || named.some(isDirectory);
            if (force && wide) deny('git add --force sur un dossier peut ajouter des ROM ou des sauvegardes '
                + 'ignorées par .gitignore : ajouter les fichiers un par un.');
        } else if (sub === 'commit') {
            const all = rest.some(a => a === '--all' || /^-[a-zA-Z]*a/.test(a));
            const files = [...git('diff', '--cached', '--name-only', '-z'),
                ...(all ? git('diff', '--name-only', '-z') : [])];
            const bad = files.filter(gameFile);
            if (bad.length) deny(`Commit refusé : ${bad.join(', ')} est une ROM, une sauvegarde ou un BIOS. `
                + 'Le retirer de l\'index (git restore --staged <fichier>) avant de valider.');
            const added = git('diff', '--cached', '--name-only', '--diff-filter=A', '-z');
            if (added.some(f => warnings(f).includes(MEDIA_NOTE))) notes.push(MEDIA_NOTE);
            if (files.some(f => warnings(f).includes(ROM_PATCH))) notes.push(ROM_PATCH);
            if (files.some(f => warnings(f).includes(NETWORK))) notes.push(NETWORK);
        }
    }
    return notes;
}

function isDirectory(p) {
    try {
        return statSync(path.resolve(input.cwd || root, p)).isDirectory();
    } catch {
        return false;
    }
}

const file = args.file_path || args.notebook_path;
if (phase === 'pre') {
    if (file) {
        const rel = inRepo(file);
        if (rel && gameFile(rel)) deny(`Écriture refusée : ${rel} est une ROM, une sauvegarde ou un BIOS. `
            + 'Ces fichiers ne doivent jamais être créés dans le dépôt (les tests travaillent sur des copies '
            + 'dans le dossier temporaire de la session).');
    } else if ((tool === 'Bash' || tool === 'PowerShell') && args.command) {
        const notes = [...new Set(checkShell(args.command))];
        if (notes.length) console.log(JSON.stringify({ systemMessage: 'Garde-fou Cable Club : ' + notes.join(' ') }));
    }
} else if (phase === 'post' && file) {
    const rel = inRepo(file);
    const notes = rel ? warnings(rel) : [];
    if (notes.length) {
        const text = 'Garde-fou Cable Club : ' + notes.join(' ');
        console.log(JSON.stringify({ systemMessage: text,
            hookSpecificOutput: { hookEventName: 'PostToolUse', additionalContext: text } }));
    }
}
