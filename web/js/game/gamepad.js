// Gamepads seen by the browser (Bluetooth or USB on a phone): read on every frame while the game is shown.
// The buttons are taken by their place, as on a Nintendo console (the one at the bottom is B, on the right A).

import { BUTTONS } from './keys.js';

const BIT = Object.fromEntries(BUTTONS.map((b) => [b.id, b.bit]));

// "standard" layout of the Gamepad API: index -> console button
const STANDARD = { 0: 'B', 1: 'A', 2: 'Y', 3: 'X', 4: 'L', 5: 'R', 8: 'SELECT', 9: 'START', 12: 'UP', 13: 'DOWN', 14: 'LEFT', 15: 'RIGHT' };

export class Gamepads {
  /**
   * @param onMask      the button mask, whenever it changes
   * @param onFast      the right trigger held (fast-forward), whenever it changes
   * @param onConnected whether a gamepad is there, whenever that changes
   */
  constructor({ onMask, onFast, onConnected }) {
    this.onMask = onMask;
    this.onFast = onFast;
    this.onConnected = onConnected;
    this.mask = 0;
    this.fast = false;
    this.connected = false;
    this.raf = 0;
    this.index = 0;
  }

  /** @param index which gamepad (Réglages > Contrôles), when several are connected */
  start(index = 0) {
    this.index = index;
    if (!navigator.getGamepads || this.raf) return;
    const loop = () => {
      this.raf = requestAnimationFrame(loop);
      this.poll();
    };
    this.raf = requestAnimationFrame(loop);
  }

  stop() {
    if (this.raf) cancelAnimationFrame(this.raf);
    this.raf = 0;
  }

  poll() {
    const pads = [...(navigator.getGamepads() || [])].filter((p) => p && p.connected);
    const pad = pads[this.index] || pads[0];
    if (!!pad !== this.connected) {
      this.connected = !!pad;
      this.onConnected?.(this.connected);
    }
    let m = 0;
    let fast = false;
    if (pad) {
      const pressed = (i) => !!pad.buttons[i] && (pad.buttons[i].pressed || pad.buttons[i].value > 0.5);
      for (const [i, id] of Object.entries(STANDARD)) if (pressed(Number(i))) m |= 1 << BIT[id];
      const [x = 0, y = 0] = pad.axes;
      if (x < -0.5) m |= 1 << BIT.LEFT;
      if (x > 0.5) m |= 1 << BIT.RIGHT;
      if (y < -0.5) m |= 1 << BIT.UP;
      if (y > 0.5) m |= 1 << BIT.DOWN;
      fast = pressed(7);
    }
    if (m !== this.mask) {
      this.mask = m;
      this.onMask(m);
    }
    if (fast !== this.fast) {
      this.fast = fast;
      this.onFast?.(fast);
    }
  }
}
