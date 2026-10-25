package com.godwand;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ExplosiveProjectileEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.World;

import java.util.*;

/** Toda a lógica de servidor das 5 habilidades da God Wand. */
public final class SkillManager {
    private SkillManager() {}

    public interface Effect {
        /** @return true quando o efeito terminou */
        boolean tick();
        void cancel();
    }

    private static final java.util.Random RNG = new java.util.Random();
    private static final BlockState AIR = Blocks.AIR.getDefaultState();
    private static final BlockState BRIDGE_STATE = Blocks.LIGHT_BLUE_STAINED_GLASS.getDefaultState();

    private static final List<Effect> EFFECTS = new ArrayList<>();
    private static final Map<UUID, TunnelData> TUNNELS = new HashMap<>();
    private static final Map<UUID, Integer> SNEAK_START = new HashMap<>();
    private static final Set<UUID> SUPPRESS_TAP = new HashSet<>();
    private static final Map<UUID, Singularity> SINGULARITIES = new HashMap<>();
    private static final Map<UUID, Vec3d> SUPERNOVA_TARGET = new HashMap<>();
    private static final Map<TempKey, Integer> BRIDGE = new HashMap<>();
    private static final ArrayDeque<TerrainJob> JOBS = new ArrayDeque<>();

    private record TempKey(RegistryKey<World> world, BlockPos pos) {}
    private static final class TunnelData {
        ServerWorld world;
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        TunnelData(ServerWorld w) { this.world = w; }
    }
    private static final class TerrainJob {
        final ServerWorld w; final List<BlockPos> list; int i = 0;
        TerrainJob(ServerWorld w, List<BlockPos> l) { this.w = w; this.list = l; }
    }

    // =====================================================================
    //  Utilidades
    // =====================================================================
    public static ItemStack heldWand(PlayerEntity p) {
        if (p.getMainHandStack().isOf(GodWandMod.GOD_WAND)) return p.getMainHandStack();
        if (p.getOffHandStack().isOf(GodWandMod.GOD_WAND)) return p.getOffHandStack();
        return ItemStack.EMPTY;
    }

    public static void setSkill(ServerPlayerEntity p, ItemStack st, int skill) {
        GodWandItem.setSkill(st, skill);
        GodWandItem.setActive(st, false);
        GodWandItem.setBridge(st, false);
        p.sendMessage(Text.translatable("skill.godwand." + skill), true);
    }

    public static void selectSkill(ServerPlayerEntity p, int skill) {
        ItemStack st = heldWand(p);
        if (st.isEmpty()) return;
        setSkill(p, st, MathHelper.clamp(skill, 0, GodWandItem.SKILLS - 1));
    }

    public static void markSneakUsed(ServerPlayerEntity p) { SUPPRESS_TAP.add(p.getUuid()); }

    private static Vec3d rayTarget(ServerPlayerEntity p, double range, boolean allowMiss) {
        HitResult hr = p.raycast(range, 1.0f, false);
        if (hr.getType() == HitResult.Type.MISS)
            return allowMiss ? p.getEyePos().add(p.getRotationVec(1f).multiply(range)) : null;
        return hr.getPos();
    }

    private static boolean isBoss(LivingEntity e) {
        return e instanceof EnderDragonEntity || e instanceof WitherEntity || e.getMaxHealth() >= 100;
    }

    private static void hurt(LivingEntity e, DamageSource src, float amount) {
        if (e instanceof EnderDragonEntity d) { d.damagePart(d.head, src, amount); return; }
        e.timeUntilRegen = 0;
        e.damage(src, amount);
    }

    private static List<LivingEntity> targets(ServerWorld w, Vec3d center, double radius, Entity exclude, boolean includePlayers) {
        return w.getEntitiesByClass(LivingEntity.class, new Box(center, center).expand(radius),
                e -> e.isAlive() && e != exclude && !e.isSpectator() && (includePlayers || !(e instanceof PlayerEntity)));
    }

    private static DisplayEntity.BlockDisplayEntity makeDisplay(ServerWorld w, BlockState s, Vec3d pos) {
        DisplayEntity.BlockDisplayEntity d = EntityType.BLOCK_DISPLAY.create(w);
        NbtCompound tag = new NbtCompound();
        tag.put("block_state", NbtHelper.fromBlockState(s));
        d.readNbt(tag);
        d.setNoGravity(true);
        d.refreshPositionAndAngles(pos.x, pos.y, pos.z, 0f, 0f);
        w.spawnEntity(d);
        return d;
    }

    private static boolean liftable(ServerWorld w, BlockPos pos) {
        BlockState s = w.getBlockState(pos);
        return !s.isAir() && s.getFluidState().isEmpty() && s.isFullCube(w, pos)
                && s.getHardness(w, pos) >= 0 && !s.hasBlockEntity();
    }

    private static void clearNow(ServerWorld w, BlockPos pos) {
        if (!w.isChunkLoaded(pos)) return;
        BlockState s = w.getBlockState(pos);
        if (s.isAir() || s.getHardness(w, pos) < 0) return;
        w.setBlockState(pos, AIR, Block.NOTIFY_LISTENERS);
    }

    /** Cratera permanente, escavada aos poucos (vários ticks) para não travar o servidor. */
    private static void queueSphere(ServerWorld w, BlockPos c, int r, int maxDy) {
        List<BlockPos> list = new ArrayList<>();
        for (int x = -r; x <= r; x++) for (int y = -r; y <= Math.min(r, maxDy); y++) for (int z = -r; z <= r; z++)
            if (x * x + y * y + z * z <= r * r) list.add(c.add(x, y, z));
        list.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(c)));
        JOBS.add(new TerrainJob(w, list));
    }

    /** Bloco levantado: o bloco real some e um BlockDisplay vanilla o representa. */
    private static final class Lifted {
        final BlockPos pos; final BlockState state; final DisplayEntity.BlockDisplayEntity disp;
        final Vec3d origin; final double dx, dz;
        final double h = 3 + RNG.nextDouble() * 4.5, phase = RNG.nextDouble() * Math.PI * 2;
        Lifted(ServerWorld w, BlockPos pos, BlockState st, Vec3d c) {
            this.pos = pos; this.state = st; this.origin = Vec3d.ofCenter(pos);
            this.dx = origin.x - c.x; this.dz = origin.z - c.z;
            this.disp = makeDisplay(w, st, origin.add(-0.5, -0.5, -0.5));
        }
        void moveTo(double x, double y, double z) { disp.refreshPositionAndAngles(x - 0.5, y - 0.5, z - 0.5, 0f, 0f); }
    }

    private static List<Lifted> liftColumns(ServerWorld w, Vec3d c, int radius, int max, int depth) {
        BlockPos cb = BlockPos.ofFloored(c.x, c.y - 0.1, c.z);
        List<int[]> cols = new ArrayList<>();
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++)
            if (x * x + z * z <= radius * radius) cols.add(new int[]{x, z});
        cols.sort(Comparator.comparingInt(a -> a[0] * a[0] + a[1] * a[1]));
        List<Lifted> out = new ArrayList<>();
        for (int[] col : cols) {
            for (int dy = 3; dy >= -6; dy--) {
                BlockPos pos = cb.add(col[0], dy, col[1]);
                if (!liftable(w, pos)) continue;
                for (int k = 0; k < depth && out.size() < max; k++) {
                    BlockPos bp = pos.down(k);
                    if (!liftable(w, bp)) break;
                    BlockState bs = w.getBlockState(bp);
                    w.setBlockState(bp, AIR, Block.NOTIFY_LISTENERS);
                    out.add(new Lifted(w, bp.toImmutable(), bs, c));
                }
                break;
            }
            if (out.size() >= max) break;
        }
        return out;
    }

    private static void restoreLifted(ServerWorld w, List<Lifted> list) {
        for (Lifted l : list) {
            l.disp.discard();
            BlockState cur = w.getBlockState(l.pos);
            if (cur.isAir() || cur.isReplaceable() || !cur.getFluidState().isEmpty())
                w.setBlockState(l.pos, l.state, Block.NOTIFY_ALL);
        }
    }

    // =====================================================================
    //  Tick global
    // =====================================================================
    public static void tick(MinecraftServer server) {
        int now = server.getTicks();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) tickPlayer(p, now);
        tickTunnels(server);
        tickBridge(server, now);
        for (Effect e : new ArrayList<>(EFFECTS)) {
            boolean done;
            try { done = e.tick(); } catch (Exception ex) { ex.printStackTrace(); e.cancel(); done = true; }
            if (done) EFFECTS.remove(e);
        }
        int budget = 3500;
        while (budget > 0 && !JOBS.isEmpty()) {
            TerrainJob j = JOBS.peek();
            while (budget > 0 && j.i < j.list.size()) { clearNow(j.w, j.list.get(j.i++)); budget--; }
            if (j.i >= j.list.size()) JOBS.poll();
        }
    }

    public static void shutdown(MinecraftServer server) {
        for (Effect e : new ArrayList<>(EFFECTS)) e.cancel();
        EFFECTS.clear();
        SINGULARITIES.clear();
        for (TunnelData td : TUNNELS.values()) restoreAll(td);
        TUNNELS.clear();
        for (TempKey k : BRIDGE.keySet()) {
            ServerWorld w = server.getWorld(k.world());
            if (w != null && w.getBlockState(k.pos()).isOf(BRIDGE_STATE.getBlock())) w.setBlockState(k.pos(), AIR, Block.NOTIFY_LISTENERS);
        }
        BRIDGE.clear();
        JOBS.clear();
    }

    // =====================================================================
    //  Habilidade 1: Caminho Espacial (túnel / ponte / escudo)
    // =====================================================================
    private static void tickPlayer(ServerPlayerEntity p, int now) {
        UUID id = p.getUuid();
        boolean sneak = p.isSneaking();
        Integer start = SNEAK_START.get(id);
        boolean tap = false;
        if (sneak && start == null) SNEAK_START.put(id, now);
        if (!sneak && start != null) {
            SNEAK_START.remove(id);
            boolean suppressed = SUPPRESS_TAP.remove(id);
            tap = (now - start) <= 8 && !suppressed;   // toque rápido no Shift
        }

        ItemStack st = heldWand(p);
        if (st.isEmpty() || GodWandItem.getSkill(st) != 0 || !GodWandItem.isActive(st) || p.isSpectator()) return;

        if (tap) {
            boolean b = !GodWandItem.isBridge(st);
            GodWandItem.setBridge(st, b);
            p.sendMessage(Text.translatable(b ? "msg.godwand.bridge" : "msg.godwand.tunnel"), true);
        }

        TunnelData td = TUNNELS.computeIfAbsent(id, k -> new TunnelData(p.getServerWorld()));
        if (td.world != p.getServerWorld()) { restoreAll(td); td.world = p.getServerWorld(); }

        if (GodWandItem.isBridge(st)) bridge(p, now); else carve(p, td);
        shield(p, now);
    }

    private static void carve(ServerPlayerEntity p, TunnelData td) {
        ServerWorld w = td.world;
        Direction f = p.getHorizontalFacing();
        Direction r = f.rotateYClockwise();
        BlockPos base = p.getBlockPos();
        for (int d = 0; d <= 2; d++) for (int l = -1; l <= 1; l++) for (int y = 0; y < 4; y++) {   // túnel 3x4
            BlockPos pos = base.offset(f, d).offset(r, l).up(y);
            BlockState s = w.getBlockState(pos);
            if (s.isAir() || !s.getFluidState().isEmpty() || s.getHardness(w, pos) < 0 || s.hasBlockEntity()) continue;
            td.blocks.put(pos.toImmutable(), s);
            w.setBlockState(pos, AIR, Block.NOTIFY_LISTENERS);   // sem drops, sem atualização de vizinhos
        }
    }

    private static void tickTunnels(MinecraftServer server) {
        Iterator<Map.Entry<UUID, TunnelData>> it = TUNNELS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, TunnelData> en = it.next();
            TunnelData td = en.getValue();
            ServerPlayerEntity owner = server.getPlayerManager().getPlayer(en.getKey());
            Iterator<Map.Entry<BlockPos, BlockState>> bi = td.blocks.entrySet().iterator();
            while (bi.hasNext()) {
                Map.Entry<BlockPos, BlockState> b = bi.next();
                BlockPos pos = b.getKey();
                boolean far = owner == null || owner.getServerWorld() != td.world
                        || sq(owner.getX() - (pos.getX() + 0.5)) + sq(owner.getZ() - (pos.getZ() + 0.5)) > 20.25
                        || Math.abs(owner.getY() - pos.getY()) > 8;
                if (!far || !td.world.isChunkLoaded(pos)) continue;
                BlockState cur = td.world.getBlockState(pos);
                if (!cur.isAir() && !cur.isReplaceable()) { bi.remove(); continue; }
                if (!td.world.doesNotIntersectEntities(null, VoxelShapes.cuboid(new Box(pos)))) continue;
                td.world.setBlockState(pos, b.getValue(), Block.NOTIFY_LISTENERS);   // fecha atrás de você
                bi.remove();
            }
            if (td.blocks.isEmpty() && owner == null) it.remove();
        }
    }

    private static void restoreAll(TunnelData td) {
        for (Map.Entry<BlockPos, BlockState> b : td.blocks.entrySet()) {
            BlockState cur = td.world.getBlockState(b.getKey());
            if (cur.isAir() || cur.isReplaceable()) td.world.setBlockState(b.getKey(), b.getValue(), Block.NOTIFY_LISTENERS);
        }
        td.blocks.clear();
    }

    private static double sq(double v) { return v * v; }

    private static void bridge(ServerPlayerEntity p, int now) {
        if (p.isSneaking() || p.getVelocity().y > 0.05) return;
        ServerWorld w = p.getServerWorld();
        BlockPos below = BlockPos.ofFloored(p.getX(), p.getY() - 0.5, p.getZ());
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx * dx + dz * dz > 1) continue;
            BlockPos pos = below.add(dx, 0, dz);
            TempKey key = new TempKey(w.getRegistryKey(), pos.toImmutable());
            if (w.getBlockState(pos).isAir()) {
                w.setBlockState(pos, BRIDGE_STATE, Block.NOTIFY_LISTENERS);
                BRIDGE.put(key, now + 40);
            } else if (BRIDGE.containsKey(key)) {
                BRIDGE.put(key, now + 40);
            }
        }
    }

    private static void tickBridge(MinecraftServer server, int now) {
        Iterator<Map.Entry<TempKey, Integer>> it = BRIDGE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<TempKey, Integer> en = it.next();
            if (now < en.getValue()) continue;
            ServerWorld w = server.getWorld(en.getKey().world());
            if (w != null && w.getBlockState(en.getKey().pos()).isOf(BRIDGE_STATE.getBlock()))
                w.setBlockState(en.getKey().pos(), AIR, Block.NOTIFY_LISTENERS);
            it.remove();
        }
    }

    private static void shield(ServerPlayerEntity p, int now) {
        ServerWorld w = p.getServerWorld();
        Vec3d c = p.getPos().add(0, p.getHeight() / 2, 0);
        for (Entity e : w.getOtherEntities(p, p.getBoundingBox().expand(3.5))) {
            Vec3d away = e.getPos().add(0, e.getHeight() / 2, 0).subtract(c);
            if (away.lengthSquared() < 1e-4) continue;
            away = away.normalize();
            if (e instanceof ExplosiveProjectileEntity) {                 // bolas de fogo
                w.spawnParticles(ParticleTypes.FLAME, e.getX(), e.getY(), e.getZ(), 8, 0.2, 0.2, 0.2, 0.05);
                e.discard();
            } else if (e instanceof ProjectileEntity pr && pr.getOwner() != p && pr.getVelocity().lengthSquared() > 0.04) {
                pr.setVelocity(away.multiply(0.8));                       // flechas, tridentes...
                pr.addVelocity(0, 0, 0);
                pr.velocityModified = true;
            } else if (e instanceof Monster m) {                          // empurra mobs hostis
                Vec3d push = new Vec3d(away.x, 0, away.z).normalize().multiply(0.5);
                e.setVelocity(push.x, Math.max(e.getVelocity().y, 0.1), push.z);
                e.velocityModified = true;
            }
        }
        if (now % 3 == 0) {
            for (int i = 0; i < 6; i++) {
                double a = now * 0.2 + i * Math.PI / 3;
                w.spawnParticles(ParticleTypes.END_ROD, p.getX() + Math.cos(a) * 1.3, p.getY() + 1 + Math.sin(now * 0.07 + i) * 0.7,
                        p.getZ() + Math.sin(a) * 1.3, 1, 0, 0, 0, 0.0);
            }
        }
    }

    // =====================================================================
    //  Carga (partículas / mira)
    // =====================================================================
    public static void chargeTick(ServerPlayerEntity p, int skill, int charged) {
        ServerWorld w = p.getServerWorld();
        Vec3d look = p.getRotationVec(1f);
        Vec3d front = p.getEyePos().add(look.multiply(1.6)).add(0, -0.3, 0);
        switch (skill) {
            case 1 -> {
                w.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, front.x, front.y, front.z, 3, 0.15, 0.15, 0.15, 0.01);
                w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, front.x, front.y, front.z, 2, 0.3, 0.3, 0.3, 0.1);
            }
            case 2, 4 -> {
                Vec3d right = look.crossProduct(new Vec3d(0, 1, 0)).normalize();
                Vec3d up = right.crossProduct(look).normalize();
                BlockStateParticleEffect stone = new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.STONE.getDefaultState());
                for (int k = 0; k < 3; k++) {
                    double a = charged * 0.6 + k * 2.09;
                    double r = 0.3 + 0.1 * k;
                    Vec3d pos = front.add(right.multiply(Math.cos(a) * r)).add(up.multiply(Math.sin(a) * r)).add(look.multiply(k * 0.25));
                    w.spawnParticles(stone, pos.x, pos.y, pos.z, 1, 0, 0, 0, 0);
                }
                int lvl = GodWandItem.level(charged, skill == 2 ? 12 : 12);
                if (charged % 12 == 0 && lvl <= 4 && charged > 0)
                    p.sendMessage(Text.translatable("msg.godwand.level", lvl), true);
            }
            case 3 -> {
                if (charged < 70) {   // mira livre; depois de 3,5 s o alvo fica fixo
                    Vec3d t = rayTarget(p, 180, false);
                    if (t != null) SUPERNOVA_TARGET.put(p.getUuid(), t);
                }
                Vec3d lock = SUPERNOVA_TARGET.get(p.getUuid());
                if (lock != null) {
                    for (int k = 0; k < 24; k++) {   // coluna de luz
                        w.spawnParticles(p, ParticleTypes.END_ROD, true, lock.x, lock.y + k * 2.5, lock.z, 1, 0.15, 0, 0.15, 0.0);
                    }
                    w.spawnParticles(ParticleTypes.END_ROD, lock.x, lock.y + 1, lock.z, 6, 0.4, 0.8, 0.4, 0.02);
                }
                if (charged % 20 == 0)
                    p.sendMessage(Text.translatable("msg.godwand.charging", Math.min(100, charged * 100 / GodWandItem.SUPERNOVA_TICKS)), true);
            }
            default -> { }
        }
    }

    public static void endCharge(ServerPlayerEntity p) { SUPERNOVA_TARGET.remove(p.getUuid()); }

    // =====================================================================
    //  Habilidade 2: Singularidade
    // =====================================================================
    public static boolean collapseSingularity(ServerPlayerEntity p) {
        Singularity s = SINGULARITIES.get(p.getUuid());
        if (s == null) return false;
        s.collapse();
        return true;
    }

    public static void fireSingularity(ServerPlayerEntity p) {
        collapseSingularity(p);
        Vec3d c = rayTarget(p, 40, true);
        Singularity s = new Singularity(p.getServerWorld(), p, c);
        SINGULARITIES.put(p.getUuid(), s);
        EFFECTS.add(s);
        p.getServerWorld().playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 2f, 0.5f);
    }

    private static final class Singularity implements Effect {
        final ServerWorld w; final ServerPlayerEntity owner; final Vec3d c;
        final List<Lifted> blocks; int age = 0; boolean done = false;
        static final int LIFE = 120;

        Singularity(ServerWorld w, ServerPlayerEntity owner, Vec3d c) {
            this.w = w; this.owner = owner; this.c = c;
            this.blocks = liftColumns(w, c, 5, 140, 2);
        }

        public boolean tick() {
            if (done) return true;
            age++;
            double ease = Math.min(1.0, age / 25.0), spin = age * 0.12;
            for (Lifted l : blocks) {
                double r0 = Math.sqrt(l.dx * l.dx + l.dz * l.dz);
                double ang = Math.atan2(l.dz, l.dx) + spin * (1.3 - r0 * 0.08);
                double r = r0 * (1 - 0.35 * ease);
                double y = l.origin.y + (c.y + l.h - l.origin.y) * ease + Math.sin(age * 0.1 + l.phase) * 0.3;
                l.moveTo(c.x + Math.cos(ang) * r, y, c.z + Math.sin(ang) * r);
            }
            for (LivingEntity e : targets(w, c, 8, owner, false)) {     // levanta mobs
                double vy = MathHelper.clamp((c.y + 4.5 - e.getY()) * 0.15, -0.3, 0.6);
                Vec3d toC = new Vec3d(c.x - e.getX(), 0, c.z - e.getZ()).multiply(0.04);
                e.setVelocity(toC.x, vy, toC.z);
                e.fallDistance = 0;
                e.velocityModified = true;
            }
            w.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, c.x, c.y + 4, c.z, 6, 0.6, 0.6, 0.6, 0.05);
            w.spawnParticles(ParticleTypes.REVERSE_PORTAL, c.x, c.y + 4, c.z, 10, 1.5, 1.5, 1.5, 0.1);
            if (age >= LIFE) { collapse(); return true; }
            return false;
        }

        void collapse() {
            if (done) return;
            done = true;
            restoreLifted(w, blocks);                                      // terreno 100% restaurado
            DamageSource crush = w.getDamageSources().fallingBlock(owner);
            DamageSource boom = w.getDamageSources().explosion((Entity) null, owner);
            for (LivingEntity e : targets(w, c, 9, owner, false)) {
                if (e.getMaxHealth() < 50) hurt(e, crush, 1000f);          // esmagados
                else hurt(e, boom, 150f);                                  // chefes / muita vida
            }
            w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y + 3, c.z, 2, 1.5, 1.5, 1.5, 0);
            w.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 3f, 0.7f);
            SINGULARITIES.remove(owner.getUuid(), this);
        }

        public void cancel() {
            if (done) return;
            done = true;
            restoreLifted(w, blocks);
            SINGULARITIES.remove(owner.getUuid(), this);
        }
    }

    // =====================================================================
    //  Habilidade 3: Broca da Terra
    // =====================================================================
    public static boolean fireDrill(ServerPlayerEntity p, int level) {
        Vec3d t = rayTarget(p, 30, false);
        if (t == null) { p.sendMessage(Text.translatable("msg.godwand.no_target"), true); return false; }
        EFFECTS.add(new Drill(p.getServerWorld(), p, t, level));
        return true;
    }

    private static final class Drill implements Effect {
        final ServerWorld w; final ServerPlayerEntity owner; final Vec3d c; final int level;
        final List<Lifted> blocks; int age = 0;

        Drill(ServerWorld w, ServerPlayerEntity owner, Vec3d c, int level) {
            this.w = w; this.owner = owner; this.c = c; this.level = level;
            this.blocks = liftColumns(w, c, 2 + level, 8 + 10 * level, 1);
            w.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_GRAVEL_BREAK, SoundCategory.PLAYERS, 3f, 0.5f);
        }

        public boolean tick() {
            age++;
            double up = Math.min(1.0, age / 20.0);
            double plunge = age >= 28 ? Math.min(1.0, (age - 28) / 8.0) : 0.0;
            int n = blocks.size();
            for (int i = 0; i < n; i++) {
                Lifted l = blocks.get(i);
                double t = n <= 1 ? 0 : i / (double) (n - 1);
                double r = (1 - t) * (1.0 + 0.5 * level) + 0.25;
                double a = age * 0.55 + t * 9.0;
                double ty = c.y + 0.6 + t * (2.5 + level * 1.4) * (1.0 - plunge);
                double tx = c.x + Math.cos(a) * r, tz = c.z + Math.sin(a) * r;
                l.moveTo(l.origin.x + (tx - l.origin.x) * up, l.origin.y + (ty - l.origin.y) * up, l.origin.z + (tz - l.origin.z) * up);
            }
            w.spawnParticles(ParticleTypes.CLOUD, c.x, c.y + 1.5, c.z, 3, 1.2, 1.0, 1.2, 0.04);   // vento
            if (age >= 36) { impact(); return true; }
            return false;
        }

        void impact() {
            for (Lifted l : blocks) l.disp.discard();
            double radius = 3 + level * 1.5;
            DamageSource boom = w.getDamageSources().explosion((Entity) null, owner);
            for (LivingEntity e : targets(w, c, radius, owner, true)) {
                hurt(e, boom, 25f * level);
                Vec3d dir = e.getPos().subtract(c).multiply(1, 0, 1);
                if (dir.lengthSquared() > 1e-4) { dir = dir.normalize().multiply(0.8); e.addVelocity(dir.x, 0.5, dir.z); e.velocityModified = true; }
            }
            queueSphere(w, BlockPos.ofFloored(c.x, c.y - 0.1, c.z), 2 + level, 1);   // cratera permanente
            w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y + 1, c.z, 1, 0, 0, 0, 0);
            w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.STONE.getDefaultState()), c.x, c.y + 1, c.z, 80, radius / 2, 0.5, radius / 2, 0.3);
            w.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 3f, 0.8f);
        }

        public void cancel() { for (Lifted l : blocks) { l.disp.discard(); } restoreLifted(w, blocks); }
    }

    // =====================================================================
    //  Habilidade 4: Supernova
    // =====================================================================
    public static boolean fireSupernova(ServerPlayerEntity p) {
        Vec3d t = SUPERNOVA_TARGET.get(p.getUuid());
        if (t == null) return false;
        EFFECTS.add(new Supernova(p.getServerWorld(), p, t));
        return true;
    }

    private static final class Supernova implements Effect {
        final ServerWorld w; final ServerPlayerEntity owner; final Vec3d ground, c;
        final List<Object[]> pulls = new ArrayList<>();   // {display, Vec3d pos}
        int age = 0;
        static final int LIFE = 100;

        Supernova(ServerWorld w, ServerPlayerEntity owner, Vec3d ground) {
            this.w = w; this.owner = owner; this.ground = ground; this.c = ground.add(0, 7, 0);
            w.playSound(null, ground.x, ground.y, ground.z, SoundEvents.ENTITY_WITHER_SPAWN, SoundCategory.PLAYERS, 6f, 0.5f);
        }

        @SuppressWarnings("unchecked")
        public boolean tick() {
            age++;
            double size = 0.5 + 3.5 * Math.min(1.0, age / 60.0);
            w.spawnParticles(ParticleTypes.LARGE_SMOKE, c.x, c.y, c.z, 12, size * 0.5, size * 0.5, size * 0.5, 0.02);
            w.spawnParticles(ParticleTypes.PORTAL, c.x, c.y, c.z, 30, size * 2, size * 2, size * 2, 0.8);
            for (int i = 0; i < 10; i++) {
                double a = age * 0.35 + i * 0.628;
                w.spawnParticles(ParticleTypes.END_ROD, c.x + Math.cos(a) * size * 1.6, c.y + Math.sin(age * 0.1 + i) * 0.4,
                        c.z + Math.sin(a) * size * 1.6, 1, 0, 0, 0, 0.01);
            }

            // suga blocos do entorno
            if (age <= LIFE - 15 && pulls.size() < 250) {
                for (int tries = 0; tries < 6; tries++) {
                    double ang = RNG.nextDouble() * Math.PI * 2, dist = RNG.nextDouble() * 14;
                    BlockPos col = BlockPos.ofFloored(ground.x + Math.cos(ang) * dist, ground.y, ground.z + Math.sin(ang) * dist);
                    for (int dy = 8; dy >= -8; dy--) {
                        BlockPos pos = col.up(dy);
                        if (!liftable(w, pos)) continue;
                        BlockState s = w.getBlockState(pos);
                        w.setBlockState(pos, AIR, Block.NOTIFY_LISTENERS);
                        Vec3d o = Vec3d.ofCenter(pos);
                        pulls.add(new Object[]{makeDisplay(w, s, o.add(-0.5, -0.5, -0.5)), o});
                        break;
                    }
                }
            }
            Iterator<Object[]> it = pulls.iterator();
            while (it.hasNext()) {
                Object[] pl = it.next();
                DisplayEntity.BlockDisplayEntity d = (DisplayEntity.BlockDisplayEntity) pl[0];
                Vec3d pos = (Vec3d) pl[1];
                Vec3d dir = c.subtract(pos);
                double len = dir.length();
                if (len < 1.5) { d.discard(); it.remove(); continue; }
                Vec3d swirl = new Vec3d(-dir.z, 0, dir.x).normalize().multiply(0.25);
                pos = pos.add(dir.normalize().multiply(Math.min(len, 0.4 + len * 0.08))).add(swirl);
                pl[1] = pos;
                d.refreshPositionAndAngles(pos.x - 0.5, pos.y - 0.5, pos.z - 0.5, 0f, 0f);
            }
            for (LivingEntity e : targets(w, c, 40, owner, false)) {          // puxa mobs
                Vec3d d = c.subtract(e.getPos());
                if (d.length() > 1) {
                    e.setVelocity(e.getVelocity().multiply(0.8).add(d.normalize().multiply(0.12)));
                    e.fallDistance = 0;
                    e.velocityModified = true;
                }
            }
            if (age >= LIFE) { explode(); return true; }
            return false;
        }

        void explode() {
            for (Object[] pl : pulls) ((DisplayEntity.BlockDisplayEntity) pl[0]).discard();
            pulls.clear();
            queueSphere(w, BlockPos.ofFloored(ground), 18, 8);                // cratera permanente
            DamageSource boom = w.getDamageSources().explosion((Entity) null, owner);
            for (LivingEntity e : targets(w, ground, 32, owner, false)) {
                if (isBoss(e)) hurt(e, boom, 250f);                           // chefes: dano explosivo intenso
                else hurt(e, boom, 100000f);                                  // mobs comuns: eliminados
            }
            w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, ground.x, ground.y + 2, ground.z, 14, 8, 4, 8, 0);
            w.spawnParticles(ParticleTypes.FLASH, c.x, c.y, c.z, 1, 0, 0, 0, 0);
            w.playSound(null, ground.x, ground.y, ground.z, SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 10f, 0.5f);
        }

        public void cancel() { for (Object[] pl : pulls) ((DisplayEntity.BlockDisplayEntity) pl[0]).discard(); pulls.clear(); }
    }

    // =====================================================================
    //  Habilidade 5: Canhão de Pedra
    // =====================================================================
    private static final float[] CANNON_DAMAGE = {130f, 240f, 370f, 520f};

    public static void fireCannon(ServerPlayerEntity p, int level) {
        Vec3d look = p.getRotationVec(1f);
        EFFECTS.add(new Cannon(p.getServerWorld(), p, p.getEyePos().add(look.multiply(1.5)), look, level));
        p.getServerWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 1.5f, 1.4f);
    }

    private static final class Cannon implements Effect {
        final ServerWorld w; final ServerPlayerEntity owner; final Vec3d dir; final int level;
        final DisplayEntity.BlockDisplayEntity disp; final Set<UUID> hit = new HashSet<>();
        Vec3d pos; int dist = 0;

        Cannon(ServerWorld w, ServerPlayerEntity owner, Vec3d pos, Vec3d dir, int level) {
            this.w = w; this.owner = owner; this.pos = pos; this.dir = dir; this.level = level;
            this.disp = makeDisplay(w, Blocks.STONE.getDefaultState(), pos.add(-0.5, -0.5, -0.5));
        }

        public boolean tick() {
            DamageSource src = w.getDamageSources().playerAttack(owner);
            int r = level >= 3 ? 2 : 1;
            for (int s = 0; s < 4; s++) {                                      // 4 blocos por tick
                pos = pos.add(dir);
                dist++;
                BlockPos bp = BlockPos.ofFloored(pos);
                if (!w.isChunkLoaded(bp) || dist >= 110) { disp.discard(); return true; }
                for (int x = -r; x <= r; x++) for (int y = -r; y <= r; y++) for (int z = -r; z <= r; z++)
                    if (x * x + y * y + z * z <= r * r) clearNow(w, bp.add(x, y, z));   // abre buraco na parede
                Box box = new Box(pos, pos).expand(1.0 + 0.25 * level);
                for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, box, x -> x.isAlive() && x != owner && !hit.contains(x.getUuid()))) {
                    hit.add(e.getUuid());                                      // perfura mobs (1 acerto cada)
                    hurt(e, src, CANNON_DAMAGE[level - 1]);
                }
            }
            disp.refreshPositionAndAngles(pos.x - 0.5, pos.y - 0.5, pos.z - 0.5, 0f, 0f);
            w.spawnParticles(ParticleTypes.CRIT, pos.x, pos.y, pos.z, 6, 0.3, 0.3, 0.3, 0.1);
            w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.STONE.getDefaultState()), pos.x, pos.y, pos.z, 8, 0.4, 0.4, 0.4, 0.1);
            return false;
        }

        public void cancel() { disp.discard(); }
    }
}
