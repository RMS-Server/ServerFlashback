package com.serverflashback.record;

import org.jetbrains.annotations.Nullable;
//#if MC >= 11903
import org.joml.Vector3f;
//#else
//$$ import com.mojang.math.Vector3f;
//#endif

public record ReplayMarker(int colour, @Nullable MarkerPosition position, @Nullable String description) {
    public record MarkerPosition(Vector3f position, String dimension) {}
}
