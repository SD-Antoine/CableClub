// The phone version: the same interface, laid out for a small touch screen. The Android app shows it in a web
// view and lends it « CableClubAndroid » for what only the phone can do (file picker, screen kept on, vibrations).

export const native = window.CableClubAndroid || null;

/** The interface runs in the Android app. */
export const mobile = !!native;

/** « ce PC » or « ce téléphone », for the sentences that name the device. */
export const device = mobile ? 'ce téléphone' : 'ce PC';

/** The game is shown: screen kept on, system bars hidden. */
export function setPlaying(on) {
  try { native?.setPlaying(!!on); } catch { /* older app */ }
}

/** A short vibration under the finger (touch buttons), when the player wants it. */
export function haptic() {
  try { native?.haptic(); } catch { /* older app */ }
}

/** Android's file picker for ROM files: the app copies them and adds them (event « rom-import »). */
export function pickRoms() {
  native?.pickRoms();
}

// The back button of the phone: the interface closes what is open on top (a dialog, a panel) before the app
// goes back a page. Views register what they can close; the last one registered goes first.
const backs = [];

export function onBack(fn) {
  backs.push(fn);
  return () => { const i = backs.lastIndexOf(fn); if (i >= 0) backs.splice(i, 1); };
}

window.ccBack = () => {
  const modals = document.querySelectorAll('#modals .modal-back');
  if (modals.length) {
    const close = modals[modals.length - 1].querySelector('.modal-head .btn');
    if (close) close.click();
    return true;
  }
  for (let i = backs.length - 1; i >= 0; i--) {
    try { if (backs[i]()) return true; } catch (e) { console.error(e); }
  }
  return false;
};

// The app leaves the screen or comes back (the system's own events may come late in a web view).
const hiddenListeners = new Set();
export function onHidden(fn) {
  hiddenListeners.add(fn);
  return () => hiddenListeners.delete(fn);
}
window.ccHidden = (hidden) => { for (const fn of hiddenListeners) { try { fn(!!hidden); } catch (e) { console.error(e); } } };
