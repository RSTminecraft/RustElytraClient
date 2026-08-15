package dev.rstminecraft.elytra;

import dev.rstminecraft.RustClientCore.utils.Pair;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.util.List;

public final class BlockChangeEvent {
    private final ChunkPos chunk;
    private final List<Pair<BlockPos, BlockState>> blocks;

    public BlockChangeEvent(ChunkPos chunk, List<Pair<BlockPos, BlockState>> blocks) {
        this.chunk = chunk;
        this.blocks = blocks;
    }

    public ChunkPos getChunkPos() {
        return chunk;
    }

    public List<Pair<BlockPos, BlockState>> getBlocks() {
        return blocks;
    }
}