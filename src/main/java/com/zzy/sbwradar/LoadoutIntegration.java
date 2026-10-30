package com.zzy.sbwradar;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

/** Optional link. The nested implementation is resolved only when loadout is installed. */
public final class LoadoutIntegration {
    private static boolean installed;
    public static void install() {
        if (!installed && ModList.get().isLoaded("sbw_loadout")) {
            WithLoadout.install();
            installed = true;
        }
    }
    public static boolean handles(Player player, VehicleEntity vehicle) {
        return installed && WithLoadout.handles(player,vehicle);
    }
    public static boolean ownsVehicle(VehicleEntity vehicle) {
        return installed && WithLoadout.ownsVehicle(vehicle);
    }
    public static boolean wantsIrLock(Player player, VehicleEntity vehicle) {
        return !handles(player,vehicle) || WithLoadout.wantsIrLock(vehicle);
    }
    public static int activeFor(VehicleEntity vehicle) {
        return installed ? WithLoadout.activeFor(vehicle) : 0;
    }
    public static int tryDecoy(ServerLevel level, Vec3 pos, double radius, double chance,
                               String owner) {
        return installed ? WithLoadout.tryDecoy(level,pos,radius,chance,owner) : 0;
    }

    private static final class WithLoadout {
        static boolean ownsVehicle(VehicleEntity v) { return cn.zzy.loadout.LoadoutService.supported(v); }
        static boolean handles(Player p, VehicleEntity v) {
            return p instanceof ServerPlayer sp && cn.zzy.loadout.LoadoutService.pilot(sp,v)
                    && cn.zzy.loadout.LoadoutService.supported(v);
        }
        static boolean wantsIrLock(VehicleEntity v) {
            var state=cn.zzy.loadout.LoadoutService.state(v);
            if(state.entries.isEmpty()) return false;
            var a=cn.zzy.loadout.api.LoadoutApi.get(state.entries.getFirst().ammo());
            return a!=null && a.category().equalsIgnoreCase("AirToAirMissile")
                    && a.guidance().equalsIgnoreCase("IR");
        }
        static int activeFor(VehicleEntity v) { return cn.zzy.loadout.guidance.RadarGuidance.activeFor(v); }
        static int tryDecoy(ServerLevel l, Vec3 pos, double radius, double chance, String owner) {
            return cn.zzy.loadout.guidance.RadarGuidance.tryChaff(l,pos,radius,chance,owner);
        }
        static void install() {
            cn.zzy.loadout.api.RadarLink.install(new cn.zzy.loadout.api.RadarLink.Provider() {
                @Override public Entity lockedTarget(VehicleEntity v) {
                    return track(v,ServerRadarHandler.trackedTargetId(v.getId()));
                }
                public Entity launchTarget(VehicleEntity v, cn.zzy.loadout.api.AmmoType a) {
                    if(!SbwRadarConfig.MISSILE_ENABLE.get()) return null;
                    if(ServerRadarHandler.modeOf(v.getId())!=RadarMode.TWS) return lockedTarget(v);
                    if(!(v.getFirstPassenger() instanceof ServerPlayer p)) return null;
                    // 1.2.1 无 assignedTargets（分配去重），退化为"最近有效航迹"；
                    // 装新版 loadout 后可恢复按已分配目标优先
                    return ServerRadarHandler.twsTracks(v.getId()).stream()
                            .map(id->track(v,id)).filter(e->e!=null
                                    && cn.zzy.loadout.guidance.RadarLocks.valid(p,v,a,e))
                            .min(java.util.Comparator.<Entity>comparingDouble(e->e.distanceToSqr(v))).orElse(null);
                }
                public Entity trackedTarget(VehicleEntity v, java.util.UUID assigned) {
                    if(!SbwRadarConfig.MISSILE_ENABLE.get() || !(v.level() instanceof ServerLevel l)) return null;
                    Entity target=l.getEntity(assigned);
                    if(target==null) return null;
                    boolean valid=ServerRadarHandler.modeOf(v.getId())==RadarMode.TWS
                            ? ServerRadarHandler.isTracked(v.getId(),target.getId())
                            : ServerRadarHandler.trackedTargetId(v.getId())==target.getId();
                    return valid ? track(v,target.getId()) : null;
                }
                @Override public int otherChannels(VehicleEntity v) { return RadarMissileControl.ownActiveFor(v.getId()); }
                @Override public int maxChannels(VehicleEntity v,int ammoLimit) {
                    return SbwRadarConfig.MISSILE_ENABLE.get() ? Math.min(ammoLimit,SbwRadarConfig.MISSILE_MAX_CONCURRENT.get()) : 0;
                }
                public boolean hasInfraredControl() { return true; }
                public Entity infraredTarget(VehicleEntity v) { return IrMissileControl.lockedTarget(v); }
                public void infraredLaunched(net.minecraft.world.entity.projectile.Projectile shot,
                        ServerPlayer owner,VehicleEntity launcher,Entity target,cn.zzy.loadout.api.AmmoType ammo) {
                    IrMissileControl.trackLoadout(shot,owner,launcher,target,ammo.id());
                }
            });
        }
        private static Entity track(VehicleEntity v,int id) {
            if(!SbwRadarConfig.MISSILE_ENABLE.get() || !(v.level() instanceof ServerLevel l)
                    || !ServerRadarHandler.hasFreshTrack(v,id)) return null;
            Entity target=l.getEntity(id);
            return target!=null && target.isAlive() ? target : null;
        }
    }
    private LoadoutIntegration() {}
}
