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
// 3D games: height of the message box over the top or the bottom of the screen, where the others stay hidden
const BOX_H = 46;
// a player walking this close behind (in pixels) is partly hidden by this player's sprite
const BEHIND_X = 20;
const BEHIND_Y = 34;
// sprites of the Pokémon following a player: "g4p-<texture>-<image>"
const FOLLOWER_PREFIX = 'g4p-';
// a character of the game (a person) fills, in its 32 x 32 sprite, the columns 7 to 23 and the rows 6 to 30: another
// character that stands in front of a player hides the part of them under that box
const CHAR_X1 = 7;
const CHAR_X2 = 24;
const CHAR_Y1 = 6;
const CHAR_Y2 = 31;

/** The Pokémon following, in a point of the way [t, map, x, y, sprite, z, pet x, y, z, pet sprite]; null when there is none. */
function petOf(p) {
  return p.length >= 10 && typeof p[9] === 'string' && typeof p[6] === 'number'
    ? { x: p[6], y: p[7], z: p[8], sid: p[9] } : null;
}

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
    this.selves = new Map(); // number -> { img, w, h }: this player's own sprites (3D games)
    this.selfShown = null;
    this.width = 0;
    this.height = 0;
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

  /**
   * One of this player's own sprites (3D games), as the game draws it: redrawn over the others who walk behind
   * them, since the others are laid over the image of the game, where this player is already drawn.
   */
  setSelf(m) {
    if (!m || !m.png || this.selves.has(m.n)) return;
    const img = document.createElement('img');
    img.alt = '';
    img.className = 'pg-self';
    img.src = `data:image/png;base64,${m.png}`;
    img.style.width = `${Number(m.w) || 32}px`;
    img.style.height = `${Number(m.h) || 32}px`;
    img.style.display = 'none';
    img.decode?.().catch(() => {});
    this.layer.appendChild(img);
    this.selves.set(m.n, { img, w: Number(m.w) || 32, h: Number(m.h) || 32 });
    if (this.selves.size > 128) {
      const [oldest, o] = this.selves.entries().next().value;
      if (oldest !== this.selfShown) { o.img.remove(); this.selves.delete(oldest); }
    }
  }

  /** Size of the image of the game, in its pixels (the layer covers it). */
  setSize(w, h) {
    this.width = w;
    this.height = h;
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

  /** The Pokémon following a player (HeartGold / SoulSilver): its own element and sprites, drawn like a player without name. */
  followerOf(s) {
    if (!s.pet) {
      const el = document.createElement('div');
      el.className = 'pg pg-pet';
      this.layer.appendChild(el);
      s.pet = { el, sprites: new Map(), shown: null };
    }
    return s.pet;
  }

  /** A sprite of this player (or of the Pokémon following them), decoded once: switching between frames is then instant. */
  sprite(s, sid, png, w, h) {
    const holder = String(sid).startsWith(FOLLOWER_PREFIX) ? this.followerOf(s) : s;
    if (!png || holder.sprites.has(sid)) return;
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
    holder.el.appendChild(img);
    holder.sprites.set(sid, { img, w: sw, h: sh });
    if (holder.sprites.size > 128) {
      const [oldest, o] = holder.sprites.entries().next().value;
      if (oldest !== holder.shown) { o.img.remove(); holder.sprites.delete(oldest); }
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
      let pet = null;
      if (Array.isArray(g.pts)) {
        for (const p of g.pts) {
          if (Array.isArray(p) && p.length >= 4) {
            pet = petOf(p);
            points.push({ t: p[0], map: p[1], x: p[2], y: p[3], sid: p[4] ?? null, z: p[5], pet });
          }
        }
      }
      // the Pokémon following travels inside the points of the way (the server relays no field of its own): the
      // live position has the one of the last point
      points.push({ t: g.t, map: g.map, x: g.x, y: g.y, sid: g.sid ?? null, z: g.z, pet });
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
    // the Pokémon following glides on its own (it does not jump: no glide over a warp)
    let pet = a.pet ?? null;
    if (pet && b.pet && Math.abs(b.pet.x - pet.x) <= warp && Math.abs(b.pet.y - pet.y) <= warp && Math.abs(b.pet.z - pet.z) <= warp) {
      pet = { x: pet.x + (b.pet.x - pet.x) * k, y: pet.y + (b.pet.y - pet.y) * k, z: pet.z + (b.pet.z - pet.z) * k, sid: pet.sid };
    }
    return { map: a.map, x: a.x + (b.x - a.x) * k, y: a.y + (b.y - a.y) * k, z, sid: a.sid, pet };
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
      const top = me.ay + (p.y - me.y) - sprite.h;
      return { left: me.ax + (p.x - me.x) - sprite.w / 2, top, scale: 1, feetY: top + sprite.h };
    }
    if (typeof p.z !== 'number') return null;
    const x = p.x / 16;
    const y = p.y / 16;
    const z = p.z / 16;
    const feet = GhostLayer.project(me.mtx, x, y, z);
    if (!feet) return null;
    const scale = Math.max(0.25, Math.min(4, (me.depth > 0 ? me.depth : feet.w) / feet.w));
    // measured on White 2: the game draws the sprite one pixel to the right of the point (me.dx); in generation 4 the
    // point of the ground is a few rows above the bottom of the sprite (me.drop)
    const drop = me.drop || 0;
    return { left: feet.x + (me.dx ?? 1) - (sprite.w * scale) / 2, top: feet.y - (sprite.h - drop) * scale, scale, bottom: 192 + drop * scale, feetY: feet.y };
  }

  /**
   * Cuts out of a drawn character the parts that other characters of the game (people, Pokémon) standing in front
   * of it hide: the game draws those in its own image, under this layer. A rectangle of the size of a character
   * around the feet of each (the image of the game stays visible there). Empty when nothing is in the way.
   */
  static cuts(me, left, top, w, h, feetY) {
    const npcs = me.others;
    if (!npcs || !npcs.length) return '';
    let path = '';
    const drop = me.drop || 0;
    for (const n of npcs) {
      if (n.y - feetY <= 0.5) continue; // behind or level with this one: it stays in front
      const k = me.depth > 0 && n.w > 0 ? Math.max(0.25, Math.min(4, me.depth / n.w)) : 1;
      const qx = n.x + (me.dx ?? 1) - 16 * k; // where its sprite is drawn (the sprites of the others: see place)
      const qy = n.y - (32 - drop) * k;
      const x1 = Math.round(qx + CHAR_X1 * k);
      const x2 = Math.round(qx + CHAR_X2 * k);
      const y1 = Math.round(qy + CHAR_Y1 * k);
      const y2 = Math.round(qy + CHAR_Y2 * k);
      if (x2 <= left || x1 >= left + w || y2 <= top || y1 >= top + h) continue;
      path += ` M${x1 - left} ${y1 - top}H${x2 - left}V${y2 - top}H${x1 - left}Z`;
    }
    return path ? `path(evenodd, "M-800 -800H1200V1200H-800Z${path}")` : '';
  }

  /**
   * Places one character drawn over the game (a player, or the Pokémon following one) with the sprite {@code sid} of
   * {@code holder} ({ el, sprites, shown }) at {@code pos}. Returns null when it stays hidden, else { covered }:
   * whether it walks behind this player (whose sprite is then redrawn over it).
   */
  drawOne(me, holder, sid, pos, mine) {
    const sprite = holder.sprites.get(sid);
    const hide = () => { holder.el.style.display = 'none'; return null; };
    if (!sprite) return hide();
    const box = GhostLayer.place(me, pos, sprite);
    const left = box && Math.round(box.left);
    const top = box && Math.round(box.top);
    // 3D games (DS): only on the top screen
    if (!box || left < -40 || left > 300 || top < -60 || top > 220
      || (box.bottom !== undefined && top + sprite.h * box.scale > box.bottom + 8)) return hide();
    const w = Math.round(sprite.w * box.scale);
    const h = Math.round(sprite.h * box.scale);
    let covered = false;
    if (me.feet) {
      // in front of this player: over them; behind: under them (their sprite is redrawn over, or the other hidden)
      const feetY = box.feetY;
      const behind = me.feet.y - feetY > 0.5 && me.feet.y - feetY < BEHIND_Y && Math.abs(left + w / 2 - (me.feet.x + (me.dx ?? 1))) < BEHIND_X;
      if (behind && !mine) return hide();
      covered = behind;
      holder.el.style.zIndex = String(Math.round(feetY * 2) + 1);
    }
    if (sid !== holder.shown) {
      if (holder.shown !== null) holder.sprites.get(holder.shown)?.img.style.setProperty('display', 'none');
      sprite.img.style.display = 'block';
      holder.shown = sid;
    }
    if (sprite.img.style.width !== `${w}px`) { sprite.img.style.width = `${w}px`; sprite.img.style.height = `${h}px`; }
    holder.el.style.display = '';
    holder.el.style.transform = `translate(${left}px, ${top}px)`;
    holder.el.style.width = `${w}px`;
    const cut = me.feet ? GhostLayer.cuts(me, left, top, w, h, box.feetY) : '';
    if (holder.cut !== cut) { holder.el.style.clipPath = cut; holder.cut = cut; }
    return { covered };
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
    // 3D games (DS): the others stay on the top screen (256 x 192, top left of the image, the touch screen being
    // under it or on its right), and behind a message box drawn over its top or its bottom
    let clip = '';
    if (me?.mtx) {
      const right = Math.max(0, this.width - 256);
      const bottom = Math.max(0, this.height - 192) + (me.boxBottom ? BOX_H : 0);
      clip = `inset(${me.boxTop ? BOX_H : 0}px ${right}px ${bottom}px 0)`;
    }
    if (this.layer.style.clipPath !== clip) this.layer.style.clipPath = clip;
    // the others walking behind this player (their feet higher on the image) pass under their sprite
    const mine = me?.feet ? this.selves.get(me.self) ?? null : null;
    let covered = false;
    for (const [userId, s] of this.ghosts) {
      const who = this.info(userId);
      const p = s.pts.length ? GhostLayer.at(s.pts, GhostLayer.playhead(s, now)) : null;
      const sid = p && s.sprites.has(p.sid ?? '') ? (p.sid ?? '') : s.shown;
      const sprite = sid !== null ? s.sprites.get(sid) : null;
      // only while the field is shown: never over a battle, a menu or a transition (3D games: a message box stays over them)
      const visible = me && (me.free ?? me.ow) && p && p.map === me.map && sprite && who;
      if (!visible) {
        s.el.style.display = 'none';
        if (s.pet) s.pet.el.style.display = 'none';
        continue;
      }
      const drawn = this.drawOne(me, s, sid, p, mine);
      if (drawn) {
        covered ||= drawn.covered;
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
      // the Pokémon following (3D games)
      if (s.pet) {
        const petSid = p.pet && s.pet.sprites.has(p.pet.sid) ? p.pet.sid : null;
        const petDrawn = petSid !== null && drawn && me.mtx ? this.drawOne(me, s.pet, petSid, p.pet, mine) : null;
        if (!petDrawn) s.pet.el.style.display = 'none';
        else {
          covered ||= petDrawn.covered;
          s.pet.el.classList.toggle('offline', s.el.classList.contains('offline'));
        }
      }
    }
    const shown = covered ? mine : null;
    if (shown !== this.selfShown) {
      if (this.selfShown) this.selfShown.img.style.display = 'none';
      this.selfShown = shown;
    }
    if (shown) {
      const st = shown.img.style;
      st.display = 'block';
      st.transform = `translate(${Math.round(me.feet.x + (me.dx ?? 1) - shown.w / 2)}px, ${Math.round(me.feet.y - (shown.h - (me.drop || 0)))}px)`;
      st.zIndex = String(Math.round(me.feet.y * 2));
    }
  }

  clear() {
    this.stop();
    for (const s of this.ghosts.values()) { s.el.remove(); s.pet?.el.remove(); }
    this.ghosts.clear();
  }
}
