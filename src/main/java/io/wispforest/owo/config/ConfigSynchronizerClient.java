package io.wispforest.owo.config;

import io.wispforest.owo.Owo;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/**
 * Client-only half of {@link ConfigSynchronizer}.
 *
 * <p>Split into its own class so that the client-only event type is never referenced
 * by the common class, and NeoForge only loads this on a physical client.
 */
@EventBusSubscriber(modid = Owo.MOD_ID, value = Dist.CLIENT)
public final class ConfigSynchronizerClient {

    private ConfigSynchronizerClient() {}

    @SubscribeEvent
    public static void onLoggingOut(final ClientPlayerNetworkEvent.LoggingOut event) {
        ConfigSynchronizer.reattachAll();
    }
}
