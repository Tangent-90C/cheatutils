package com.zergatul.cheatutils.modules.hacks;

import com.zergatul.cheatutils.ModMain;
import com.zergatul.cheatutils.common.Events;
import com.zergatul.cheatutils.configs.ConfigStore;
import com.zergatul.cheatutils.configs.FakePingConfig;
import com.zergatul.cheatutils.modules.Module;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The latency shown in the Tab player list is measured server-side from keep-alive round trips.
 * We intercept keep-alive handling at its source (MixinClientCommonPacketListenerImpl) and answer
 * later, so the server measures a higher latency and reports it to every client.
 * Our re-sends go through Connection.send, which never passes through handleKeepAlive again,
 * so they cannot be re-intercepted by this module.
 */
public class FakePing implements Module {

    public static final FakePing instance = new FakePing();

    private final List<PendingKeepAlive> pending = new ArrayList<>();

    private FakePing() {
        Events.ClientTickEnd.add(this::onClientTickEnd);
    }

    private static FakePingConfig config() {
        return ConfigStore.instance.getConfig().fakePingConfig;
    }

    public boolean onKeepAlive(Connection connection, long id) {
        FakePingConfig config = config();
        if (!config.enabled || config.delay <= 0) {
            return false;
        }

        synchronized (pending) {
            pending.add(new PendingKeepAlive(connection, id, System.nanoTime() + config.delay * 1_000_000L));
        }
        if (config.log) {
            ModMain.LOGGER.info("FakePing: captured keep-alive response, delaying it by {} ms", config.delay);
        }
        return true;
    }

    private void onClientTickEnd() {
        FakePingConfig config = config();
        List<PendingKeepAlive> due = null;

        synchronized (pending) {
            if (!pending.isEmpty()) {
                long now = System.nanoTime();
                due = new ArrayList<>();
                Iterator<PendingKeepAlive> iterator = pending.iterator();
                while (iterator.hasNext()) {
                    PendingKeepAlive p = iterator.next();
                    if (!config.enabled || p.dueNanos() <= now) {
                        due.add(p); // module disabled: answer immediately
                        iterator.remove();
                    }
                }
            }
        }

        if (due != null && !due.isEmpty()) {
            int sent = 0;
            for (PendingKeepAlive p : due) {
                if (p.connection().isConnected()) {
                    p.connection().send(new ServerboundKeepAlivePacket(p.id()));
                    sent++;
                }
            }
            if (config.log) {
                ModMain.LOGGER.info("FakePing: sent {} delayed keep-alive response(s)", sent);
            }
        }
    }

    private record PendingKeepAlive(Connection connection, long id, long dueNanos) {}
}
