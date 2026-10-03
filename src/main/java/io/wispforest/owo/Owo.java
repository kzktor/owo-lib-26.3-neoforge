package io.wispforest.owo;

import io.wispforest.owo.config.ConfigSynchronizer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.jetbrains.annotations.ApiStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.wispforest.owo.ops.TextOps.withColor;

/**
 * NeoForge entrypoint of the owo-lib config slice.
 *
 * <p>This is a reduced port: it carries only what the config subsystem needs
 * (the {@code io.wispforest.owo.config} package and its supporting utilities).
 * The owo-ui / braid / itemgroup / networking-UI modules are not ported, so the
 * in-game config screen is not available — config values are read from and written
 * to {@code config/<name>.json5} as usual.
 */
@Mod(Owo.MOD_ID)
public class Owo {

    public static final String MOD_ID = "owo";
    /**
     * Whether oωo debug is enabled, this defaults to {@code true} in a development environment.
     * To override that behavior, add the {@code -Dowo.debug=false} java argument
     */
    public static final boolean DEBUG;
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static MinecraftServer SERVER;

    public static final Component PREFIX = Component.empty().withStyle(ChatFormatting.GRAY)
        .append(withColor("o", 0x3955e5))
        .append(withColor("ω", 0x13a6f0))
        .append(withColor("o", 0x3955e5))
        .append(Component.literal(" > ").withStyle(ChatFormatting.GRAY));

    static {
        boolean debug = !FMLEnvironment.isProduction();
        if (System.getProperty("owo.debug") != null) debug = Boolean.getBoolean("owo.debug");
        if (Boolean.getBoolean("owo.forceDisableDebug")) {
            LOGGER.warn("Deprecated system property 'owo.forceDisableDebug=true' was used - use 'owo.debug=false' instead");
            debug = false;
        }

        DEBUG = debug;
    }

    public Owo(IEventBus modBus) {
        ConfigSynchronizer.init(modBus);

        NeoForge.EVENT_BUS.addListener((final ServerStartingEvent event) -> SERVER = event.getServer());
        NeoForge.EVENT_BUS.addListener((final ServerStoppedEvent event) -> SERVER = null);
    }

    @ApiStatus.Internal
    public static void debugWarn(Logger logger, String message) {
        if (!DEBUG) return;
        logger.warn(message);
    }

    @ApiStatus.Internal
    public static void debugWarn(Logger logger, String message, Object... params) {
        if (!DEBUG) return;
        logger.warn(message, params);
    }

    /**
     * @return The currently active minecraft server instance. If running
     * on a physical client, this will return the integrated server while in
     * a local singleplayer world and {@code null} otherwise
     */
    public static MinecraftServer currentServer() {
        return SERVER;
    }

    @ApiStatus.Internal
    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
