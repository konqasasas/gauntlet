package konqasasas.gauntlet.play;

import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockCanBuildEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;

/**
 * Players never affect each other: no damage, no knockback (attacks, mace,
 * wind charges, explosions), no hits from another player's projectiles (they
 * pass through), no potions, no fishing hooks, and another player's body does
 * not block placing a block. Pushing is turned off by the team (Sidebar).
 * Players and mobs still interact normally.
 */
public final class PlayerContact implements Listener {
    /** The player behind an entity: itself, a projectile's shooter, a TNT or cloud source. */
    static Player responsible(Entity entity) {
        if(entity instanceof Player player) return player;
        if(entity instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        if(entity instanceof TNTPrimed tnt && tnt.getSource() instanceof Player player) return player;
        if(entity instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Player player) return player;
        return null;
    }
    /** True when another player (not the victim) is behind this entity. */
    private static boolean otherPlayer(Entity cause,Entity victim) {
        Player player=cause==null ? null : responsible(cause);
        return player!=null && victim instanceof Player && !player.equals(victim);
    }

    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true)
    public void damage(EntityDamageByEntityEvent event) {
        if(otherPlayer(event.getDamager(),event.getEntity())) event.setCancelled(true);
    }
    /** Also covers knockback by attacks and explosions (wind charges, mace smash). */
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true)
    public void knockback(EntityPushedByEntityAttackEvent event) {
        if(otherPlayer(event.getPushedBy(),event.getEntity())) event.setCancelled(true);
    }
    /** Cancelled hits let the projectile fly on through the player. */
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true)
    public void projectile(ProjectileHitEvent event) {
        if(event.getHitEntity()!=null && otherPlayer(event.getEntity(),event.getHitEntity())) event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true)
    public void potion(PotionSplashEvent event) {
        for(LivingEntity entity:event.getAffectedEntities()) if(otherPlayer(event.getEntity(),entity)) event.setIntensity(entity,0);
    }
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true)
    public void cloud(AreaEffectCloudApplyEvent event) {
        event.getAffectedEntities().removeIf(entity -> otherPlayer(event.getEntity(),entity));
    }
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true)
    public void fishing(PlayerFishEvent event) {
        if(event.getState()==PlayerFishEvent.State.CAUGHT_ENTITY && otherPlayer(event.getPlayer(),event.getCaught())) event.setCancelled(true);
    }

    /**
     * Placing was refused: allow it when the only obstacles are other players.
     * The block must still be supported, and never placed inside the placer.
     */
    @EventHandler(priority=EventPriority.LOW)
    public void build(BlockCanBuildEvent event) {
        Player placer=event.getPlayer();
        if(event.isBuildable() || placer==null) return;
        Block block=event.getBlock();
        if(!event.getBlockData().isSupported(block)) return;
        Location at=block.getLocation();
        VoxelShape shape=event.getBlockData().getCollisionShape(at);
        boolean blockedByPlayer=false;
        for(BoundingBox local:shape.getBoundingBoxes()) {
            BoundingBox box=local.clone().shift(at);
            for(Entity entity:block.getWorld().getNearbyEntities(box)) {
                if(!(entity instanceof LivingEntity || entity instanceof Vehicle)) continue;
                if(entity instanceof Player other && other.getGameMode()==GameMode.SPECTATOR) continue;
                if(entity instanceof Player other && !other.equals(placer)) { blockedByPlayer=true; continue; }
                return; // the placer themself, a mob or a vehicle: keep the refusal
            }
        }
        if(blockedByPlayer) event.setBuildable(true);
    }
}
