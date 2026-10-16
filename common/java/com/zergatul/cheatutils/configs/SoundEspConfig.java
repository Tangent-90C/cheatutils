package com.zergatul.cheatutils.configs;

import com.zergatul.cheatutils.utils.MathUtils;

import java.awt.Color;
import java.util.List;

public class SoundEspConfig implements ValidatableConfig, ModuleStateProvider {

    /**
     * Sounds marked on a fresh config - ordinary entries the user can edit or remove.
     * <p>
     * The three weapon families are all listed here: a longer pattern wins over a
     * shorter one, so "weapons.fx." decides for bullet impacts while "weapons.*"
     * decides for the gunfire itself. Everything the module can draw is a row below -
     * nothing is applied from a rule the list does not show.
     */
    public static final java.util.List<SoundEntry> DEFAULT_SOUNDS = java.util.List.of(
            new SoundEntry("minecraft:counterstrike.weapons.*", true, new Color(255, 153, 26),
                    SoundEntry.SHAPE_BOX, 1.6),
            new SoundEntry("minecraft:counterstrike.weapons.fx.*", false, new Color(51, 230, 230),
                    SoundEntry.SHAPE_RING, 0.0),
            new SoundEntry("minecraft:counterstrike.weapons.events.*", false, new Color(153, 153, 153),
                    SoundEntry.SHAPE_BOX, 0.0),
            new SoundEntry("minecraft:counterstrike.player.footsteps.*", true, new Color(255, 64, 64),
                    SoundEntry.SHAPE_BOX, 0.0),
            new SoundEntry("minecraft:counterstrike.player.damage_*", true, new Color(255, 64, 64),
                    SoundEntry.SHAPE_BOX, 0.0),
            new SoundEntry("minecraft:counterstrike.player.headshot_*", true, new Color(255, 64, 64),
                    SoundEntry.SHAPE_BOX, 0.0),
            new SoundEntry("minecraft:block.stone.step", true, new Color(153, 153, 153),
                    SoundEntry.SHAPE_BOX, 0.0),
            new SoundEntry("minecraft:block.wood.step", true, new Color(153, 153, 153),
                    SoundEntry.SHAPE_BOX, 0.0),
            new SoundEntry("minecraft:block.grass.step", true, new Color(153, 153, 153),
                    SoundEntry.SHAPE_BOX, 0.0));

    public boolean enabled;
    public double markerDuration;
    /**
     * Non-entity sounds closer than this to the player are treated as the player's own
     * (own gun, UI) and never marked. Entity-bound sounds are matched by their entity
     * instead and are not affected.
     */
    public double ownSoundDistance;

    /** Write the per-sound and 5-second diagnostic lines into the game log. */
    public boolean debug;
    public boolean writeCsv;
    /** Sounds to mark. An id ending in "*" matches every sound with that prefix. */
    public List<SoundEntry> sounds;

    // render switches used to be four globals; they live on each entry now. These remain
    // only so validate() can copy an old config's globals onto its entries once, then
    // clears them - a fresh config never sets them, so Gson drops them on the next save.
    public Boolean showMarkers;
    public Boolean drawTracers;
    public Boolean throughWalls;
    public Boolean onlyNotVisible;

    public SoundEspConfig() {
        enabled = true;
        markerDuration = 5;
        ownSoundDistance = 2.0;
        debug = false;
        writeCsv = false;
        // left null on purpose: a config json without this field is a fresh one, and
        // validate() then seeds the defaults. An empty list means the user removed them
        // all, which is respected.
    }

    @Override
    public void validate() {
        markerDuration = MathUtils.clamp(markerDuration, 0.05, 60);
        ownSoundDistance = MathUtils.clamp(ownSoundDistance, 0.0, 64.0);
        if (sounds == null) {
            sounds = new java.util.ArrayList<>();
            for (SoundEntry entry : DEFAULT_SOUNDS) {
                // copy: these entries are shared statics, and the backfills below edit them
                sounds.add(new SoundEntry(entry));
            }
        }
        sounds.removeIf(entry -> entry == null || entry.id == null || entry.id.isEmpty());
        // a hand-edited entry may carry a nonsense render setting - fall back to the
        // defaults, but never overwrite a value that is merely absent (null)
        for (SoundEntry entry : sounds) {
            if (entry.shape != null && entry.shape != SoundEntry.SHAPE_BOX && entry.shape != SoundEntry.SHAPE_RING) {
                entry.shape = SoundEntry.SHAPE_BOX;
            }
            if (entry.offsetY != null && !Double.isFinite(entry.offsetY)) {
                entry.offsetY = 0.0;
            }
        }
        applyLegacyRenderSwitches();
        migrateLegacyGunfireRow();
    }

    /**
     * A config saved while the render switches were global carries them at the top level
     * and its entries without their own. Copy the globals onto every entry - on that
     * config that is exactly what they did - and clear them, so this runs one time.
     */
    private void applyLegacyRenderSwitches() {
        if (showMarkers == null && drawTracers == null && throughWalls == null && onlyNotVisible == null) {
            return;
        }
        for (SoundEntry entry : sounds) {
            if (showMarkers != null) {
                entry.drawMarker = showMarkers;
            }
            if (drawTracers != null) {
                entry.drawTracer = drawTracers;
            }
            if (throughWalls != null) {
                entry.throughWalls = throughWalls;
            }
            if (onlyNotVisible != null) {
                entry.onlyNotVisible = onlyNotVisible;
            }
        }
        showMarkers = null;
        drawTracers = null;
        throughWalls = null;
        onlyNotVisible = null;
    }

    /**
     * Configs written by the first iteration of this list carried the gunfire pattern
     * but not the two weapon families it must share the list with, so those families
     * were applied from a rule the list never showed. Add them, using the shipped
     * settings, so the list shows every rule that is in effect.
     */
    /**
     * One-off migration for configs written before per-entry render settings existed.
     * <p>
     * A row whose shape/offsetY were never saved is a legacy row: it gets the shipped
     * render settings (gunfire draws the box back around the shooter), and the two
     * weapon families that must share the list with it are added. Once the user saves,
     * the fields exist and this never runs again - their edits stick, and a row they
     * remove stays removed.
     */
    private void migrateLegacyGunfireRow() {
        SoundEntry legacy = sounds.stream()
                .filter(e -> "minecraft:counterstrike.weapons.*".equals(e.id) && e.shape == null)
                .findFirst().orElse(null);
        if (legacy == null) {
            return;
        }
        legacy.shape = SoundEntry.SHAPE_BOX;
        legacy.offsetY = 1.6;
        for (SoundEntry defaults : DEFAULT_SOUNDS) {
            if (defaults.id.equals("minecraft:counterstrike.weapons.fx.*") ||
                    defaults.id.equals("minecraft:counterstrike.weapons.events.*")) {
                boolean present = sounds.stream().anyMatch(e -> defaults.id.equals(e.id));
                if (!present) {
                    sounds.add(new SoundEntry(defaults));
                }
            }
        }
    }

    /**
     * Entry that decides this sound id: exact match first, then the longest matching
     * "prefix*" entry. null - the sound is not listed, so it is not marked.
     */
    public SoundEntry findSound(String id) {
        if (sounds == null || id == null) {
            return null;
        }
        SoundEntry wildcard = null;
        int wildcardLength = -1;
        for (SoundEntry entry : sounds) {
            if (entry == null || entry.id == null) {
                continue;
            }
            if (entry.id.equals(id)) {
                return entry;
            }
            if (entry.id.endsWith("*")) {
                String prefix = entry.id.substring(0, entry.id.length() - 1);
                if (id.startsWith(prefix) && prefix.length() > wildcardLength) {
                    wildcard = entry;
                    wildcardLength = prefix.length();
                }
            }
        }
        return wildcard;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
