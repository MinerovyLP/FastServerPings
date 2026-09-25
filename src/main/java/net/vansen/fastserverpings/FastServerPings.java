package net.vansen.fastserverpings;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.vansen.fastserverpings.pipeline.FastPing;

import java.util.concurrent.TimeUnit;

public class FastServerPings implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        try {
            // Optional command that relies on Fabric API. If Fabric API is not present, the command will simply not be registered.
            Class.forName("net.vansen.fastserverpings.command.FastPingCommand")
                    .getMethod("register")
                    .invoke(null);
        } catch (Throwable ignored) {
        }

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            // Order matters: stop anything that can submit NEW work to the event loop
            // group before we start tearing the group down. Previously the group was
            // shut down first while pingWithRetry's retry loop was still alive on the
            // pinger pool, so failed pings kept re-submitting fresh connect() calls to
            // an event loop that was already dying, which is what caused the multi-second
            // (sometimes 30-60s) freeze on quit when the server list had offline entries.
            FastPing.shuttingDown = true;
            FastPing.pinger().shutdownNow();
            try {
                // Give already-queued pinger tasks a moment to notice the flag and bail
                // out instead of racing the event loop shutdown below.
                FastPing.pinger().awaitTermination(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            // Bounded wait as a safety net: never let this hang the client shutdown
            // indefinitely even if something unexpected is still holding the group open.
            FastPing.eventLoopGroup()
                    .shutdownGracefully(0, 2, TimeUnit.SECONDS)
                    .awaitUninterruptibly(3, TimeUnit.SECONDS);
        });
    }
}
