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

    private static final int TILE_SCAN_TICKS      = 3;
    private static final int ENTITY_SCAN_TICKS    = 3;
    private static final int RESET_ENTITIES_TICKS = 600;

    // Задержки сканирования чанка после загрузки (в тиках)
    // Сканируем несколько раз с нарастающей задержкой — ловим данные которые пришли позже
    private static final int[] SCAN_DELAYS = { 10, 40, 100, 200 };

    private static final int  ENTITY_RADIUS       = 256;
    private static final int  PLAYER_UPDATE_TICKS = 100;
    private static final int  GROUP_RADIUS        = 48;
    private static final long GROUP_TIMEOUT_MS    = 1500L;
    private static final int  DEEP_Y              = 21;

    public static boolean scanSpiders    = false;
    public static boolean scanSilverfish = true;
    public static boolean scanPandas     = true;

    // ── Кэши ──────────────────────────────────────────────────────────────────
    private static final Map<BlockPos, Integer>        spawnerCache  = new HashMap<>();
    private static final Map<String,   List<BlockPos>> pendingGroups = new HashMap<>();
    private static final Map<String,   Long>           groupFoundAt  = new HashMap<>();
    private static final Set<UUID>                     seenEntities  = new HashSet<>();
    private static final Map<UUID,     Integer>        playerLastSeen= new HashMap<>();
    private static final Set<BlockPos>                 foundBlocks   = new HashSet<>();
    private static final Set<Long>                     recheckChunks = new HashSet<>();

    // Очередь отложенных сканов: ключ чанка → список тиков когда нужно сканировать
    private static final Map<Long, List<Integer>>      pendingScans  = new HashMap<>();

    public  static boolean active = false;
    private static int     tick   = 0;

    // ── Основной тик ──────────────────────────────────────────────────────────
    public static void tick(Minecraft mc) {
        if (!active || mc.player == null || mc.world == null) return;
        tick++;

        if (tick % TILE_SCAN_TICKS == 0) {
            scanTileEntities(mc);
            processPendingScans(mc);   // <-- отложенные сканы
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
        recheckChunks.add(chunkKey(cx, cz));
    }

    // ── ChunkEvent.Load ────────────────────────────────────────────────────────
    public static void onChunkLoaded(Chunk chunk) {
        int cx = chunk.getPos().x, cz = chunk.getPos().z;
        long key = chunkKey(cx, cz);

        // Немедленный скан
        scanChunkForSpawners(chunk);
        scanChunkTileEntities(chunk);

        // Добавляем отложенные сканы с задержками
        List<Integer> delays = new ArrayList<>();
        for (int d : SCAN_DELAYS) delays.add(tick + d);
        pendingScans.put(key, delays);
    }

    // ── Отложенные сканы ──────────────────────────────────────────────────────
    private static void processPendingScans(Minecraft mc) {
        List<Long> toRemove = new ArrayList<>();

        for (Map.Entry<Long, List<Integer>> entry : pendingScans.entrySet()) {
            long key = entry.getKey();
            List<Integer> schedule = entry.getValue();

            // Убираем все тики которые уже наступили и сканируем
            schedule.removeIf(scanTick -> {
                if (tick < scanTick) return false;
                int cx = (int)(key >> 32), cz = (int)(key & 0xFFFFFFFFL);
                if (mc.world != null && mc.world.chunkExists(cx, cz)) {
                    Chunk chunk = mc.world.getChunk(cx, cz);
                    scanChunkForSpawners(chunk);
                }
                return true;
            });

            if (schedule.isEmpty()) toRemove.add(key);
        }

        for (long key : toRemove) pendingScans.remove(key);

        // Пакетный скан из PacketSniffer
        for (long key : new ArrayList<>(recheckChunks)) {
            recheckChunks.remove(key);
            int cx = (int)(key >> 32), cz = (int)(key & 0xFFFFFFFFL);
            if (mc.world == null || !mc.world.chunkExists(cx, cz)) continue;
            Chunk chunk = mc.world.getChunk(cx, cz);
            scanChunkForSpawners(chunk);
        }
    }

    // ── Скан loadedTileEntityList ──────────────────────────────────────────────
    private static void scanTileEntities(Minecraft mc) {
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
    }

    // ── Скан TileEntityMap чанка (спецблоки) ──────────────────────────────────
    private static void scanChunkTileEntities(Chunk chunk) {
        for (Map.Entry<BlockPos, TileEntity> e : chunk.getTileEntityMap().entrySet()) {
            BlockPos pos = e.getKey();
            TileEntity te = e.getValue();
            if (te instanceof MobSpawnerTileEntity) {
                handleSpawner(pos, "TEMap");
            } else if (!foundBlocks.contains(pos)) {
                String name = specialTEName(te);
                if (name != null) {
                    foundBlocks.add(pos);
                    DiscordWebhook.send(KrikCraftMod.currentAnarchy, name, pos.getX(), pos.getY(), pos.getZ());
                }
            }
        }
    }

    /**
     * Главный метод поиска — читает прямо из ChunkSection.
     * Находит спавнеры даже в закрытых подземных структурах потому что
     * не зависит от TileEntityMap и loadedTileEntityList.
     */
    private static void scanChunkForSpawners(Chunk chunk) {
        int cx = chunk.getPos().x, cz = chunk.getPos().z;
        net.minecraft.world.chunk.ChunkSection[] sections = chunk.getSections();

        for (int secIdx = 0; secIdx < sections.length; secIdx++) {
            net.minecraft.world.chunk.ChunkSection sec = sections[secIdx];
            if (sec == null || sec.isEmpty()) continue;

            int baseY = secIdx << 4;

            for (int bx = 0; bx < 16; bx++) {
                for (int by = 0; by < 16; by++) {
                    for (int bz = 0; bz < 16; bz++) {
                        if (sec.getBlockState(bx, by, bz).getBlock() != Blocks.SPAWNER) continue;
                        BlockPos pos = new BlockPos(cx * 16 + bx, baseY + by, cz * 16 + bz);
                        handleSpawner(pos, "SectionScan");
                    }
                }
            }
        }
    }

    private static void scanEntities(Minecraft mc) {
        PlayerEntity self = mc.player;
        if (self == null || mc.world == null) return;

        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p.getUniqueID().equals(self.getUniqueID())) continue;
            handleNewPlayer(p);
        }

        double px = self.getPosX(), pz = self.getPosZ();
        AxisAlignedBB box = new AxisAlignedBB(
                px-ENTITY_RADIUS, 0, pz-ENTITY_RADIUS,
                px+ENTITY_RADIUS, 256, pz+ENTITY_RADIUS);

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

    private static String specialTEName(TileEntity te) {
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
        return null;
    }

    private static String getEntityName(Entity e) {
        if (scanSpiders) {
            if (e instanceof CaveSpiderEntity) return "🕷 Пещерный паук";
            if (e instanceof SpiderEntity)     return "🕸 Паук";
        }
        if (scanSilverfish && e instanceof SilverfishEntity) return "🐛 Чешуйница";
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

    private static long chunkKey(int cx, int cz) { return ((long)cx << 32) | (cz & 0xFFFFFFFFL); }

    public static void start()  { active = true; tick = 0; }
    public static void stop()   { active = false; }
    public static void reset() {
        stop();
        spawnerCache.clear(); pendingGroups.clear(); groupFoundAt.clear();
        seenEntities.clear(); playerLastSeen.clear(); foundBlocks.clear();
        recheckChunks.clear(); pendingScans.clear(); tick = 0;
    }
}
