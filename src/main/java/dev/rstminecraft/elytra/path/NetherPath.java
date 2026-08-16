/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package dev.rstminecraft.elytra.path;

import dev.rstminecraft.RustClientCore.utils.BetterBlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.AbstractList;
import java.util.Collections;
import java.util.List;

/**
 * @author Brady
 */
public final class NetherPath extends AbstractList<BetterBlockPos> {

    private static final NetherPath EMPTY_PATH = new NetherPath(Collections.emptyList(),false);

    private final List<BetterBlockPos> backing;

    public final boolean complete;

    NetherPath(List<BetterBlockPos> backing, boolean complete) {
        this.backing = backing;
        this.complete = complete;
    }

    @Override
    public BetterBlockPos get(int index) {
        return backing.get(index);
    }

    @Override
    public int size() {
        return backing.size();
    }

    public BetterBlockPos getLast() {
        return isEmpty() ? null : backing.getLast();
    }

    public Vec3d getVec(int index) {
        final BetterBlockPos pos = get(index);
        return pos.toCenterPos();
    }

    public static NetherPath emptyPath() {
        return EMPTY_PATH;
    }
}
