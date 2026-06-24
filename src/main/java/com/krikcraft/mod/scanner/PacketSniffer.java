package com.krikcraft.mod.scanner;

import com.krikcraft.mod.KrikCraftMod;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.play.server.SChunkDataPacket;

import java.lang.reflect.Field;

/**
 * Перехватывает SChunkDataPacket и читает его int-поля chunkX/chunkZ напрямую.
 * Потом сканер через пару тиков проверит getTileEntityMap() этого чанка —
 * к тому времени Minecraft уже обработает пакет и заполнит карту спавнеров.
 */
public class PacketSniffer extends ChannelDuplexHandler {

    private static Field chunkXField = null, chunkZField = null;
    private static boolean fieldsResolved = false;

    private static void resolveFields(SChunkDataPacket packet) {
        if (fieldsResolved) return;
        fieldsResolved = true;
        try {
            // В SChunkDataPacket есть int chunkX и int chunkZ (они есть в любых маппингах)
            for (Field f : SChunkDataPacket.class.getDeclaredFields()) {
                if (f.getType() == int.class) {
                    f.setAccessible(true);
                    String name = f.getName();
                    // Ищем поля с "chunk" в имени
                    if (name.contains("chunkX") || (chunkXField == null && name.matches(".*[Xx].*"))) {
                        if (chunkXField == null) chunkXField = f;
                    }
                    if (name.contains("chunkZ") || (chunkZField == null && name.matches(".*[Zz].*") && f != chunkXField)) {
                        if (chunkZField == null) chunkZField = f;
                    }
                }
            }
            if (chunkXField != null && chunkZField != null) {
                KrikCraftMod.LOGGER.info("[KrikCraft] PacketSniffer ready: {} / {}",
                        chunkXField.getName(), chunkZField.getName());
            }
        } catch (Exception e) {
            KrikCraftMod.LOGGER.debug("[KrikCraft] PacketSniffer resolveFields: {}", e.getMessage());
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        try {
            if (msg instanceof SChunkDataPacket && Scanner.active) {
                SChunkDataPacket packet = (SChunkDataPacket) msg;
                resolveFields(packet);
                if (chunkXField != null && chunkZField != null) {
                    int cx = chunkXField.getInt(packet);
                    int cz = chunkZField.getInt(packet);
                    Scanner.onChunkPacket(cx, cz);
                }
            }
        } catch (Exception e) {
            KrikCraftMod.LOGGER.debug("[KrikCraft] PacketSniffer error: {}", e.getMessage());
        }
        super.channelRead(ctx, msg);
    }
}
