package com.jiangyuefengyu.redstonecircuit.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.jiangyuefengyu.redstonecircuit.structure.pocket.PocketSounds;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Fabric's stand-in for NeoForge's {@code PlayLevelSoundEvent.AtPosition} - the one place a sound made inside a
 * structure can be picked up and re-issued where it can actually be heard.
 *
 * <h2>Why this method and nowhere else</h2>
 * <b>The signatures were read off the mapped jar, not guessed</b> (javap, minecraft-merged 1.21.1):
 *
 * <pre>
 *   ServerLevel#playSeededSound(Player;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V
 *   ServerLevel#playSeededSound(Player;Lnet/minecraft/world/entity/Entity;Holder;SoundSource;FFJ)V
 *   Level#playSeededSound(Player;DDDHolder;SoundSource;FFJ)V   // abstract here, implemented by ServerLevel
 * </pre>
 *
 * <p>The position overload is the funnel: <em>every</em> position-shaped sound reaches it, whichever door it came
 * through.
 *
 * <pre>
 *   Level#playSound(Entity, BlockPos, SoundEvent, ...)   -&gt; playSound(Player, BlockPos, SoundEvent, ...)
 *   Level#playSound(Player, BlockPos, SoundEvent, ...)   -&gt; playSound(Player, DDD, SoundEvent, ...)
 *   Level#playSound(Player, DDD, SoundEvent, ...)        -&gt; playSeededSound(Player, DDD, Holder, ...)
 *   Level#playSeededSound(Player, DDD, SoundEvent, ...)  -&gt; playSeededSound(Player, DDD, Holder, ...)
 *                               // via BuiltInRegistries.SOUND_EVENT.wrapAsHolder
 * </pre>
 *
 * <p>which is why a note block, a lever, a button and a repeater are all covered by this one injection - they hand
 * over a {@code SoundEvent} and vanilla widens it to a {@code Holder} on the way in. NeoForge patches this very method
 * to fire {@code PlayLevelSoundEvent.AtPosition} (21.1 {@code patches/net/minecraft/server/level/ServerLevel.java.patch}),
 * so injecting here is the faithful stand-in rather than a plausible-looking one.
 *
 * <h2>Why the cancel lives in {@link PocketSounds}</h2>
 * The decision is one question - "is this sound the pocket's?" - and it is answered inside the bridge (level gate, plot
 * owner, the world cell). This class only carries it: at {@code HEAD} the original body has not run, so cancelling on a
 * positive answer means the pocket never sends the packet while the world sends exactly one.
 *
 * <p>{@code defaultRequire: 1} in the mixin config: if a future Minecraft reshapes this method the mod would silently
 * go mute again, which is far worse than a loud failure at startup.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelSoundMixin {

    @Inject(
            method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
            at = @At("HEAD"),
            cancellable = true)
    private void redstonecircuit$pocketSound(Player player, double x, double y, double z,
                                             Holder<SoundEvent> sound, SoundSource source,
                                             float volume, float pitch, long seed,
                                             CallbackInfo callback) {
        if (PocketSounds.replayAtOwner((ServerLevel) (Object) this, new Vec3(x, y, z), sound, source, volume, pitch)) {
            callback.cancel();
        }
    }
}
