package com.george_vi.electroenergetics;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public class CEEMixinPlugin implements IMixinConfigPlugin {


    @Override
    public void onLoad(String s) {

    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // Names here MUST match the entries in electroenergetics.mixins.json exactly.
        //
        // The SubLevelAssemblyHelper test compared against
        // "com.george_vi.electroenergetics.mixins.SubLevelAssemblyHelper" - without the "Mixin"
        // suffix - while the config registers "...SubLevelAssemblyHelperMixin". equals() therefore
        // never matched, so this guard never fired. That class imports
        // dev.ryanhcode.sable.api.SubLevelAssemblyHelper at the top level and declares
        // @Mixin(SubLevelAssemblyHelper.class), so on a pack WITHOUT sable it cannot even be loaded:
        // the intended protection was dead, and the mod would fail rather than skip the mixin.
        //
        // Verified against the config file rather than by eye; the entries are
        //     SubLevelAssemblyHelperMixin
        //     compat.ElectricFanBlockEntityMixin
        if ((mixinClassName.equals("com.george_vi.electroenergetics.mixins.SubLevelAssemblyHelperMixin") ||
                mixinClassName.equals("com.george_vi.electroenergetics.mixins.compat.ElectricFanBlockEntityMixin")) &&
                LoadingModList.get().getModFileById("sable") == null)
            return false;

        // has to be disabled because youer does something so this mixin fails
        if (mixinClassName.equals("com.george_vi.electroenergetics.mixins.LivingEntityMixin") &&
                LoadingModList.get().getModFileById("youer") != null)
            return false;

        return true;
    }

    @Override
    public void acceptTargets(Set<String> set, Set<String> set1) {

    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String s, ClassNode classNode, String s1, IMixinInfo iMixinInfo) {

    }

    @Override
    public void postApply(String s, ClassNode classNode, String s1, IMixinInfo iMixinInfo) {

    }
}
