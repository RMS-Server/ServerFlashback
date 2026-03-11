package com.serverflashback.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.serverflashback.record.RecordingManager;
import com.serverflashback.record.ReplayMarker;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
//#if MC < 11900
//$$ import net.minecraft.network.chat.TextComponent;
//#endif
import net.minecraft.server.level.ServerLevel;
//#if MC >= 11903
import org.joml.Vector3f;
//#else
//$$ import com.mojang.math.Vector3f;
//#endif

import java.util.Set;

public class ServerFlashbackCommand {

    private static Component text(String s) {
//#if MC >= 11900
        return Component.literal(s);
//#else
//$$ return new TextComponent(s);
//#endif
    }

    private static void sendOk(CommandSourceStack source, Component msg, boolean log) {
//#if MC >= 12000
        source.sendSuccess(() -> msg, log);
//#else
//$$ source.sendSuccess(msg, log);
//#endif
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("serverflashback").requires(s -> s.hasPermission(2));

        root.then(Commands.literal("start")
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .then(Commands.argument("radius", IntegerArgumentType.integer(16, 4096))
                                .executes(ctx -> startRecording(ctx, null))
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .executes(ctx -> startRecording(ctx,
                                                StringArgumentType.getString(ctx, "name")))))));

        root.then(Commands.literal("stop")
                .executes(ctx -> stopRecording(ctx, null))
                .then(Commands.argument("name", StringArgumentType.string())
                        .executes(ctx -> stopRecording(ctx,
                                StringArgumentType.getString(ctx, "name")))));

        root.then(Commands.literal("pause")
                .executes(ctx -> pauseRecording(ctx, null))
                .then(Commands.argument("name", StringArgumentType.string())
                        .executes(ctx -> pauseRecording(ctx,
                                StringArgumentType.getString(ctx, "name")))));

        root.then(Commands.literal("resume")
                .executes(ctx -> resumeRecording(ctx, null))
                .then(Commands.argument("name", StringArgumentType.string())
                        .executes(ctx -> resumeRecording(ctx,
                                StringArgumentType.getString(ctx, "name")))));

        root.then(Commands.literal("list").executes(ServerFlashbackCommand::listRecordings));

        root.then(Commands.literal("mark")
                .then(Commands.argument("name", StringArgumentType.string())
                        .executes(ctx -> addMarker(ctx,
                                StringArgumentType.getString(ctx, "name"), null))
                        .then(Commands.argument("description", StringArgumentType.greedyString())
                                .executes(ctx -> addMarker(ctx,
                                        StringArgumentType.getString(ctx, "name"),
                                        StringArgumentType.getString(ctx, "description"))))));

        dispatcher.register(root);
    }

    private static int startRecording(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
//#if MC >= 11900
        BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
//#else
//$$ BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
//#endif
        int radius = IntegerArgumentType.getInteger(ctx, "radius");
        ServerLevel level = source.getLevel();

        String result = RecordingManager.getInstance().startRecording(
                source.getServer(), level, pos, radius, name);

        if (result != null) {
            sendOk(source, text("Started recording '" + result + "' at " + pos.toShortString() + " radius=" + radius), true);
            return 1;
        } else {
            source.sendFailure(text("Recording with name '" + name + "' already exists"));
            return 0;
        }
    }

    private static int stopRecording(CommandContext<CommandSourceStack> ctx, String name) {
        CommandSourceStack source = ctx.getSource();
        if (name == null) {
            Set<String> names = RecordingManager.getInstance().getActiveRecordingNames();
            if (names.isEmpty()) {
                source.sendFailure(text("No active recordings"));
                return 0;
            }
            name = names.iterator().next();
        }

        if (RecordingManager.getInstance().stopRecording(source.getServer(), name)) {
            sendOk(source, text("Stopped recording '" + name + "'"), true);
            return 1;
        } else {
            source.sendFailure(text("No recording named '" + name + "'"));
            return 0;
        }
    }

    private static int pauseRecording(CommandContext<CommandSourceStack> ctx, String name) {
        CommandSourceStack source = ctx.getSource();
        if (name == null) {
            Set<String> names = RecordingManager.getInstance().getActiveRecordingNames();
            if (names.isEmpty()) {
                source.sendFailure(text("No active recordings"));
                return 0;
            }
            name = names.iterator().next();
        }

        if (RecordingManager.getInstance().pauseRecording(name)) {
            sendOk(source, text("Paused recording '" + name + "'"), true);
            return 1;
        }
        source.sendFailure(text("No recording named '" + name + "'"));
        return 0;
    }

    private static int resumeRecording(CommandContext<CommandSourceStack> ctx, String name) {
        CommandSourceStack source = ctx.getSource();
        if (name == null) {
            Set<String> names = RecordingManager.getInstance().getActiveRecordingNames();
            if (names.isEmpty()) {
                source.sendFailure(text("No active recordings"));
                return 0;
            }
            name = names.iterator().next();
        }

        if (RecordingManager.getInstance().resumeRecording(name)) {
            sendOk(source, text("Resumed recording '" + name + "'"), true);
            return 1;
        }
        source.sendFailure(text("No recording named '" + name + "'"));
        return 0;
    }

    private static int listRecordings(CommandContext<CommandSourceStack> ctx) {
        Set<String> names = RecordingManager.getInstance().getActiveRecordingNames();
        if (names.isEmpty()) {
            sendOk(ctx.getSource(), text("No active recordings"), false);
        } else {
            sendOk(ctx.getSource(), text("Active recordings (" + names.size() + "):"), false);
            for (String n : names) {
                var recorder = RecordingManager.getInstance().getRecorder(n);
                if (recorder != null) {
                    sendOk(ctx.getSource(), text("  " + recorder.getDebugString()), false);
                }
            }
        }
        return 1;
    }

    private static int addMarker(CommandContext<CommandSourceStack> ctx, String name, String description) {
        CommandSourceStack source = ctx.getSource();
        var recorder = RecordingManager.getInstance().getRecorder(name);
        if (recorder == null) {
            source.sendFailure(text("No recording named '" + name + "'"));
            return 0;
        }

        ReplayMarker.MarkerPosition pos = null;
        if (source.getEntity() != null) {
//#if MC >= 11903
            Vector3f eyePos = source.getEntity().getEyePosition().toVector3f();
//#else
//$$ net.minecraft.world.phys.Vec3 eyeVec = source.getEntity().getEyePosition(1.0f);
//$$ Vector3f eyePos = new Vector3f((float) eyeVec.x, (float) eyeVec.y, (float) eyeVec.z);
//#endif
            pos = new ReplayMarker.MarkerPosition(eyePos, source.getLevel().dimension().toString());
        }

        recorder.addMarker(new ReplayMarker(0xFF5555, pos, description));
        sendOk(source, text("Added marker to '" + name + "'"), false);
        return 1;
    }
}
