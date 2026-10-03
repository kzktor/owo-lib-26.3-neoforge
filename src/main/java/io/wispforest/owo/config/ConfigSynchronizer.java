package io.wispforest.owo.config;

import com.google.common.collect.HashMultimap;
import com.mojang.datafixers.util.Pair;
import io.netty.buffer.Unpooled;
import io.wispforest.endec.Endec;
import io.wispforest.endec.util.EndecBuffer;
import io.wispforest.endec.impl.StructEndecBuilder;
import io.wispforest.owo.Owo;
import io.wispforest.owo.mixin.ServerCommonPacketListenerImplAccessor;
import io.wispforest.owo.ops.TextOps;
import io.wispforest.owo.serialization.CodecUtils;
import io.wispforest.owo.serialization.endec.MinecraftEndecs;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;

/**
 * Synchronizes config options marked with {@link Option.SyncMode} between server and client.
 *
 * <p>Ported from Fabric API networking to NeoForge's {@code PayloadRegistrar} / {@code PacketDistributor}.
 * The wire format and the sync protocol are unchanged.
 */
public class ConfigSynchronizer {

    public static final Identifier CONFIG_SYNC_CHANNEL = Owo.id("config_sync");

    private static final Map<Connection, Map<String, Map<Option.Key, Object>>> CLIENT_OPTION_STORAGE = new WeakHashMap<>();

    private static final Map<String, ConfigWrapper<?>> KNOWN_CONFIGS = new HashMap<>();
    private static final MutableComponent PREFIX = TextOps.concat(Owo.PREFIX, Component.nullToEmpty("§cunrecoverable config mismatch\n\n"));

    static void register(ConfigWrapper<?> config) {
        KNOWN_CONFIGS.put(config.name(), config);
    }

    public static void init(IEventBus modBus) {
        modBus.addListener(ConfigSynchronizer::registerPayloads);

        NeoForge.EVENT_BUS.addListener((final PlayerEvent.PlayerLoggedInEvent event) -> {
            if (!(event.getEntity() instanceof ServerPlayer player)) return;
            Owo.LOGGER.info("Sending server config values to client");
            PacketDistributor.sendToPlayer(player, toPacket(Option.SyncMode.OVERRIDE_CLIENT));
        });

        // Client-only listeners live in ConfigSynchronizerClient, which NeoForge loads
        // only on the physical client.
    }

    static void reattachAll() {
        KNOWN_CONFIGS.forEach((name, config) -> config.forEachOption(Option::reattach));
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playBidirectional(
                ConfigSyncPacket.ID,
                CodecUtils.toPacketCodec(ConfigSyncPacket.ENDEC),
                ConfigSynchronizer::applyServer,
                ConfigSynchronizer::applyClient
        );
    }

    /**
     * Retrieve the options which the given player's client
     * sent to the server during config synchronization
     *
     * @param player     The player for which to retrieve the client values
     * @param configName The name of the config for which to retrieve values
     * @return The player's client's values of the given config options,
     * or {@code null} if no config with the given name was synced
     */
    public static @Nullable Map<Option.Key, ?> getClientOptions(ServerPlayer player, String configName) {
        var storage = CLIENT_OPTION_STORAGE.get(((ServerCommonPacketListenerImplAccessor) player.connection).owo$getConnection());
        if (storage == null) return null;

        return storage.get(configName);
    }

    /**
     * Safer, more clear version of {@link #getClientOptions(ServerPlayer, String)} to
     * be used when the actual config wrapper is available
     *
     * @see #getClientOptions(ServerPlayer, String)
     */
    public static @Nullable Map<Option.Key, ?> getClientOptions(ServerPlayer player, ConfigWrapper<?> config) {
        return getClientOptions(player, config.name());
    }

    private static ConfigSyncPacket toPacket(Option.SyncMode targetMode) {
        Map<String, ConfigEntry> configs = new HashMap<>();

        KNOWN_CONFIGS.forEach((configName, config) -> {
            var entry = new ConfigEntry(new HashMap<>());

            config.allOptions().forEach((key, option) -> {
                if (option.syncMode().ordinal() < targetMode.ordinal()) return;

                FriendlyByteBuf optionBuf = new FriendlyByteBuf(Unpooled.buffer());
                option.write(optionBuf);

                entry.options().put(key.asString(), optionBuf);
            });

            configs.put(configName, entry);
        });

        return new ConfigSyncPacket(configs);
    }

    private static void read(ConfigSyncPacket packet, BiConsumer<Option<?>, FriendlyByteBuf> optionConsumer) {
        for (var configEntry : packet.configs().entrySet()) {
            var configName = configEntry.getKey();
            var config = KNOWN_CONFIGS.get(configName);
            if (config == null) {
                Owo.LOGGER.error("Received overrides for unknown config '{}', skipping", configName);
                continue;
            }

            for (var optionEntry : configEntry.getValue().options().entrySet()) {
                var optionKey = new Option.Key(optionEntry.getKey());
                var option = config.optionForKey(optionKey);
                if (option == null) {
                    Owo.LOGGER.error("Received override for unknown option '{}' in config '{}', skipping", optionKey, configName);
                    continue;
                }

                optionConsumer.accept(option, optionEntry.getValue());
            }
        }
    }

    private static void applyClient(ConfigSyncPacket payload, IPayloadContext context) {
        Owo.LOGGER.info("Applying server overrides");
        var mismatchedOptions = new HashMap<Option<?>, Object>();

        var client = Minecraft.getInstance();
        if (!(client.hasSingleplayerServer() && client.getSingleplayerServer().isSingleplayer())) {
            read(payload, (option, packetByteBuf) -> {
                var mismatchedValue = option.read(packetByteBuf);
                if (mismatchedValue != null) mismatchedOptions.put(option, mismatchedValue);
            });

            if (!mismatchedOptions.isEmpty()) {
                Owo.LOGGER.error("Aborting connection, non-syncable config values were mismatched");
                mismatchedOptions.forEach((option, serverValue) -> {
                    Owo.LOGGER.error("- Option {} in config '{}' has value '{}' but server requires '{}'",
                            option.key().asString(), option.configName(), option.value(), serverValue);
                });

                var errorMessage = Component.empty();
                var optionsByConfig = HashMultimap.<String, Pair<Option<?>, Object>>create();

                mismatchedOptions.forEach((option, serverValue) -> optionsByConfig.put(option.configName(), Pair.of(option, serverValue)));
                for (var configName : optionsByConfig.keys()) {
                    errorMessage.append(TextOps.withFormatting("in config ", ChatFormatting.GRAY)).append(configName).append("\n");
                    for (var option : optionsByConfig.get(configName)) {
                        errorMessage.append(Component.translatable(option.getFirst().translationKey()).withStyle(ChatFormatting.YELLOW)).append(" -> ");
                        errorMessage.append(option.getFirst().value().toString()).append(TextOps.withFormatting(" (client)", ChatFormatting.GRAY));
                        errorMessage.append(TextOps.withFormatting(" / ", ChatFormatting.DARK_GRAY));
                        errorMessage.append(option.getSecond().toString()).append(TextOps.withFormatting(" (server)", ChatFormatting.GRAY)).append("\n");
                    }
                    errorMessage.append("\n");
                }

                errorMessage.append(TextOps.withFormatting("these options could not be synchronized because\n", ChatFormatting.GRAY));
                errorMessage.append(TextOps.withFormatting("they require your client to be restarted\n", ChatFormatting.GRAY));
                errorMessage.append(TextOps.withFormatting("change them manually and restart if you want to join this server", ChatFormatting.GRAY));

                context.disconnect(TextOps.concat(PREFIX, errorMessage));
                return;
            }
        }

        Owo.LOGGER.info("Responding with client values");
        context.reply(toPacket(Option.SyncMode.INFORM_SERVER));
    }

    private static void applyServer(ConfigSyncPacket payload, IPayloadContext context) {
        Owo.LOGGER.info("Receiving client config");
        if (!(context.player() instanceof ServerPlayer player)) return;
        var connection = ((ServerCommonPacketListenerImplAccessor) player.connection).owo$getConnection();

        read(payload, (option, optionBuf) -> {
            var config = CLIENT_OPTION_STORAGE.computeIfAbsent(connection, $ -> new HashMap<>()).computeIfAbsent(option.configName(), s -> new HashMap<>());
            config.put(option.key(), ((EndecBuffer) (Object) optionBuf).read(option.endec()));
        });
    }

    private record ConfigSyncPacket(Map<String, ConfigEntry> configs) implements CustomPacketPayload {
        public static final Type<ConfigSyncPacket> ID = new Type<>(CONFIG_SYNC_CHANNEL);
        public static final Endec<ConfigSyncPacket> ENDEC = StructEndecBuilder.of(
                ConfigEntry.ENDEC.mapOf().fieldOf("configs", ConfigSyncPacket::configs),
                ConfigSyncPacket::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    private record ConfigEntry(Map<String, FriendlyByteBuf> options) {
        public static final Endec<ConfigEntry> ENDEC = StructEndecBuilder.of(
                MinecraftEndecs.FRIENDLY_BYTE_BUF.mapOf().fieldOf("options", ConfigEntry::options),
                ConfigEntry::new
        );
    }
}
