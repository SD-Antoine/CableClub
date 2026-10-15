package slt.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * DS cartridge saves between the PC and the phone. DeSmuME (the PC) keeps rom.dsv: the raw save followed by a
 * footer of its own (122 bytes); melonDS (the phone) and the server keep the raw save, so that a player can go on
 * with the same game on another device.
 */
final class DsSaves {

    private static final byte[] SNIP = "|<--Snip above here to create a raw sav by excluding this DeSmuME savedata footer:"
            .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] COOKIE = "|-DESMUME SAVE-|".getBytes(StandardCharsets.US_ASCII);
    private static final int FOOTER = SNIP.length + 24 + COOKIE.length;

    private DsSaves() {
    }

    /** The raw save of a DeSmuME file (the file itself when it has no DeSmuME footer). */
    static byte[] raw(byte[] dsv) {
        if (!isDsv(dsv)) {
            return dsv;
        }
        return Arrays.copyOf(dsv, dsv.length - FOOTER);
    }

    /**
     * A raw save as DeSmuME reads it.
     *
     * @param previous the DeSmuME file it replaces (its kind of memory is kept), or null
     */
    static byte[] dsv(byte[] raw, byte[] previous) {
        // what DeSmuME writes for the 512 KB flash memory of the Pokémon games: type 6, 3-byte addresses
        int type = 6;
        int addressSize = 3;
        if (previous != null && isDsv(previous)) {
            ByteBuffer old = ByteBuffer.wrap(previous, previous.length - COOKIE.length - 24, 24).order(ByteOrder.LITTLE_ENDIAN);
            old.getInt();
            old.getInt();
            type = old.getInt();
            addressSize = old.getInt();
        }
        ByteBuffer out = ByteBuffer.allocate(raw.length + FOOTER).order(ByteOrder.LITTLE_ENDIAN);
        out.put(raw);
        out.put(SNIP);
        out.putInt(raw.length).putInt(raw.length).putInt(type).putInt(addressSize).putInt(raw.length).putInt(0);
        out.put(COOKIE);
        return out.array();
    }

    static boolean isDsv(byte[] data) {
        if (data.length < FOOTER) {
            return false;
        }
        for (int i = 0; i < COOKIE.length; i++) {
            if (data[data.length - COOKIE.length + i] != COOKIE[i]) {
                return false;
            }
        }
        return true;
    }
}
