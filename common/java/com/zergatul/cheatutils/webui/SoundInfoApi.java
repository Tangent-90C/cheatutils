package com.zergatul.cheatutils.webui;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/** All sound ids the client knows about, so the Sound ESP UI can offer per-sound settings. */
public class SoundInfoApi extends ApiBase {

    @Override
    public String getRoute() {
        return "sound-info";
    }

    @Override
    public String get() {
        List<String> ids = new ArrayList<>();
        try {
            for (ResourceLocation location : Minecraft.getInstance().getSoundManager().getAvailableSounds()) {
                ids.add(location.toString());
            }
        } catch (Throwable t) {
            // sound manager not loaded yet
        }
        ids.sort(String::compareTo);
        return gson.toJson(ids);
    }
}
