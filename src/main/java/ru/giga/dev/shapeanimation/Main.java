package ru.giga.dev.shapeanimation;

import dev.by1337.bc.addon.AbstractAddon;
import dev.by1337.bc.animation.AnimationRegistry;
import org.by1337.blib.util.SpacedNameKey;

import java.io.File;

public final class Main extends AbstractAddon {
    @Override
    protected void onEnable() {
        AnimationRegistry.INSTANCE.register("gigadev:shape", ShapeAnimation::new);
        saveResourceToFile("shapeAnimation.yml", new File(getPlugin().getDataFolder(), "animations/gigadev/shape.yml"));
    }

    @Override
    protected void onDisable() {
        AnimationRegistry.INSTANCE.unregister(new SpacedNameKey("gigadev:shape"));
    }
}
