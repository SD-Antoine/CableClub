package slt;

import java.awt.AWTException;
import java.awt.GraphicsEnvironment;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Everything that touches the Windows desktop: explorer, emulator, browser window, tray. */
public final class Desktop {

    private static TrayIcon trayIcon;

    private Desktop() {
    }

    // ------------------------------------------------------------- explorer

    public static void reveal(String path) throws IOException {
        File f = new File(path);
        if (f.isFile()) {
            new ProcessBuilder("explorer.exe", "/select,", f.getAbsolutePath()).start();
        } else if (f.isDirectory()) {
            new ProcessBuilder("explorer.exe", f.getAbsolutePath()).start();
        } else {
            throw new Http.Fail(404, "Introuvable : " + path);
        }
    }

    public static Process launch(String exe, String file) throws IOException {
        File e = new File(exe);
        if (!e.isFile()) {
            throw new Http.Fail(400, "Émulateur introuvable : " + exe + ". Vérifiez les Paramètres.");
        }
        ProcessBuilder pb = new ProcessBuilder(e.getAbsolutePath(), new File(file).getAbsolutePath());
        pb.directory(e.getAbsoluteFile().getParentFile());
        return pb.start();
    }

    // -------------------------------------------------------------- browser

    private static List<Path> browserCandidates() {
        List<Path> out = new ArrayList<>();
        String[] roots = {System.getenv("ProgramFiles(x86)"), System.getenv("ProgramFiles"), System.getenv("LOCALAPPDATA")};
        for (String root : roots) {
            if (root != null) {
                out.add(Path.of(root, "Microsoft", "Edge", "Application", "msedge.exe"));
            }
        }
        // other Chromium browsers also support the chromeless "--app" window
        String[][] others = {
                {"Google", "Chrome", "Application", "chrome.exe"},
                {"BraveSoftware", "Brave-Browser", "Application", "brave.exe"},
                {"Vivaldi", "Application", "vivaldi.exe"},
                {"Chromium", "Application", "chrome.exe"},
        };
        for (String[] rel : others) {
            for (String root : roots) {
                if (root != null) {
                    out.add(Path.of(root, rel));
                }
            }
        }
        return out;
    }

    /**
     * Opens the UI in a chromeless Edge/Chrome "app" window with its own
     * profile, or in the default browser as a fallback.
     *
     * @return the browser process when we own a dedicated window, else null
     */
    public static Process openAppWindow(String url, Path profileDir) {
        for (Path exe : browserCandidates()) {
            if (Files.isRegularFile(exe)) {
                try {
                    Files.createDirectories(profileDir);
                    return new ProcessBuilder(exe.toString(), "--app=" + url,
                            "--user-data-dir=" + profileDir.toAbsolutePath(),
                            "--no-first-run", "--no-default-browser-check", "--disable-sync",
                            "--disable-features=Translate,msEdgeSidebarV2,msImplicitSignin",
                            "--window-size=1440,920").start();
                } catch (IOException e) {
                    Log.warn("Lancement de " + exe + " impossible : " + e.getMessage());
                }
            }
        }
        browse(url);
        return null;
    }

    public static void browse(String url) {
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception e) {
            Log.warn("Ouverture du navigateur impossible : " + e.getMessage());
        }
        try {
            new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
        } catch (IOException e) {
            Log.warn("Ouverture du navigateur impossible : " + e.getMessage());
        }
    }

    // ----------------------------------------------------------------- tray

    public static void installTray(Runnable onOpen, Runnable onData, Runnable onQuit) {
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            return;
        }
        try {
            PopupMenu menu = new PopupMenu();
            MenuItem open = new MenuItem("Ouvrir Cable Club");
            open.addActionListener(e -> onOpen.run());
            MenuItem data = new MenuItem("Dossier des données");
            data.addActionListener(e -> onData.run());
            MenuItem quit = new MenuItem("Quitter");
            quit.addActionListener(e -> onQuit.run());
            menu.add(open);
            menu.add(data);
            menu.addSeparator();
            menu.add(quit);
            trayIcon = new TrayIcon(Icon.render(64), "Cable Club", menu);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> onOpen.run());
            SystemTray.getSystemTray().add(trayIcon);
        } catch (AWTException e) {
            Log.warn("Icône de zone de notification indisponible : " + e.getMessage());
        }
    }

    public static void notify(String title, String text) {
        if (trayIcon != null) {
            trayIcon.displayMessage(title, text, TrayIcon.MessageType.INFO);
        }
    }

    public static void removeTray() {
        if (trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
            trayIcon = null;
        }
    }
}
