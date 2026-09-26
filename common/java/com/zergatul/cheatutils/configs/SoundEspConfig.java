package com.zergatul.cheatutils.configs;

import com.zergatul.cheatutils.utils.MathUtils;

public class SoundEspConfig implements ValidatableConfig, ModuleStateProvider {

    public boolean enabled;
    public boolean showMarkers;
    public boolean showEntitySoundPackets;
    public boolean drawTracers;
    public int markerDuration;
    public boolean skipMusic;
    public boolean writeCsv;
    public boolean markPlayerSpawn;

    public SoundEspConfig() {
        enabled = true;
        showMarkers = true;
        showEntitySoundPackets = false;
        drawTracers = true;
        markerDuration = 5;
        skipMusic = true;
        writeCsv = true;
        markPlayerSpawn = true;
    }

    @Override
    public void validate() {
        markerDuration = MathUtils.clamp(markerDuration, 1, 60);
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
