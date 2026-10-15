package com.dabomstew.pkrandom.newgui;

/**
 * The randomizer engine names its Swing window to find its own folder (Utils.getExecutionLocation); Android has no
 * Swing: this empty class stands for it (the engine then works in memory, without temporary folder).
 */
public final class NewRandomizerGUI {

    private NewRandomizerGUI() {
    }
}
