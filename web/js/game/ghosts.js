// The other players drawn inside the game (generation 3): their real sprite
// at their place on the map, their name above their head and an icon telling
// what they are doing (battle, dialogue, trade, disconnected). A player in
// battle or in a menu stays where they left the field, with their icon.

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

export class GhostLayer {
  /**
   * @param layer element laid over the game image, sized in game pixels (240x160)
   * @param info  (userId) -> { name, color, online } of a member of the room
   */
  constructor(layer, info) {
    this.layer = layer;
    this.info = info;
    this.me = null;
    this.ghosts = new Map();
    this.raf = 0;
    this.last = 0;
    this.tick = (t) => this.render(t);
  }

  start() {
    if (!this.raf) this.raf = requestAnimationFrame(this.tick);
  }

  stop() {
    if (this.raf) cancelAnimationFrame(this.raf);
    this.raf = 0;
  }

  /** Where this player stands, read with every image of the game. */
  setMe(me) {
    this.me = me;
  }

  /** Latest reading of another player (event from the server, or the initial list). */
  update(g) {
    if (!g || !g.userId) return;
    let s = this.ghosts.get(g.userId);
    if (!s) {
      const img = document.createElement('img');
      img.alt = '';
      img.className = 'pg-sprite';
      const name = document.createElement('div');
      name.className = 'pg-name';
      const icon = document.createElement('div');
      icon.className = 'pg-icon';
      const el = document.createElement('div');
      el.className = 'pg';
      el.append(icon, name, img);
      this.layer.appendChild(el);
      s = { el, img, name, icon, x: null, y: null, tx: 0, ty: 0, map: -1, ow: false, st: 'free', sw: 16, sh: 32, at: 0, iconKey: '' };
      this.ghosts.set(g.userId, s);
    }
    if (g.spr) {
      s.img.src = `data:image/png;base64,${g.spr}`;
      s.sw = Number(g.sw) || 16;
      s.sh = Number(g.sh) || 32;
      s.img.style.width = `${s.sw}px`;
      s.img.style.height = `${s.sh}px`;
      s.hasSprite = true;
    }
    if (g.st === 'offline') {
      s.st = 'offline';
    } else {
      if (typeof g.map === 'number') {
        if (g.map !== s.map) { s.x = null; } // other map: no glide across maps
        s.map = g.map;
      }
      if (typeof g.x === 'number') { s.tx = g.x; s.ty = g.y; }
      if (typeof g.ow === 'boolean') s.ow = g.ow;
      if (g.st) s.st = g.st;
    }
    s.at = typeof g.at === 'number' && g.at < Date.now() ? g.at : Date.now();
  }

  render(t) {
    this.raf = requestAnimationFrame(this.tick);
    const dt = this.last ? Math.min(0.1, (t - this.last) / 1000) : 0;
    this.last = t;
    const me = this.me;
    for (const [userId, s] of this.ghosts) {
      const who = this.info(userId);
      const stale = Date.now() - s.at > 8000;
      const offline = !who || who.online === false || stale || s.st === 'offline';
      // only while this player has the controls: never over a battle, a dialogue, a menu or a transition
      const visible = me && (me.free ?? me.ow) && s.map === me.map && s.hasSprite && who;
      if (!visible) { s.el.style.display = 'none'; continue; }
      if (s.x === null || Math.abs(s.tx - s.x) > 48 || Math.abs(s.ty - s.y) > 48) {
        s.x = s.tx; s.y = s.ty;
      } else {
        const k = Math.min(1, dt * 14);
        s.x += (s.tx - s.x) * k;
        s.y += (s.ty - s.y) * k;
      }
      const left = Math.round(me.ax + (s.x - me.x) - s.sw / 2);
      const top = Math.round(me.ay + (s.y - me.y) - s.sh);
      if (left < -40 || left > 280 || top < -60 || top > 200) { s.el.style.display = 'none'; continue; }
      s.el.style.display = '';
      s.el.style.transform = `translate(${left}px, ${top}px)`;
      s.el.style.width = `${s.sw}px`;
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
