// The goals of a mode (point 3): the « objectives » of its file, or, for a mode that declares none, the road of
// every Pokémon game (the 8 badges, then the Champion of the League) when beating the Champion ends it. Shown with
// each player's progress, from what the server counts (badges, captures, status); they decide nothing.

import { specOf } from './modes.js';

/** The goals shown for a mode (an id, a room or a mode file). */
export function objectivesOf(mode) {
  const spec = specOf(mode);
  if (spec.objectives?.length) return spec.objectives;
  return (spec.win || []).includes('champion') ? [{ type: 'badges', count: 8 }, { type: 'champion' }] : [];
}

/** Where a player is on a goal: { value, target, done, label }. */
export function progressOf(goal, member) {
  const s = member?.stats || {};
  if (goal.type === 'champion') {
    const done = member?.status === 'champion';
    return { value: done ? 1 : 0, target: 1, done, label: done ? 'Maître de la Ligue vaincu' : 'Battre le Maître de la Ligue' };
  }
  const value = Number(goal.type === 'badges' ? s.badges : s.captures) || 0;
  const target = Number(goal.count) || 1;
  return {
    value: Math.min(value, target), target, done: value >= target,
    label: goal.type === 'badges' ? `${target} badge${target > 1 ? 's' : ''}` : `${target} capture${target > 1 ? 's' : ''}`,
  };
}
