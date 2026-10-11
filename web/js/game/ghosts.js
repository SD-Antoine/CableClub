// The other players drawn inside the game: their real sprite at their place
// on the map, their name above their head and an icon telling what they are
// doing (battle, dialogue, trade, disconnected). A player in battle or in a
// menu stays where they left the field, with their icon.
//
// Their way is replayed as they walked it: each message carries their path
// image by image with their own clock, played back a little later (just
// enough to absorb the irregularity of the network), one pixel per image
// with each walking frame at its moment. The layer is drawn together with
// each image of this player's game, so the others never lag behind the map.

const ICONS = {
  // two Poké Balls and a shock between them
  battle: `<svg viewBox="0 0 26 12" width="26" height="12" aria-hidden="true">
    <g stroke="#1b1b1b" stroke-width="1">
      <circle cx="5" cy="6" r="4.5" fill="#f2f2f2"/><path d="M0.5 6a4.5 4.5 0 0 1 9 0z" fill="#e5463a"/><path d="M0.5 6h9"/>
      <circle cx="5" cy="6" r="1.3" fill="#f2f2f2"/>
      <circle cx="21" cy="6" r="4.5" fill="#f2f2f2"/><path d="M16.5 6a4.5 4.5 0 0 1 9 0z" fill="#e5463a"/><path d="M16.5 6h9"/>
      <circle cx="21" cy="6" r="1.3" fill="#f2f2f2"/>
    </g>
    <path d="M14 1l-4 6h3l-1.5 4.5L16 5h-3z" fill="#ffd23f" stroke="#7a5a00" stroke-width=".6" stroke-linejoin="round"/>
  </svg>`,
  // three dots jumping one after the other (animated in CSS)
  dialogue: `<span class="pg-dots"><i></i><i></i><i></i></span>`,
  // Wi-Fi with a slash
  offline: `<svg viewBox="0 0 14 12" width="14" height="12" aria-hidden="true">
    <g fill="none" stroke="#f2f2f2" stroke-width="1.4" stroke-linecap="round">
      <path d="M1.5 4.5a8 8 0 0 1 11 0"/><path d="M3.5 6.8a5 5 0 0 1 7 0"/><path d="M5.6 9a2 2 0 0 1 2.8 0"/>
    </g>
    <path d="M2 11L12 1" stroke="#ef5350" stroke-width="1.6" stroke-linecap="round"/>
  </svg>`,
  // two Poké Balls and two arrows turning anticlockwise
  trade: `<svg viewBox="0 0 26 12" width="26" height="12" aria-hidden="true">
    <g stroke="#1b1b1b" stroke-width="1">
      <circle cx="5" cy="6" r="4.5" fill="#f2f2f2"/><path d="M0.5 6a4.5 4.5 0 0 1 9 0z" fill="#e5463a"/><path d="M0.5 6h9"/>
      <circle cx="21" cy="6" r="4.5" fill="#f2f2f2"/><path d="M16.5 6a4.5 4.5 0 0 1 9 0z" fill="#e5463a"/><path d="M16.5 6h9"/>
    </g>
    <g fill="none" stroke="#7fd1ff" stroke-width="1.2" stroke-linecap="round">
      <path d="M15.5 4a3 3 0 0 0-5 0"/><path d="M10.5 8a3 3 0 0 0 5 0"/>
    </g>
    <path d="M10 2.6l.5 1.8 1.7-.6z" fill="#7fd1ff"/><path d="M16 9.4l-.5-1.8-1.7.6z" fill="#7fd1ff"/>
  </svg>`,
};

// the sender groups the way of 100 ms per message
const BATCH_MS = 100;
// a jump longer than this is a warp (door, stairs): no glide (3D games: in 1/16 of a world unit, 3 tiles)
const WARP_PX = 48;
const WARP_3D = 768;
// two readings further apart than this: the player stood still in between (older SLT, at rest)
const GLIDE_MS = 200;

export class GhostLayer {
  /**
   * @param layer element laid over the game image, sized in game pixels
   * @param info  (userId) -> { name, color, online, trading } of a member of the room
   */
  constructor(layer, info) {
    this.layer = layer;
    this.info = info;
    this.me = null;
    this.ghosts = new Map();
    this.raf = 0;
    this.tick = () => { this.raf = requestAnimationFrame(this.tick); this.draw(); };
  }

  start() {
    if (!this.raf) this.raf = requestAnimationFrame(this.tick);
  }

  stop() {
    if (this.raf) cancelAnimationFrame(this.raf);
    this.raf = 0;
  }

  /** Where this player stands on the image just drawn: the others are placed on that same image. */
  setMe(me) {
    this.me = me;
    this.draw();
  }

  ghost(userId) {
    let s = this.ghosts.get(userId);
    if (s) return s;
    const name = document.createElement('div');
    name.className = 'pg-name';
    const icon = document.createElement('div');
    icon.className = 'pg-icon';
    const el = document.createElement('div');
    el.className = 'pg';
    el.append(icon, name);
    this.layer.appendChild(el);
    s = {
      el, name, icon, iconKey: '',
      sprites: new Map(), shown: null, // sprite id -> { img, w, h }
      pts: [], offs: [], offset: 0, delay: BATCH_MS + 60, clock: null,
      st: 'free', seen: 0,
    };
    this.ghosts.set(userId, s);
    return s;
  }

  /** A sprite of this player, decoded once: switching between walking frames is then instant. */
  sprite(s, sid, png, w, h) {
    if (!png || s.sprites.has(sid)) return;
    const img = document.createElement('img');
    img.alt = '';
    img.className = 'pg-sprite';
    img.src = `data:image/png;base64,${png}`;
    const sw = Number(w) || 16;
    const sh = Number(h) || 32;
    img.style.width = `${sw}px`;
    img.style.height = `${sh}px`;
    img.style.display = 'none';
    img.decode?.().catch(() => {});
    s.el.appendChild(img);
    s.sprites.set(sid, { img, w: sw, h: sh });
    if (s.sprites.size > 64) {
      const [oldest, o] = s.sprites.entries().next().value;
      if (oldest !== s.shown) { o.img.remove(); s.sprites.delete(oldest); }
    }
  }

  /** A message about another player (event from the server, or the room's latest list). */
  update(g) {
    if (!g || !g.userId) return;
    const s = this.ghost(g.userId);
    const now = Date.now();
    if (g.sprs && typeof g.sprs === 'object') {
      for (const [sid, v] of Object.entries(g.sprs)) if (Array.isArray(v)) this.sprite(s, sid, v[0], v[1], v[2]);
    }
    if (g.spr) this.sprite(s, g.sid ?? '', g.spr, g.sw, g.sh);
    s.seen = typeof g.at === 'number' && g.at < now ? g.at : now;
    if (g.st === 'offline') { s.st = 'offline'; return; }
    if (g.st) s.st = g.st;
    if (typeof g.map !== 'number' || typeof g.x !== 'number') return;

    const points = [];
    // live messages of SLT 0.4.5+ carry the sender's clock; the room's latest list (at) and older SLT do not
    const clock = typeof g.t === 'number' && typeof g.at !== 'number' ? 'sender' : 'local';
    if (clock !== s.clock) { s.clock = clock; s.pts = []; s.offs = []; }
    if (clock === 'sender') {
      // how late this message is compared with the quickest one of the last seconds
      const off = now - g.t;
      s.offs.push([now, off]);
      while (s.offs.length > 1 && now - s.offs[0][0] > 10000) s.offs.shift();
      let min = Infinity;
      for (const [, o] of s.offs) min = Math.min(min, o);
      let late = 0;
      for (const [at, o] of s.offs) if (now - at < 5000) late = Math.max(late, o - min);
      s.offset = min;
      s.delay = Math.min(1000, BATCH_MS + late + 40);
      if (Array.isArray(g.pts)) {
        for (const p of g.pts) {
          if (Array.isArray(p) && p.length >= 4) points.push({ t: p[0], map: p[1], x: p[2], y: p[3], sid: p[4] ?? null, z: p[5] });
        }
      }
      points.push({ t: g.t, map: g.map, x: g.x, y: g.y, sid: g.sid ?? null, z: g.z });
    } else {
      // from one reading to the next; the room's latest list places the player at once
      s.offset = 0;
      s.delay = BATCH_MS + 40;
      if (typeof g.at === 'number') s.pts = [];
      points.push({ t: now, map: g.map, x: g.x, y: g.y, sid: g.sid ?? null, z: g.z });
    }
    for (const p of points) {
      const last = s.pts[s.pts.length - 1];
      if (last && p.t < last.t) continue; // already played
      if (last && p.t === last.t) { Object.assign(last, p); continue; }
      s.pts.push(p);
    }
  }

  /** Where a player was at a moment of their own clock: the point before it, glided towards the next one. */
  static at(pts, t) {
    // forget what was played long ago, keeping the point being played
    while (pts.length > 2 && pts[1].t <= t - 2000) pts.shift();
    let i = 0;
    while (i + 1 < pts.length && pts[i + 1].t <= t) i++;
    const a = pts[i];
    const b = pts[i + 1];
    const warp = typeof a.z === 'number' ? WARP_3D : WARP_PX;
    if (!b || t <= a.t || b.map !== a.map || Math.abs(b.x - a.x) > warp || Math.abs(b.y - a.y) > warp
      || Math.abs((b.z ?? 0) - (a.z ?? 0)) > warp) return a;
    const from = Math.max(a.t, b.t - GLIDE_MS);
    if (t <= from) return a;
    const k = (t - from) / (b.t - from);
    const z = typeof a.z === 'number' && typeof b.z === 'number' ? a.z + (b.z - a.z) * k : a.z;
    return { map: a.map, x: a.x + (b.x - a.x) * k, y: a.y + (b.y - a.y) * k, z, sid: a.sid };
  }

  /** Where a point of the 3D world (in world units) appears on the top screen (256 x 192), and its depth; null if behind. */
  static project(m, x, y, z) {
    const w = x * m[3] + y * m[7] + z * m[11] + m[15];
    if (w <= 0.01) return null;
    return {
      x: ((x * m[0] + y * m[4] + z * m[8] + m[12]) / w + 1) * 128,
      y: (1 - (x * m[1] + y * m[5] + z * m[9] + m[13]) / w) * 96,
      w,
    };
  }

  /**
   * Box of a player's sprite on this image: 2D games, from where this player stands; 3D games, the
   * point of the world projected with the camera, its feet on that point, the sprite one texel per
   * pixel at the depth of this player's feet (as the game draws its characters), smaller farther.
   */
  static place(me, p, sprite) {
    if (!me.mtx) {
      return { left: me.ax + (p.x - me.x) - sprite.w / 2, top: me.ay + (p.y - me.y) - sprite.h, scale: 1 };
    }
    if (typeof p.z !== 'number') return null;
    const x = p.x / 16;
    const y = p.y / 16;
    const z = p.z / 16;
    const feet = GhostLayer.project(me.mtx, x, y, z);
    if (!feet) return null;
    const scale = Math.max(0.25, Math.min(4, (me.depth > 0 ? me.depth : feet.w) / feet.w));
    // measured on White 2: the game draws the sprite one pixel to the right of the point
    return { left: feet.x + 1 - (sprite.w * scale) / 2, top: feet.y - sprite.h * scale, scale, bottom: 192 };
  }

  /**
   * The moment of the player's own clock shown now: it runs at the speed of time and follows the target
   * (the latest news minus the delay) at most 10 % faster or slower, so that it never goes back.
   */
  static playhead(s, now) {
    const target = now - s.offset - s.delay;
    if (s.play === undefined || s.clockAt !== s.clock || Math.abs(target - s.play) > 1500) {
      s.play = target;
    } else {
      const dt = Math.max(0, now - s.drawnAt);
      s.play += dt + Math.max(-0.1 * dt, Math.min(0.1 * dt, target - s.play - dt));
    }
    s.clockAt = s.clock;
    s.drawnAt = now;
    return s.play;
  }

  draw() {
    const me = this.me;
    const now = Date.now();
    for (const [userId, s] of this.ghosts) {
      const who = this.info(userId);
      const p = s.pts.length ? GhostLayer.at(s.pts, GhostLayer.playhead(s, now)) : null;
      const sid = p && s.sprites.has(p.sid ?? '') ? (p.sid ?? '') : s.shown;
      const sprite = sid !== null ? s.sprites.get(sid) : null;
      // only while this player has the controls: never over a battle, a dialogue, a menu or a transition
      const visible = me && (me.free ?? me.ow) && p && p.map === me.map && sprite && who;
      if (!visible) { s.el.style.display = 'none'; continue; }
      const box = GhostLayer.place(me, p, sprite);
      const left = box && Math.round(box.left);
      const top = box && Math.round(box.top);
      // 3D games (DS): only on the top screen
      if (!box || left < -40 || left > 300 || top < -60 || top > 220
        || (box.bottom !== undefined && top + sprite.h * box.scale > box.bottom + 8)) { s.el.style.display = 'none'; continue; }
      if (sid !== s.shown) {
        if (s.shown !== null) s.sprites.get(s.shown)?.img.style.setProperty('display', 'none');
        sprite.img.style.display = 'block';
        s.shown = sid;
      }
      const w = Math.round(sprite.w * box.scale);
      const h = Math.round(sprite.h * box.scale);
      if (sprite.img.style.width !== `${w}px`) { sprite.img.style.width = `${w}px`; sprite.img.style.height = `${h}px`; }
      s.el.style.display = '';
      s.el.style.transform = `translate(${left}px, ${top}px)`;
      s.el.style.width = `${w}px`;
      const offline = who.online === false || now - s.seen > 8000 || s.st === 'offline';
      s.el.classList.toggle('offline', offline);
      if (s.name.textContent !== who.name) s.name.textContent = who.name;
      s.name.style.setProperty('--player', who.color);
      const key = offline ? 'offline' : who.trading ? 'trade' : ['battle', 'dialogue', 'trade'].includes(s.st) ? s.st : '';
      if (key !== s.iconKey) {
        s.iconKey = key;
        s.icon.innerHTML = key ? ICONS[key] : '';
        s.icon.style.display = key ? '' : 'none';
      }
    }
  }

  clear() {
    this.stop();
    for (const s of this.ghosts.values()) s.el.remove();
    this.ghosts.clear();
  }
}
