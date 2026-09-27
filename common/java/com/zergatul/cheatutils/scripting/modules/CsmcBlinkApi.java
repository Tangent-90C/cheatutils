package com.zergatul.cheatutils.scripting.modules;

import com.zergatul.cheatutils.controllers.CsmcBlinkController;
import com.zergatul.scripting.MethodDescription;
import com.zergatul.cheatutils.scripting.ApiType;
import com.zergatul.cheatutils.scripting.ApiVisibility;

@SuppressWarnings("unused")
public class CsmcBlinkApi {

    @MethodDescription("""
            Checks if CSMC Blink is activated
            """)
    public boolean isEnabled() {
        return CsmcBlinkController.instance.isEnabled();
    }

    @MethodDescription("""
            Checks if movement is being buffered right now. False while the buffer is released and the
            module is waiting to be armed again.
            """)
    public boolean isArmed() {
        return CsmcBlinkController.instance.isArmed();
    }

    @MethodDescription("""
            Starts buffering outgoing movement. The server keeps you where you were, so a shot fired
            while buffering comes from that position until the buffer is released.
            """)
    @ApiVisibility(ApiType.ACTION)
    public void enable() {
        CsmcBlinkController.instance.enable();
    }

    @MethodDescription("""
            Stops buffering and releases everything held, so the server catches up with where you moved.
            """)
    @ApiVisibility(ApiType.ACTION)
    public void disable() {
        CsmcBlinkController.instance.disable();
    }

    @MethodDescription("""
            Releases everything held without turning the module off, so the next call to enable starts
            from the position you are standing in now.
            """)
    @ApiVisibility(ApiType.ACTION)
    public void flush() {
        CsmcBlinkController.instance.flush();
    }

    @MethodDescription("""
            Replays the buffer exactly as it was recorded, without reporting the position you hold
            now. Use after a respawn with Rewind On Respawn on: the buffer then describes where you
            died, and reporting it is what pulls you back to that spot.
            """)
    @ApiVisibility(ApiType.ACTION)
    public void recall() {
        CsmcBlinkController.instance.recall();
    }

    @MethodDescription("""
            How many movement packets are buffered right now.
            """)
    public int getPackets() {
        return CsmcBlinkController.instance.getPackets();
    }

    @MethodDescription("""
            How far you moved from the position the buffer started at.
            """)
    public double getDistance() {
        return CsmcBlinkController.instance.getDistance();
    }

    @MethodDescription("""
            How far the server-side position is from where you actually stand. This is the offset the
            debug box draws, and it stays meaningful after a flush that re-armed the buffer.
            """)
    public double getServerDistance() {
        return CsmcBlinkController.instance.getServerDistance();
    }
}
