package slt;

import java.util.Arrays;

/**
 * SLT — Soul Link Together. One executable, three roles:
 * <ul>
 *   <li>{@code SLT.exe}: the application of a player (interface, library, game);</li>
 *   <li>{@code SLT.exe --server}: the SLT server alone (accounts, friends, rooms);</li>
 *   <li>{@code SLT.exe --player …}: the game player, started by the application.</li>
 * </ul>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (Arrays.asList(args).contains("--player")) {
            slt.player.PlayerMain.main(args);
        } else if (Arrays.asList(args).contains("--server")) {
            slt.server.SltServer.main(args);
        } else {
            slt.client.ClientApp.main(args);
        }
    }
}
