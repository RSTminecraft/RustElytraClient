package dev.rstminecraft.elytra;

import dev.rstminecraft.utils.Pair;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.util.List;

public final class BlockChangeEvent {

    private final ChunkPos chunk;
    private final List<Pair<BlockPos, BlockState>> blocks;

    public BlockChangeEvent(ChunkPos pos, List<Pair<BlockPos, BlockState>> blocks) {
        this.chunk = pos;
        this.blocks = blocks;
    }

    public ChunkPos getChunkPos() {
        return this.chunk;
    }

    public List<Pair<BlockPos, BlockState>> getBlocks() {
        return this.blocks;
    }
}

