// The game inside the SLT window: images from the player (WebSocket, RGBA),
// keyboard and mouse back to it.

import { BUTTONS, keyName } from './keys.js';

export class GameScreen {
  /**
   * @param canvas  where the game is drawn
   * @param keys    button / action -> key name
   * @param handlers { onInfo, onOsd, onClosed, onOpen, onAction }
   */
  constructor(canvas, keys, handlers) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d', { alpha: false });
    this.keys = keys;
    this.handlers = handlers;
    this.ws = null;
    this.pending = null;
    this.mask = 0;
    this.held = new Set();
    this.w = 0;
    this.h = 0;
    this.closedOnPurpose = false;
    this.raf = 0;
    this.onKeyDown = (e) => this.key(e, true);
    this.onKeyUp = (e) => this.key(e, false);
    this.onBlur = () => this.releaseAll();
    this.pointerDown = false;
    this.onMouse = (e) => this.mouse(e);
    // DS: 'v' = top screen above the touch screen (as the console), 'h' = side by side (touch screen on the right)
    this.layout = 'v';
    this.split = false;
    this.last = null;
  }

  /** Lays the two DS screens out one above the other ('v') or side by side ('h'); redraws the last image. */
  setLayout(layout) {
    this.layout = layout === 'h' ? 'h' : 'v';
    if (this.last) { this.pending = this.last; this.draw(); }
  }

  connect(port, token) {
    this.close();
    this.closedOnPurpose = false;
    const ws = new WebSocket(`ws://127.0.0.1:${port}/?t=${encodeURIComponent(token)}`);
    ws.binaryType = 'arraybuffer';
    ws.onopen = () => { this.handlers.onOpen?.(); this.send({ t: 'keys', m: this.mask }); };
    ws.onmessage = (e) => {
      if (typeof e.data === 'string') {
        let m;
        try { m = JSON.parse(e.data); } catch { return; }
        if (m.t === 'info') this.handlers.onInfo?.(m);
        else if (m.t === 'osd') this.handlers.onOsd?.(m.text);
        else if (m.t === 'self') this.handlers.onSelf?.(m);
        else if (m.t === 'closed') { this.closedOnPurpose = true; this.handlers.onClosed?.(true); }
        return;
      }
      this.pending = e.data;
      if (!this.raf) this.raf = requestAnimationFrame(() => this.draw());
    };
    ws.onclose = () => { if (this.ws === ws) { this.ws = null; this.handlers.onClosed?.(this.closedOnPurpose); } };
    this.ws = ws;
    window.addEventListener('keydown', this.onKeyDown, true);
    window.addEventListener('keyup', this.onKeyUp, true);
    window.addEventListener('blur', this.onBlur);
    this.canvas.addEventListener('mousedown', this.onMouse);
    window.addEventListener('mousemove', this.onMouse);
    window.addEventListener('mouseup', this.onMouse);
  }

  close() {
    window.removeEventListener('keydown', this.onKeyDown, true);
    window.removeEventListener('keyup', this.onKeyUp, true);
    window.removeEventListener('blur', this.onBlur);
    this.canvas.removeEventListener('mousedown', this.onMouse);
    window.removeEventListener('mousemove', this.onMouse);
    window.removeEventListener('mouseup', this.onMouse);
    if (this.ws) {
      const ws = this.ws;
      this.ws = null;
      try { ws.close(); } catch { /* already closed */ }
    }
    if (this.raf) cancelAnimationFrame(this.raf);
    this.raf = 0;
  }

  get connected() {
    return !!this.ws && this.ws.readyState === WebSocket.OPEN;
  }

  send(obj) {
    if (this.connected) this.ws.send(JSON.stringify(obj));
  }

  cmd(c, extra = {}) {
    this.send({ t: 'cmd', c, ...extra });
  }

  /** Draws the latest image only (older ones were replaced before the screen refreshed). */
  draw() {
    this.raf = 0;
    const buf = this.pending;
    this.pending = null;
    if (!buf || buf.byteLength < 5) return;
    const v = new DataView(buf);
    const type = v.getUint8(0);
    if (type !== 1 && type !== 2 && type !== 3) return;
    const w = v.getUint16(1, true);
    const h = v.getUint16(3, true);
    // type 2: where the player stands, to draw the other players at the right place on this image;
    // type 3 (3D games): the camera of this image, to place them in the world, then the other characters drawn on it
    // (number at 94, then 12 bytes each from 96)
    let head = type === 2 ? 14 : type === 3 ? 96 : 5;
    if (type === 3) {
      if (buf.byteLength < 96) return;
      head += 12 * v.getUint16(94, true);
    }
    if (w * h * 4 + head > buf.byteLength) return;
    this.last = buf;
    // the two DS screens side by side: the touch screen (lower half of the image) goes to the right
    const split = this.layout === 'h' && w === 256 && h === 384;
    const cw = split ? 512 : w;
    const ch = split ? 192 : h;
    if (w !== this.w || h !== this.h || split !== this.split) {
      this.w = w;
      this.h = h;
      this.split = split;
      this.canvas.width = cw;
      this.canvas.height = ch;
      this.handlers.onResize?.(cw, ch);
    }
    const img = new ImageData(new Uint8ClampedArray(buf, head, w * h * 4), w, h);
    if (split) {
      this.ctx.putImageData(img, 0, 0, 0, 0, 256, 192);
      this.ctx.putImageData(img, 256, -192, 0, 192, 256, 192);
    } else {
      this.ctx.putImageData(img, 0, 0);
    }
    if (type === 2) {
      const map = v.getUint16(5, true);
      this.handlers.onFrame?.(map === 0xFFFF ? null : {
        map, x: v.getInt16(7, true), y: v.getInt16(9, true),
        ax: v.getUint8(11), ay: v.getUint8(12), ow: (v.getUint8(13) & 1) === 1, free: (v.getUint8(13) & 2) === 2,
      });
    } else if (type === 3) {
      const map = v.getUint16(5, true);
      const mtx = new Float32Array(16);
      for (let i = 0; i < 16; i++) mtx[i] = v.getFloat32(8 + 4 * i, true);
      // depth of this player's feet: the game draws the characters one texel per pixel there
      const depth = v.getFloat32(72, true);
      // where the feet are on this image, the rows of a sprite under the ground point it stands on and the columns its
      // middle lies right of it, the number of the sprite sent for it (0 = none), the message box and the other
      // characters of the game drawn on the image
      const flags = v.getUint8(7);
      const others = [];
      for (let i = 0, n = v.getUint16(94, true); i < n; i++) {
        others.push({ x: v.getFloat32(96 + 12 * i, true), y: v.getFloat32(100 + 12 * i, true), w: v.getFloat32(104 + 12 * i, true) });
      }
      this.handlers.onFrame?.(map === 0xFFFF ? null : {
        map, mtx, depth, feet: { x: v.getFloat32(76, true), y: v.getFloat32(80, true) }, drop: v.getFloat32(84, true),
        dx: v.getFloat32(88, true), self: v.getUint16(92, true), others,
        boxTop: (flags & 4) === 4, boxBottom: (flags & 8) === 8, ow: (flags & 1) === 1, free: (flags & 2) === 2,
      });
    }
  }

  // ------------------------------------------------------------- input

  key(e, down) {
    const t = e.target;
    if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.tagName === 'SELECT' || t.isContentEditable)) return;
    if (document.querySelector('.modal-back')) return; // a dialog is open (naming…)
    const k = keyName(e);
    let used = false;
    for (const b of BUTTONS) {
      if (this.keys[b.id] === k) {
        used = true;
        if (down) this.mask |= 1 << b.bit; else this.mask &= ~(1 << b.bit);
      }
    }
    if (used) {
      e.preventDefault();
      this.send({ t: 'keys', m: this.mask });
      return;
    }
    if (k === this.keys.FAST_HOLD) {
      e.preventDefault();
      if (down !== this.held.has('ff')) {
        if (down) this.held.add('ff'); else this.held.delete('ff');
        this.cmd('ff-hold', { on: down });
      }
      return;
    }
    if (!down || e.repeat) return;
    if (k === this.keys.FAST_TOGGLE) { e.preventDefault(); this.cmd('ff-toggle'); return; }
    if (k === this.keys.PAUSE) { e.preventDefault(); this.handlers.onAction?.('pause'); return; }
    if (k === this.keys.FULLSCREEN) { e.preventDefault(); this.handlers.onAction?.('fullscreen'); }
  }

  releaseAll() {
    this.mask = 0;
    this.send({ t: 'keys', m: 0 });
    if (this.held.has('ff')) { this.held.delete('ff'); this.cmd('ff-hold', { on: false }); }
  }

  /** Mouse = stylus on the DS touch screen (libretro pointer coordinates over the whole image). */
  mouse(e) {
    if (!this.w) return;
    if (e.type === 'mousedown') this.pointerDown = true;
    if (e.type === 'mouseup') { if (!this.pointerDown) return; this.pointerDown = false; }
    if (e.type === 'mousemove' && !this.pointerDown) return;
    const r = this.canvas.getBoundingClientRect();
    let fx = Math.max(0, Math.min(1, (e.clientX - r.left) / r.width));
    let fy = Math.max(0, Math.min(1, (e.clientY - r.top) / r.height));
    if (this.split) {
      // side by side: back to the place on the image of the game (top screen above the touch screen)
      if (fx >= 0.5) { fx = (fx - 0.5) * 2; fy = 0.5 + fy / 2; } else { fx *= 2; fy /= 2; }
    }
    this.send({ t: 'touch', x: Math.round((fx * 2 - 1) * 0x7fff), y: Math.round((fy * 2 - 1) * 0x7fff), d: this.pointerDown });
  }
}
