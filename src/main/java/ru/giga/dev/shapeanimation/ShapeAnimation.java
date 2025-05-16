package ru.giga.dev.shapeanimation;

import blib.com.mojang.serialization.Codec;
import blib.com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.by1337.bc.CaseBlock;
import dev.by1337.bc.animation.AbstractAnimation;
import dev.by1337.bc.animation.AnimationContext;
import dev.by1337.bc.engine.MoveEngine;
import dev.by1337.bc.prize.Prize;
import dev.by1337.bc.prize.PrizeSelector;
import dev.by1337.bc.task.AsyncTask;
import dev.by1337.bc.yaml.CashedYamlContext;
import dev.by1337.virtualentity.api.entity.EntityEvent;
import dev.by1337.virtualentity.api.util.PlayerHashSet;
import dev.by1337.virtualentity.api.virtual.VirtualEntity;
import dev.by1337.virtualentity.api.virtual.item.VirtualItem;
import dev.by1337.virtualentity.api.virtual.projectile.VirtualFireworkRocketEntity;
import org.bukkit.*;
import org.bukkit.block.Lidded;
import org.bukkit.block.data.type.RespawnAnchor;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.by1337.blib.configuration.adapter.codec.YamlCodec;
import org.by1337.blib.configuration.serialization.BukkitCodecs;
import org.by1337.blib.configuration.serialization.DefaultCodecs;
import org.by1337.blib.geom.Vec3d;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

public class ShapeAnimation extends AbstractAnimation {

    private final VirtualItem virtualItem = VirtualItem.create();
    private final Prize winner;
    private final Config config;

    public ShapeAnimation(CaseBlock caseBlock, AnimationContext context, Runnable onEndCallback, PrizeSelector prizeSelector, CashedYamlContext yaml, Player player) {
        super(caseBlock, context, onEndCallback, prizeSelector, yaml, player);
        winner = prizeSelector.getRandomPrize();
        config = yaml.get("settings", v -> YamlCodec.codecOf(Config.CODEC).decode(v));
    }

    @Override
    protected void onStart() {
        caseBlock.hideHologram();
    }

    @Override
    protected void animate() throws InterruptedException {
        modifyAnchorCharges(charges -> config.anchorCharges.max - charges, 1, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE);
        modifyLidded(Lidded::open);

        virtualItem.setPos(center);
        trackEntity(virtualItem);

        var taskPart = new AsyncTask() {
            @Override
            public void run() {
                config.particles.ifPresent(part -> part.spawn(ShapeAnimation.this));
            }
        }.timer().delay(2).start(this);

        var taskSwap = new AsyncTask() {
            @Override
            public void run() {
                setParamsPrize(prizeSelector.getRandomPrize());
                config.item.swap.sound.ifPresent(v -> v.play(ShapeAnimation.this));
            }
        }.timer().delay(config.item.swap.period).start(this);
        MoveEngine.goTo(virtualItem, center.add(config.item.offset), config.item.speed).startSync(this);

        taskSwap.cancel();
        taskPart.cancel();
        setParamsPrize(winner);
        sleep(700);

        for (Config.Firework firework : config.fireworks) {
            VirtualFireworkRocketEntity virtualFirework = VirtualFireworkRocketEntity.create();

            PlayerHashSet players = new PlayerHashSet();
            tracker.forEachViewers(players::add);

            FireworkEffect effect = FireworkEffect.builder()
                    .with(firework.type)
                    .withColor(firework.colors)
                    .build();

            ItemStack itemFirework = new ItemStack(Material.FIREWORK_ROCKET);
            FireworkMeta meta = (FireworkMeta) itemFirework.getItemMeta();
            meta.addEffect(effect);
            itemFirework.setItemMeta(meta);

            virtualFirework.setFireworkItem(itemFirework);
            virtualFirework.setPos(virtualItem.getPos().add(firework.offset));

            virtualFirework.tick(players);
            virtualFirework.sendEntityEvent(EntityEvent.FIREWORKS_EXPLODE);
            virtualFirework.tick(Set.of());
        }
        sleep(2000);

        MoveEngine.goTo(virtualItem, center, config.item.speedBack).startSync(this);

        trackEntity(virtualItem);
        modifyAnchorCharges(charges -> charges - config.anchorCharges.min, -1, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE);
        modifyLidded(Lidded::close);
    }

    private void modifyLidded(Consumer<Lidded> action) {
        sync(() -> {
            var state = blockPos.toBlock(world).getState();
            if (state instanceof Lidded lidded) {
                action.accept(lidded);
                state.update();
            }
        }).start();
    }

    private void modifyAnchorCharges(Function<Integer, Integer> steps, int direction, Sound sound) throws InterruptedException {
        var block = blockPos.toBlock(world);
        if (!(block.getBlockData() instanceof RespawnAnchor anchor)) return;

        for (int i = 0; i <= steps.apply(anchor.getCharges()) + 1; i++) {
            int newCharges = anchor.getCharges() + direction;
            if (newCharges < 0 || newCharges > 4) break;

            sync(() -> {
                anchor.setCharges(newCharges);
                block.setBlockData(anchor);
            }).start();
            playSound(sound, 1, 1);
            sleepTicks(config.anchorCharges.interval);
        }
    }

    @Override
    protected void onEnd() {
        caseBlock.showHologram();
        if (winner != null) caseBlock.givePrize(winner, player);
    }

    private void setParamsPrize(Prize prize) {
        virtualItem.setItem(prize.itemStack());
        virtualItem.setCustomNameVisible(true);
        virtualItem.setCustomName(prize.displayNameComponent());
        virtualItem.setNoGravity(true);
        virtualItem.setMotion(Vec3d.ZERO);
    }

    @Override
    protected void onClick(VirtualEntity virtualEntity, Player player) {
    }

    @Override
    public void onInteract(PlayerInteractEvent playerInteractEvent) {
    }

    public record Config(Optional<Particles> particles, AnchorCharges anchorCharges, Item item, List<Firework> fireworks) {
        public final static Codec<Config> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Particles.CODEC.optionalFieldOf("particles").forGetter(Config::particles),
                AnchorCharges.CODEC.optionalFieldOf("anchor_charges", AnchorCharges.DEFAULT).forGetter(Config::anchorCharges),
                Item.CODEC.fieldOf("item").forGetter(Config::item),
                Firework.CODEC.listOf().optionalFieldOf("fireworks", Collections.emptyList()).forGetter(Config::fireworks)
        ).apply(instance, Config::new));

        public record AnchorCharges(long interval, int max, int min) {
            public final static AnchorCharges DEFAULT = new AnchorCharges(15, 3, 1);
            public final static Codec<AnchorCharges> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Codec.LONG.optionalFieldOf("interval", 15L).forGetter(AnchorCharges::interval),
                    Codec.INT.optionalFieldOf("max", 3).forGetter(AnchorCharges::max),
                    Codec.INT.optionalFieldOf("min", 1).forGetter(AnchorCharges::min)
            ).apply(instance, AnchorCharges::new));

            public AnchorCharges {
                max = Math.min(4, Math.max(0, max));
                min = Math.min(max, Math.max(0, min));
            }
        }

        public record Particles(Particle particle, Vec3d posOffset, Vec3d partOffset, int count, double speed) {
            public final static Codec<Particle> PARTICLE = DefaultCodecs.createAnyEnumCodec(Particle.class);
            public final static Codec<Particles> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    PARTICLE.fieldOf("name").forGetter(Particles::particle),
                    Vec3d.CODEC.fieldOf("pos_offset").forGetter(Particles::posOffset),
                    Vec3d.CODEC.fieldOf("part_offset").forGetter(Particles::partOffset),
                    Codec.INT.optionalFieldOf("count", 0).forGetter(Particles::count),
                    Codec.DOUBLE.optionalFieldOf("speed", 0.1).forGetter(Particles::speed)
            ).apply(instance, Particles::new));

            public void spawn(AbstractAnimation animation) {
                animation.spawnParticle(particle, animation.getCenter().clone().add(posOffset.toVector()), count, partOffset.x, partOffset.y, partOffset.z, speed);
            }
        }

        public record Item(double speed, double speedBack, Vec3d offset, Config.Item.Swap swap) {
            public final static Codec<Item> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Codec.DOUBLE.fieldOf("speed").forGetter(Item::speed),
                    Codec.DOUBLE.fieldOf("speed_back").forGetter(Item::speedBack),
                    Vec3d.CODEC.fieldOf("offset").forGetter(Item::offset),
                    Swap.CODEC.fieldOf("swap").forGetter(Item::swap)
            ).apply(instance, Item::new));

            public record Swap(long period, Optional<Sound> sound) {
                public final static Codec<Swap> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        Codec.LONG.fieldOf("period").forGetter(Swap::period),
                        Sound.CODEC.optionalFieldOf("sound").forGetter(Swap::sound)
                ).apply(instance, Swap::new));

                public record Sound(org.bukkit.Sound bukkit, float volume, float pitch) {
                    public final static Codec<Sound> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                            SoundFixer.CODEC.fieldOf("name").forGetter(Sound::bukkit),
                            Codec.FLOAT.optionalFieldOf("volume", 1f).forGetter(Sound::volume),
                            Codec.FLOAT.optionalFieldOf("pitch", 1f).forGetter(Sound::pitch)
                    ).apply(instance, Sound::new));

                    public void play(AbstractAnimation animation) {
                        animation.playSound(bukkit, volume, pitch);
                    }
                }
            }
        }

        public record Firework(FireworkEffect.Type type, List<Color> colors, Vec3d offset) {
            public static final Codec<FireworkEffect.Type> FIREWORK_TYPE = DefaultCodecs.createAnyEnumCodec(FireworkEffect.Type.class);
            public final static Codec<Firework> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    FIREWORK_TYPE.optionalFieldOf("type", FireworkEffect.Type.BALL).forGetter(Firework::type),
                    BukkitCodecs.COLOR.listOf().fieldOf("colors").forGetter(Firework::colors),
                    Vec3d.CODEC.fieldOf("offset").forGetter(Firework::offset)
            ).apply(instance, Firework::new));
        }
    }
}
