package com.zergatul.cheatutils.configs;

import com.zergatul.cheatutils.utils.MathUtils;

public class SoundEspConfig implements ValidatableConfig, ModuleStateProvider {

    public boolean enabled;
    public boolean showMarkers;
    public int markerDuration;
    public boolean skipMusic;
    public boolean showHudList;
    public int hudMaxEntries;
    public boolean writeCsv;
    public boolean markPlayerSpawn;

    public SoundEspConfig() {
        enabled = true;
        showMarkers = true;
        markerDuration = 5;
        skipMusic = true;
        showHudList = true;
        hudMaxEntries = 8;
        writeCsv = true;
        markPlayerSpawn = true;
    }

    @Override
    public void validate() {
        markerDuration = MathUtils.clamp(markerDuration, 1, 60);
        hudMaxEntries = MathUtils.clamp(hudMaxEntries, 0, 24);
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
