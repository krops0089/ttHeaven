package com.krikcraft.mod;

import com.krikcraft.mod.gui.ServerSelectGui;
import com.krikcraft.mod.keybind.KeyBindings;
import com.krikcraft.mod.scanner.PacketSniffer;
import com.krikcraft.mod.scanner.Scanner;
import io.netty.channel.ChannelPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(KrikCraftMod.MOD_ID)
public class KrikCraftMod {

    public static final String MOD_ID = "krikcraft";
    public static final Logger LOGGER  = LogManager.getLogger(MOD_ID);

    public static String  currentAnarchy    = "Анархия-1001";
    public static boolean autoSwitchEnabled = false;
    public static int     autoSwitchMinutes = 5;

    private static long   lastSpawnerFoundMs = System.currentTimeMillis();
    private static int    currentServerIndex = 0;
    private static boolean snifferRegistered = false;

    public KrikCraftMod() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::clientSetup);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void clientSetup(FMLClientSetupEvent event) {
        KeyBindings.register();
        LOGGER.info("[KrikCraft] Загружен. Ctrl+G — меню, Ctrl+J — старт/стоп сканера.");
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.world == null) return;

        if (!snifferRegistered && mc.getConnection() != null) {
            try {
                ChannelPipeline pipeline = mc.getConnection()
                        .getNetworkManager().channel().pipeline();
                if (pipeline.get("krikcraft_sniffer") == null) {
                    pipeline.addBefore("packet_handler", "krikcraft_sniffer",
                            new PacketSniffer());
                    LOGGER.info("[KrikCraft] PacketSniffer зарегистрирован.");
                }
                snifferRegistered = true;
            } catch (Exception e) {
                LOGGER.debug("[KrikCraft] Sniffer: {}", e.getMessage());
            }
        }

        KeyBindings.handleKeyInputs(mc);
        Scanner.tick(mc);
        tickAutoSwitch(mc);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onEntityJoin(EntityJoinWorldEvent event) {
        if (!Scanner.active) return;
        if (!(event.getWorld() instanceof net.minecraft.client.world.ClientWorld)) return;
        Scanner.onEntityJoined(event.getEntity());
    }

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getWorld() instanceof net.minecraft.client.world.ClientWorld)) return;
        if (!Scanner.active) return;
        if (event.getChunk() instanceof Chunk) {
            Scanner.onChunkLoaded((Chunk) event.getChunk());
        }
    }

    @SubscribeEvent
    public void onPlayerLogout(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggedOutEvent event) {
        snifferRegistered = false;
        Scanner.reset();
    }

    private void tickAutoSwitch(Minecraft mc) {
        if (!autoSwitchEnabled || !Scanner.active) return;
        if (System.currentTimeMillis() - lastSpawnerFoundMs >= autoSwitchMinutes * 60_000L)
            switchToNextServer(mc);
    }

    public static void onSpawnerFound() {
        lastSpawnerFoundMs = System.currentTimeMillis();
    }

    private static void switchToNextServer(Minecraft mc) {
        currentServerIndex = (currentServerIndex + 1) % ServerSelectGui.SERVERS.size();
        String next = ServerSelectGui.SERVERS.get(currentServerIndex);
        switchToServer(next);
        if (mc.player != null)
            mc.player.sendMessage(new StringTextComponent("§e[KrikCraft] → §b" + next),
                    mc.player.getUniqueID());
    }

    public static void switchToServer(String name) {
        currentAnarchy = name;
        int idx = ServerSelectGui.SERVERS.indexOf(name);
        if (idx >= 0) currentServerIndex = idx;
        Scanner.reset();
        resetAutoSwitchTimer();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null)
            mc.player.sendChatMessage("/an" + name.replace("Анархия-", ""));
    }

    public static void resetAutoSwitchTimer() {
        lastSpawnerFoundMs = System.currentTimeMillis();
    }

    public static long autoSwitchRemainingSeconds() {
        return Math.max(0,
            (autoSwitchMinutes * 60_000L - (System.currentTimeMillis() - lastSpawnerFoundMs)) / 1000L);
    }
}
