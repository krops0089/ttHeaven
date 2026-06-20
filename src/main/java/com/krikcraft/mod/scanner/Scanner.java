package com.krikcraft.mod.scanner;

import com.krikcraft.mod.KrikCraftMod;
import com.krikcraft.mod.discord.DiscordWebhook;
import net.minecraft.block.Blocks;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.entity.monster.*;
import net.minecraft.entity.monster.piglin.PiglinEntity;
import net.minecraft.entity.passive.PandaEntity;
import net.minecraft.entity.monster.SilverfishEntity;
import net.minecraft.entity.passive.SnowGolemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.tileentity.*;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;

import java.util.*;

public class Scanner {

    // ── Частоты сканирования ───────────────────────────────────────────────────
    private static final int TILE_SCAN_TICKS     = 5;    // каждые 0.25 сек
    private static final int ENTITY_SCAN_TICKS   = 3;    // каждые 0.15 сек
    private static final int RESCAN_CHUNK_TICKS  = 80;   // через 4 сек (было 100)
    private static final int RESET_ENTITIES_TICKS= 600;  // мобы «забываются» каждые 30 сек

    private static final int  ENTITY_RADIUS       = 256;
    private static final int  PLAYER_UPDATE_TICKS = 100;
    private static final int  GROUP_RADIUS        = 48;
    private static final long GROUP_TIMEOUT_MS    = 1500L; // быстрее флаш группы (было 2000)
    private static final int  DEEP_Y              = 21;

    // ── Настройки (управляются из GUI) ────────────────────────────────────────
    /** Искать пауков и пещерных пауков */
    public static boolean scanSpiders = false;
    /** Искать чешуйниц */
    public static boolean scanSilverfish = true;
    /** Искать панд */
    public static boolean scanPandas = true;

    // ── Кэши ──────────────────────────────────────────────────────────────────
    private static final Map<BlockPos, Integer>        spawnerCache  = new HashMap<>();
    private static final Map<String,   List<BlockPos>> pendingGroups = new HashMap<>();
    private static final Map<String,   Long>           groupFoundAt  = new HashMap<>();
    private static final Set<UUID>                     seenEntities  = new HashSet<>();
    private static final Map<UUID,     Integer>        playerLastSeen= new HashMap<>();
    private static final Set<BlockPos>                 foundBlocks   = new HashSet<>();
    private static final Map<Long,     Integer>        rescanAt      = new LinkedHashMap<>();
    private static final Set<Integer>                  recheckChunks = new HashSet<>();

    public  static boolean active = false;
    private static int     tick   = 0;

    // ── Основной тик ──────────────────────────────────────────────────────────
    public static void tick(Minecraft mc) {
        if (!active || mc.player == null || mc.world == null) return;
        tick++;

        if (tick % TILE_SCAN_TICKS == 0) {
            scanTileEntities(mc);
            processRescan(mc);
            flushGroups();
        }

        if (tick % ENTITY_SCAN_TICKS == 0) {
            scanEntities(mc);
        }

        if (tick % RESET_ENTITIES_TICKS == 0 && tick > 0) {
            seenEntities.clear();
        }
    }

    // ── EntityJoinWorldEvent ───────────────────────────────────────────────────
    public static void onEntityJoined(Entity entity) {
        if (entity instanceof PlayerEntity) {
            handleNewPlayer((PlayerEntity) entity);
            return;
        }
        UUID uid = entity.getUniqueID();
        if (seenEntities.contains(uid)) return;
        seenEntities.add(uid);

        String name = getEntityName(entity);
        if (name == null) return;
        BlockPos pos = entity.getPosition();
        DiscordWebhook.send(KrikCraftMod.currentAnarchy, name, pos.getX(), pos.getY(), pos.getZ());
    }

    // ── PacketSniffer callback ─────────────────────────────────────────────────
    public static void onChunkPacket(int cx, int cz) {
        if (!active) return;
        recheckChunks.add((cx << 16) | (cz & 0xFFFF));
    }

    // ── ChunkEvent.Load ────────────────────────────────────────────────────────
    public static void onChunkLoaded(Chunk chunk) {
        for (Map.Entry<BlockPos, TileEntity> e : chunk.getTileEntityMap().entrySet()) {
            BlockPos pos = e.getKey();
            TileEntity te = e.getValue();
            if (te instanceof MobSpawnerTileEntity) {
                handleSpawner(pos, "ChunkLoad");
            } else if (!foundBlocks.contains(pos)) {
                String name = specialTEName(te);
                if (name != null) {
                    foundBlocks.add(pos);
                    DiscordWebhook.send(KrikCraftMod.currentAnarchy, name, pos.getX(), pos.getY(), pos.getZ());
                }
            }
        }
        // Сканируем все блоки чанка на спавнеры (TileEntityMap может быть неполным)
        scanChunkForSpawners(chunk);

        long key = chunkKey(chunk.getPos().x, chunk.getPos().z);
        rescanAt.put(key, tick + RESCAN_CHUNK_TICKS);
    }

    // ── Сканы ─────────────────────────────────────────────────────────────────
    private static void scanTileEntities(Minecraft mc) {
        // Сканируем loadedTileEntityList
        for (TileEntity te : new ArrayList<>(mc.world.loadedTileEntityList)) {
            BlockPos pos = te.getPos();
            if (te instanceof MobSpawnerTileEntity) {
                handleSpawner(pos, "List");
            } else if (!foundBlocks.contains(pos)) {
                String name = specialTEName(te);
                if (name == null) name = specialBlockName(mc.world.getBlockState(pos).getBlock());
                if (name != null) {
                    foundBlocks.add(pos);
                    DiscordWebhook.send(KrikCraftMod.currentAnarchy, name, pos.getX(), pos.getY(), pos.getZ());
                }
            }
        }

        // Пакетный скан чанков из PacketSniffer
        for (int key : new ArrayList<>(recheckChunks)) {
            recheckChunks.remove(key);
            int cx = key >> 16, cz = key & 0xFFFF;
            if (!mc.world.chunkExists(cx, cz)) continue;
            Chunk chunk = mc.world.getChunk(cx, cz);
            scanChunkForSpawners(chunk);
        }
    }

    /** Полный перебор всех Y в чанке для поиска спавнеров */
    private static void scanChunkForSpawners(Chunk chunk) {
        int cx = chunk.getPos().x;
        int cz = chunk.getPos().z;
        // Сначала быстро через TileEntityMap
        for (Map.Entry<BlockPos, TileEntity> e : chunk.getTileEntityMap().entrySet()) {
            if (e.getValue() instanceof MobSpawnerTileEntity) {
                handleSpawner(e.getKey(), "TEMap");
            }
        }
        // Затем полный перебор блоков (ловит случаи когда TE не в Map)
        for (int bx = 0; bx < 16; bx++) {
            for (int y = 0; y <= 255; y++) {
                for (int bz = 0; bz < 16; bz++) {
                    BlockPos pos = new BlockPos(cx * 16 + bx, y, cz * 16 + bz);
                    if (spawnerCache.containsKey(pos)) continue;
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.world == null) return;
                    if (mc.world.getBlockState(pos).getBlock() == Blocks.SPAWNER) {
                        handleSpawner(pos, "BlockScan");
                    }
                }
            }
        }
    }

    private static void processRescan(Minecraft mc) {
        List<Long> ready = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : rescanAt.entrySet())
            if (tick >= e.getValue()) ready.add(e.getKey());

        for (long key : ready) {
            rescanAt.remove(key);
            int cx = (int)(key >> 32), cz = (int)(key & 0xFFFFFFFFL);
            if (!mc.world.chunkExists(cx, cz)) continue;
            Chunk chunk = mc.world.getChunk(cx, cz);
            scanChunkForSpawners(chunk);
            // Тоже перескануем дамагеры/прогрузчики на случай обновления
            for (Map.Entry<BlockPos, TileEntity> e : chunk.getTileEntityMap().entrySet()) {
                BlockPos pos = e.getKey();
                TileEntity te = e.getValue();
                if (!(te instanceof MobSpawnerTileEntity) && !foundBlocks.contains(pos)) {
                    String name = specialTEName(te);
                    if (name == null) name = specialBlockName(mc.world.getBlockState(pos).getBlock());
                    if (name != null) {
                        foundBlocks.add(pos);
                        DiscordWebhook.send(KrikCraftMod.currentAnarchy, name, pos.getX(), pos.getY(), pos.getZ());
                    }
                }
            }
            rescanAt.put(key, tick + RESCAN_CHUNK_TICKS);
        }
    }

    private static void scanEntities(Minecraft mc) {
        PlayerEntity self = mc.player;
        if (self == null || mc.world == null) return;

        double px = self.getPosX(), pz = self.getPosZ();
        AxisAlignedBB box = new AxisAlignedBB(
                px - ENTITY_RADIUS, 0, pz - ENTITY_RADIUS,
                px + ENTITY_RADIUS, 256, pz + ENTITY_RADIUS);

        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p.getUniqueID().equals(self.getUniqueID())) continue;
            handleNewPlayer(p);
        }

        List<Entity> mobs = mc.world.getEntitiesInAABBexcluding(
                self, box, e -> !(e instanceof PlayerEntity));

        for (Entity entity : mobs) {
            UUID uid = entity.getUniqueID();
            if (seenEntities.contains(uid)) continue;
            seenEntities.add(uid);

            String name = getEntityName(entity);
            if (name == null) continue;
            BlockPos pos = entity.getPosition();
            DiscordWebhook.send(KrikCraftMod.currentAnarchy, name, pos.getX(), pos.getY(), pos.getZ());
        }
    }

    private static void handleNewPlayer(PlayerEntity p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && p.getUniqueID().equals(mc.player.getUniqueID())) return;
        UUID uid = p.getUniqueID();
        Integer last = playerLastSeen.get(uid);
        if (last != null && (tick - last) < PLAYER_UPDATE_TICKS) return;
        playerLastSeen.put(uid, tick);
        BlockPos pos = p.getPosition();
        String name = "👤 Игрок: " + p.getGameProfile().getName();
        if (p.isInvisible()) name += " (невидимый)";
        DiscordWebhook.send(KrikCraftMod.currentAnarchy, name, pos.getX(), pos.getY(), pos.getZ());
    }

    // ── Спавнеры ──────────────────────────────────────────────────────────────
    private static void handleSpawner(BlockPos pos, String src) {
        if (spawnerCache.containsKey(pos)) return;
        spawnerCache.put(pos, tick);
        KrikCraftMod.onSpawnerFound();
        addToGroup(pos);
    }

    private static void addToGroup(BlockPos pos) {
        String best = null;
        outer:
        for (Map.Entry<String, List<BlockPos>> e : pendingGroups.entrySet()) {
            for (BlockPos gp : e.getValue()) {
                double dx = pos.getX()-gp.getX(), dz = pos.getZ()-gp.getZ();
                if (Math.sqrt(dx*dx+dz*dz) <= GROUP_RADIUS) { best = e.getKey(); break outer; }
            }
        }
        if (best == null) {
            best = pos.getX() + "_" + pos.getZ();
            pendingGroups.put(best, new ArrayList<>());
            groupFoundAt.put(best, System.currentTimeMillis());
        }
        pendingGroups.get(best).add(pos);
    }

    private static void flushGroups() {
        long now = System.currentTimeMillis();
        for (String key : new ArrayList<>(groupFoundAt.keySet())) {
            if (now - groupFoundAt.get(key) < GROUP_TIMEOUT_MS) continue;
            List<BlockPos> group = pendingGroups.remove(key);
            groupFoundAt.remove(key);
            if (group == null || group.isEmpty()) continue;
            if (group.size() == 1) {
                sendSpawner("🕷 Спавнер мобов", group.get(0));
            } else {
                int ax=0, ay=0, az=0;
                for (BlockPos p : group) { ax+=p.getX(); ay+=p.getY(); az+=p.getZ(); }
                BlockPos c = new BlockPos(ax/group.size(), ay/group.size(), az/group.size());
                sendSpawner("🕷 Группа спавнеров (" + group.size() + " шт)", c);
                for (BlockPos p : group) sendSpawner("   └ Спавнер", p);
            }
        }
    }

    private static void sendSpawner(String label, BlockPos pos) {
        if (pos.getY() <= DEEP_Y)
            DiscordWebhook.sendCrimson(KrikCraftMod.currentAnarchy, label, pos.getX(), pos.getY(), pos.getZ());
        else
            DiscordWebhook.send(KrikCraftMod.currentAnarchy, label, pos.getX(), pos.getY(), pos.getZ());
    }

    // ── Типы блоков ───────────────────────────────────────────────────────────
    private static String specialTEName(TileEntity te) {
        // Дамагеры — JigsawBlock используется как дамагер на серверах
        if (te instanceof JigsawTileEntity)         return "🧩 Дамагер (Jigsaw)";
        if (te instanceof StructureBlockTileEntity)  return "🔷 Прогрузчик (StructureBlock)";
        if (te instanceof CommandBlockTileEntity) {
            CommandBlockTileEntity cte = (CommandBlockTileEntity) te;
            String cmd = cte.getCommandBlockLogic().getCommand();
            if (cmd != null && !cmd.isEmpty())
                return "⌨ КомандБлок: " + cmd.substring(0, Math.min(cmd.length(), 40));
            return "⌨ Командный блок";
        }
        return null;
    }

    private static String specialBlockName(net.minecraft.block.Block b) {
        if (b == Blocks.JIGSAW)                  return "🧩 Дамагер (Jigsaw)";
        if (b == Blocks.STRUCTURE_BLOCK)         return "🔷 Прогрузчик (StructureBlock)";
        if (b == Blocks.COMMAND_BLOCK)           return "⌨ Командный блок";
        if (b == Blocks.CHAIN_COMMAND_BLOCK)     return "⌨ Цепной КБ";
        if (b == Blocks.REPEATING_COMMAND_BLOCK) return "⌨ Повтор КБ";
        if (b == Blocks.SPAWNER)                 return null; // обрабатываем отдельно
        return null;
    }

    // ── Типы мобов ────────────────────────────────────────────────────────────
    private static String getEntityName(Entity e) {
        // Пауки (только если включено в настройках)
        if (scanSpiders) {
            if (e instanceof CaveSpiderEntity) return "🕷 Пещерный паук";
            if (e instanceof SpiderEntity)     return "🕸 Паук";
        }
        // Чешуйница
        if (scanSilverfish && e instanceof SilverfishEntity) return "🐛 Чешуйница";
        // Панда
        if (scanPandas && e instanceof PandaEntity)          return "🐼 Панда";

        if (e instanceof StrayEntity)          return "👁 Зимогор";
        if (e instanceof ElderGuardianEntity)  return "👁 Elder Guardian";
        if (e instanceof GuardianEntity)       return "🔵 Страж";
        if (e instanceof ShulkerEntity)        return "🟣 Шалкер";
        if (e instanceof BlazeEntity)          return "🔥 Ифрит";
        if (e instanceof EvokerEntity)         return "🧙 Призыватель";
        if (e instanceof EndermiteEntity)      return "🖤 Эндермайт";
        if (e instanceof SnowGolemEntity)      return "⛄ Снежный голем";
        if (e instanceof VillagerEntity)       return "🟡 Житель";
        if (e instanceof CreeperEntity)
            return ((CreeperEntity)e).isCharged() ? "⚡ Крипер" : null;
        if (e instanceof MagmaCubeEntity)
            return ((MagmaCubeEntity)e).getSlimeSize() >= 2 ? "🌋 Магма куб" : null;
        if (e instanceof SlimeEntity)
            return ((SlimeEntity)e).getSlimeSize() >= 2 ? "🟢 Слизень" : null;
        return null;
    }

    private static long chunkKey(int cx, int cz) { return ((long)cx << 32)|(cz & 0xFFFFFFFFL); }

    public static void start()  { active = true; tick = 0; }
    public static void stop()   { active = false; }
    public static void reset() {
        stop();
        spawnerCache.clear(); pendingGroups.clear(); groupFoundAt.clear();
        seenEntities.clear(); playerLastSeen.clear(); foundBlocks.clear();
        rescanAt.clear(); recheckChunks.clear(); tick = 0;
    }
}
