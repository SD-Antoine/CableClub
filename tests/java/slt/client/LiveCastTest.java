package slt.client;

import java.nio.charset.StandardCharsets;

/**
 * What the application sends of its live images (point 4 of the follow-up of the audit of 2026-11-10): every image
 * by default and with a server that does not say whether it is watched; one every {@link LiveCast#IDLE_MS} while
 * nobody watches; every image again as soon as someone does.
 */
public final class LiveCastTest {

    private static int failed;

    public static void main(String[] args) {
        // an older server: {"ok":true}, or nothing readable
        LiveCast old = new LiveCast();
        check(old.shouldSend(0) && old.shouldSend(250), "au départ : chaque image");
        old.answered(bytes("{\"ok\":true}"));
        check(old.shouldSend(500) && old.shouldSend(750), "serveur qui ne dit rien : chaque image, comme avant");
        old.answered(new byte[0]);
        old.answered(bytes("<html>"));
        check(old.shouldSend(1000), "réponse illisible : chaque image");

        LiveCast c = new LiveCast();
        check(c.shouldSend(0), "première image");
        c.answered(bytes("{\"ok\":true,\"watched\":false}"));
        check(!c.shouldSend(250) && !c.shouldSend(1999), "personne ne regarde : images sautées");
        check(c.shouldSend(2000), "une image toutes les " + LiveCast.IDLE_MS + " ms");
        check(!c.shouldSend(2250), "puis de nouveau sautées");
        c.answered(null); // not sent: nothing learnt
        check(!c.shouldSend(2500), "un envoi raté ne change rien");
        c.answered(bytes("{\"ok\":true,\"watched\":true}"));
        check(c.shouldSend(2750) && c.shouldSend(3000), "quelqu'un regarde : chaque image");

        System.out.println(failed == 0 ? "ok     envoi des écrans en direct" : "ÉCHEC  envoi des écrans en direct : " + failed + " cas");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            failed++;
            System.out.println("  échec : " + what);
        }
    }
}
