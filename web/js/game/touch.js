// Touch controls of the phone: a cross, the buttons of the console played (A B on the Game Boy, + L R on the
// Advance, + X Y on the DS), Start and Select. Several fingers at once; a finger may slide from one button to
// another, or around the cross, without lifting.

import { h } from '../ui.js';
import { BUTTONS } from './keys.js';

const BIT = Object.fromEntries(BUTTONS.map((b) => [b.id, b.bit]));

/** The buttons of each console (the cross aside). */
const FACE = {
  gb: ['A', 'B'],
  gba: ['A', 'B'],
  nds: ['A', 'B', 'X', 'Y'],
};

export class TouchPad {
  /**
   * @param platform 'gb', 'gba' or 'nds'
   * @param onMask   called with the libretro button mask whenever it changes
   * @param haptic   called on each new press (a short vibration), or null
   */
  constructor(platform, { onMask, haptic = null }) {
    this.platform = FACE[platform] ? platform : 'gba';
    this.onMask = onMask;
    this.haptic = haptic;
    this.mask = 0;
    // pointerId -> { cross: the finger started on the cross, mask: what it holds }
    this.fingers = new Map();
    const face = FACE[this.platform];
    const button = (id, cls = '') => h('div', { class: `tp-btn tp-${id.toLowerCase()} ${cls}`.trim(), dataset: { b: id } }, id);
    this.arms = ['UP', 'DOWN', 'LEFT', 'RIGHT'].map((d) => h('i', { class: `tp-arm ${d.toLowerCase()}`, dataset: { dir: d } }));
    this.dpad = h('div', { class: 'tp-dpad', dataset: { dpad: '1' } }, this.arms, h('i', { class: 'tp-hub' }));
    this.el = h('div', { class: `tp tp-${this.platform}` },
      this.dpad,
      h('div', { class: `tp-face n${face.length}` }, face.map((id) => button(id))),
      this.platform === 'gb' ? null : [button('L', 'tp-shoulder'), button('R', 'tp-shoulder')],
      h('div', { class: 'tp-meta' }, h('div', { class: 'tp-pill', dataset: { b: 'SELECT' } }, 'Select'), h('div', { class: 'tp-pill', dataset: { b: 'START' } }, 'Start')));
    const up = (e) => this.release(e);
    this.el.addEventListener('pointerdown', (e) => this.press(e));
    this.el.addEventListener('pointermove', (e) => { if (this.fingers.has(e.pointerId)) this.track(e); });
    this.el.addEventListener('pointerup', up);
    this.el.addEventListener('pointercancel', up);
    this.el.addEventListener('lostpointercapture', up);
    this.el.addEventListener('contextmenu', (e) => e.preventDefault());
  }

  press(e) {
    const on = e.target.closest('[data-b], [data-dpad]');
    if (!on) return;
    e.preventDefault();
    try { this.el.setPointerCapture(e.pointerId); } catch { /* the finger is already gone */ }
    this.fingers.set(e.pointerId, { cross: !!on.dataset.dpad, mask: 0 });
    this.track(e);
  }

  /** What a finger holds: a direction of the cross (eight sectors, diagonals included), or the button under it. */
  track(e) {
    const f = this.fingers.get(e.pointerId);
    let m = 0;
    if (f.cross) {
      const r = this.dpad.getBoundingClientRect();
      const dx = e.clientX - (r.left + r.width / 2);
      const dy = e.clientY - (r.top + r.height / 2);
      if (Math.hypot(dx, dy) > r.width * 0.12) {
        const s = ((Math.round(Math.atan2(dy, dx) / (Math.PI / 4)) % 8) + 8) % 8; // 0 = right, 2 = down, 4 = left, 6 = up
        if (s === 7 || s === 0 || s === 1) m |= 1 << BIT.RIGHT;
        if (s === 1 || s === 2 || s === 3) m |= 1 << BIT.DOWN;
        if (s === 3 || s === 4 || s === 5) m |= 1 << BIT.LEFT;
        if (s === 5 || s === 6 || s === 7) m |= 1 << BIT.UP;
      }
    } else {
      const el = document.elementFromPoint(e.clientX, e.clientY)?.closest('[data-b]');
      if (el && this.el.contains(el)) m = 1 << BIT[el.dataset.b];
    }
    if (m !== f.mask) {
      if (m & ~f.mask && this.haptic) this.haptic();
      f.mask = m;
      this.update();
    }
  }

  release(e) {
    if (!this.fingers.delete(e.pointerId)) return;
    this.update();
  }

  /** Every finger lifted (the game is paused, a dialog opened…). */
  releaseAll() {
    this.fingers.clear();
    this.update();
  }

  update() {
    let m = 0;
    for (const f of this.fingers.values()) m |= f.mask;
    if (m === this.mask) return;
    this.mask = m;
    for (const b of this.el.querySelectorAll('[data-b]')) b.classList.toggle('on', (m & (1 << BIT[b.dataset.b])) !== 0);
    for (const a of this.arms) a.classList.toggle('on', (m & (1 << BIT[a.dataset.dir])) !== 0);
    this.onMask(m);
  }

  setVisible(on) {
    this.el.classList.toggle('hidden', !on);
    if (!on) this.releaseAll();
  }
}
