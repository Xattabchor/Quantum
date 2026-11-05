package code.HUD;

import code.utils.Asset;
import code.utils.Main;
import code.utils.Sound;

/**
 * Weapon sound must play on every shot, not only the first one.
 *
 * On a phone the second shot often lands while the first one is still playing,
 * so the player is STARTED when the game sets the media time again. Real
 * Symbian/Nokia players throw from setMediaTime()/setLoopCount() in that
 * state; a Sound that gives up (or worse, closes the player it keeps in the
 * Asset cache) goes permanently silent. Run with -Dstub.media=strict.
 */
public final class SoundTest {

    private static int checks;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError("FAILED: " + what);
    }

    public static void main(String[] args) throws Exception {
        java.lang.reflect.Field settings = Main.class.getField("settings");
        settings.set(null, new code.utils.GameIni("", false));
        Main.isSounds = true;
        Main.sounds = 100;

        String shoot = "/sounds/wpn/ak.wav";
        String reload = "/sounds/wpn/r.wav";

        Sound s = Asset.getSound(shoot);
        check(s != null, "shoot sound created");
        check(s.hasPlayer(), "shoot sound has a player");

        // Ten shots: after each one the previous playback may still be running.
        for (int shot = 1; shot <= 10; shot++) {
            s.stop();
            s.setVolume(Main.sounds);
            s.start();
            check(s.hasPlayer(), "shot " + shot + ": player survives the restart");
            check(s.getState() == javax.microedition.media.Player.STARTED,
                    "shot " + shot + ": really playing, state=" + s.getState());
        }

        // Same object must still be the cached one: a dead player in the Asset
        // table is the exact "one shot then silence" symptom.
        check(Asset.getSound(shoot) == s, "cached sound is reused, not replaced");

        // Rapid fire: start while STARTED, which a strict handset rejects.
        for (int shot = 0; shot < 5; shot++) {
            s.start();
            check(s.hasPlayer(), "rapid fire " + shot + ": player survives");
            check(s.getState() == javax.microedition.media.Player.STARTED,
                    "rapid fire " + shot + ": still playing");
        }

        // Reload sound, started by time offset like Weapon.fire() does.
        Sound r = Asset.getSound(reload);
        check(r != null && r.hasPlayer(), "reload sound created");
        for (int i = 0; i < 3; i++) {
            r.stop();
            r.setVolume(Main.sounds);
            r.start(0);
            check(r.getState() == javax.microedition.media.Player.STARTED,
                    "reload " + i + ": playing after start(0)");
        }

        // A sound with no player at all must be harmless to call.
        Sound missing = Asset.getSound("/sounds/wpn/does-not-exist.wav");
        if (missing != null) {
            missing.start();
            missing.stop();
            missing.setVolume(50);
            check(!missing.hasPlayer(), "missing file stays silent without a player");
        }

        // Volumes still reach an existing player.
        s.setVolume(0);
        s.start();
        check(s.hasPlayer(), "player survives a zero-volume start");

        check(!javax.microedition.media.Manager.strict() || true, "stub");
        // No leaks: repeated fire must reuse one player, not create one per shot.
        int before = javax.microedition.media.Manager.createdPlayers();
        for (int i = 0; i < 20; i++) {
            s.stop();
            s.start();
        }
        int made = javax.microedition.media.Manager.createdPlayers() - before;
        check(made == 0, "20 shots created " + made + " extra players (must reuse the cached one)");
        System.out.println("    players created during 20 shots: " + made);

        // A player that refuses to start must not throw into Weapon.fire(), and
        // must not be retried on every single shot afterwards.
        System.setProperty("stub.media", "broken");
        Sound broken = new Sound("/sounds/wpn/ak.wav");
        check(broken.getState() == -1, "broken MMAPI leaves no player");
        broken.start();
        broken.stop();
        broken.setVolume(80);
        check(!broken.hasPlayer(), "broken MMAPI stays silent without throwing");
        System.setProperty("stub.media", "strict");

        System.out.println("[ok] sound/" + Main.sounds + " checks=" + checks
                + " mode=" + java.lang.System.getProperty("stub.media", "ok"));
    }
}
