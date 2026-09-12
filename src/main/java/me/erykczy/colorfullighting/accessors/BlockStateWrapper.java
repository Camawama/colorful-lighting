package me.erykczy.colorfullighting.accessors;

import me.erykczy.colorfullighting.common.accessors.BlockStateAccessor;
import me.erykczy.colorfullighting.common.accessors.LevelAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jetbrains.annotations.NotNull;

/**
 * To be converted into a static utility class and renamed to BlockStateHelper.
 * Null guards below are intentional: async chunk mods can violate the {@code @NotNull}
 * constructor contract at runtime.
 */
@Deprecated(forRemoval = true)
public class BlockStateWrapper implements BlockStateAccessor {
    final BlockState blockState;

    public BlockStateWrapper(@NotNull BlockState blockState) {
        this.blockState = blockState;
    }

    @Override
    public ResourceKey<Block> getBlockKey() {
        if (this.blockState == null) return null;
        return blockState.getBlockHolder().unwrapKey().get();
    }

    @Override
    public Block getBlock() {
        if (this.blockState == null) return null;
        return blockState.getBlock();
    }

    @Override
    public int getLightEmission() {
        if (this.blockState == null) return 0;
        return blockState.getLightEmission(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    @Override
    public int getLightBlock() {
        if (this.blockState == null) return 0;
        return blockState.getLightBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    @Override
    public int getLightEmission(LevelAccessor level, BlockPos pos) {
        if (this.blockState == null) return 0;
        if(level instanceof LevelWrapper levelWrapper)
            return blockState.getLightEmission(levelWrapper.getWrappedLevel(), pos);
        return getLightEmission();
    }

    @Override
    public int getLightBlock(LevelAccessor level, BlockPos pos) {
        if (this.blockState == null) return 0;
        if(level instanceof LevelWrapper levelWrapper)
            return blockState.getLightBlock(levelWrapper.getWrappedLevel(), pos);
        return getLightBlock();
    }

    @Override
    public boolean isAir() {
        if (this.blockState == null) return true;
        return blockState.isAir();
    }

    @Override
    public String getPropertiesAsString() {
        if (this.blockState == null) return "[]";
        return blockState.getValues().toString();
    }

    @Override
    public String getPropertyString(String propertyName) {
        if (this.blockState == null) return null;
        for (Property<?> prop : blockState.getProperties()) {
            if (prop.getName().equals(propertyName)) {
                return blockState.getValue(prop).toString();
            }
        }
        return null;
    }

    @Override
    public BlockState getBlockState() {
        return blockState;
    }
}