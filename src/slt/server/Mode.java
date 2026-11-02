package slt.server;

import slt.Json;

import java.util.List;
import java.util.Map;

/**
 * A game mode seen as an ordered list of rules applied to each event (point 1c of the evolution plan). At this first
 * step the only rule delegates to {@link Room}'s existing per-type handlers, so behaviour is unchanged; the
 * mode-specific logic (the {@code individual()} / {@code sololocke()} / {@code shinylocke()} branches) moves into real
 * rules in the next sub-steps, until no such branch is left in the event handlers. The server stays the referee and
 * the directives stay the only channel to the adapters.
 */
final class Mode {

    /** A piece of a mode's behaviour: it reacts to one event through the context. */
    interface Rule {
        void onEvent(Ctx ctx, Map<String, Object> ev);
    }

    /** What a rule may act on: the room, the player whose event it is, and where logs, notices and state changes go. */
    static final class Ctx {
        final Room room;
        final Room.Member member;
        final Room.Out out;

        Ctx(Room room, Room.Member member, Room.Out out) {
            this.room = room;
            this.member = member;
            this.out = out;
        }
    }

    private final String id;
    private final List<Rule> rules;

    Mode(String id, List<Rule> rules) {
        this.id = id;
        this.rules = rules;
    }

    String id() {
        return id;
    }

    /** Runs every rule of the mode on the event, in order. */
    void handle(Ctx ctx, Map<String, Object> ev) {
        for (Rule rule : rules) {
            rule.onEvent(ctx, ev);
        }
    }

    /** The mode of a room. For now every mode is a single rule that delegates to {@link Room}'s current handlers. */
    static Mode forRoom(Room room) {
        return new Mode(room.mode, List.of(new LegacyDispatch()));
    }

    /** Transitional rule: runs Room's existing per-type handlers unchanged. Shrinks as logic moves into real rules. */
    private static final class LegacyDispatch implements Rule {
        @Override
        public void onEvent(Ctx ctx, Map<String, Object> ev) {
            ctx.room.dispatch(ctx.member, ev, ctx.out);
        }
    }
}
