package com.mastermarisa.maid_restaurant.utils;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.decoration.TableBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModRecipes;
import com.mastermarisa.maid_restaurant.api.ICookTask;
import com.mastermarisa.maid_restaurant.api.IMaidStorage;
import com.mastermarisa.maid_restaurant.data.TagBlock;
import com.mastermarisa.maid_restaurant.init.ModEntities;
import com.mastermarisa.maid_restaurant.maid.TaskCook;
import com.mastermarisa.maid_restaurant.request.CookRequest;
import com.mastermarisa.maid_restaurant.request.CookRequestHandler;
import com.mastermarisa.maid_restaurant.request.StockingMode;
import com.mastermarisa.maid_restaurant.utils.component.StackPredicate;
import it.unimi.dsi.fastutil.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.PositionTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds server-side cook-maid diagnostics without changing maid AI, requests, inventories,
 * block-usage claims, or world state.
 */
public class CookMaidDiagnostics {
    private static final int MAX_STACKS_PER_SECTION = 12;
    private static final int MAX_TARGETS_TO_PRINT = 8;

    public static List<String> buildReport(ServerLevel level, EntityMaid maid) {
        List<String> lines = new ArrayList<>();
        MemorySnapshot memories = MemorySnapshot.of(maid);

        lines.add("=== Maid Restaurant cook diagnostic ===");
        lines.add("maid=" + maid.getName().getString() + " uuid=" + maid.getUUID() + " entityId=" + maid.getId());
        lines.add("task=" + taskName(maid) + " isCookTask=" + (maid.getTask() instanceof TaskCook));
        lines.add("level=" + level.dimension().location() + " pos=" + formatPos(maid.blockPosition()) + " passenger=" + maid.isPassenger());
        if (!maid.hasData(CookRequestHandler.TYPE)) {
            lines.add("cookQueue=absent");
            lines.add("state=IDLE reason=NO_COOK_REQUEST_ATTACHMENT");
            appendMemoryLines(lines, memories, maid.getUUID());
            return lines;
        }

        CookRequestHandler handler = maid.getData(CookRequestHandler.TYPE);
        CookRequest request = handler.getFirst();
        lines.add("cookQueue.size=" + handler.size() + " accept=" + handler.accept);

        if (request == null) {
            lines.add("state=IDLE reason=NO_COOK_REQUEST");
            appendMemoryLines(lines, memories, maid.getUUID());
            return lines;
        }

        lines.add("request=" + describeRequest(request));
        lines.add("targets=" + describeTargets(level, request, maid.getUUID()));

        DiagnosticContext context = DiagnosticContext.create(level, maid, request, memories);
        appendMemoryLines(lines, memories, maid.getUUID());
        appendWorkBlockLines(lines, context, maid.getUUID());
        appendInventoryLines(lines, context);
        appendStateLines(lines, context, memories);
        return lines;
    }

    private static void appendStateLines(List<String> lines, DiagnosticContext context, MemorySnapshot memories) {
        lines.add("requestEnable.readOnly=" + context.enable.enabled + " exact=" + context.enable.exact + " reason=" + context.enable.reason);
        if (!context.enable.notes.isEmpty()) lines.add("requestEnable.notes=" + String.join("; ", context.enable.notes));

        lines.add("readOnlyCookState=" + context.state.state + " exact=" + context.enable.exact + " reason=" + context.state.reason);
        lines.add("stage.storage=" + context.state.storageStage);
        lines.add("stage.approach=" + context.state.approachStage);
        lines.add("stage.cooking=" + context.state.cookingStage);

        if (memories.targetType == 2 && (!memories.hasTargetPos || !memories.hasChairPos)) {
            lines.add("diagnostic.warning=TARGET_TYPE_2_WITH_MISSING_TARGET_OR_CHAIR_MEMORY");
        }
        if (memories.hasTargetPos && memories.hasChairPos && context.workBlock != null && !context.workBlock.valid) {
            lines.add("diagnostic.warning=COOKING_TARGET_WORK_BLOCK_INVALID");
        }
        if (!context.enable.exact) {
            lines.add("diagnostic.warning=READ_ONLY_ENABLEMENT_IS_BEST_EFFORT_FOR_STORAGE_TARGET_CAPACITY");
        }
        if (context.request.remain <= 0) {
            lines.add("diagnostic.warning=REQUEST_REMAIN_NON_POSITIVE_PENDING_COMPLETION_PATH");
        }
    }

    private static void appendMemoryLines(List<String> lines, MemorySnapshot memories, UUID maidId) {
        lines.add("memory.targetPos=" + memories.targetPosText + usageSuffix(memories.targetPos, maidId));
        lines.add("memory.targetType=" + memories.targetType);
        lines.add("memory.cachedWorkBlock=" + memories.cachedWorkBlockText + usageSuffix(memories.cachedWorkBlock, maidId));
        lines.add("memory.chairPos=" + memories.chairPosText);
        lines.add("memory.walkTargetPresent=" + memories.walkTargetPresent + " lookTargetPresent=" + memories.lookTargetPresent);
    }

    private static void appendWorkBlockLines(List<String> lines, DiagnosticContext context, UUID maidId) {
        if (context.workBlock == null) {
            lines.add("workBlock=absent");
            return;
        }
        WorkBlockSnapshot snapshot = context.workBlock;
        lines.add("workBlock.pos=" + formatPos(snapshot.pos) + usageSuffix(snapshot.pos, maidId));
        lines.add("workBlock.state=" + snapshot.blockState + " blockEntity=" + snapshot.blockEntity + " valid=" + snapshot.valid);
        lines.add("workBlock.currentInput=" + describeStacks(snapshot.currentInput));
        if (snapshot.currentInputError != null) lines.add("workBlock.currentInputError=" + snapshot.currentInputError);
        lines.add("workBlock.currentInputCoversRequired=" + snapshot.currentInputCoversRequired);
    }

    private static void appendInventoryLines(List<String> lines, DiagnosticContext context) {
        lines.add("maidInventory.nonEmpty=" + describeStacks(context.maidInventory));
        lines.add("required.total=" + context.required.size() + " missingWithWorkInput=" + describeMissing(context.missingWithWorkInput));
        lines.add("missingFromMaidOnly=" + describeMissing(context.missingFromMaidOnly));
    }

    private static DiagnosticState classify(DiagnosticContext context, MemorySnapshot memories) {
        if (!context.enable.exact) {
            return new DiagnosticState(
                    "UNKNOWN",
                    "REQUEST_ENABLEMENT_BEST_EFFORT:" + context.enable.reason,
                    "unverified: storage target availability/capacity was not simulated",
                    "unverified: request enablement is best-effort",
                    "unverified: cannot claim cooking readiness from best-effort storage checks"
            );
        }

        if (!context.enable.enabled) {
            return new DiagnosticState(
                    "IDLE",
                    "REQUEST_DISABLED_OR_UNAVAILABLE:" + context.enable.reason,
                    "blocked: request is not enabled by read-only target checks",
                    "blocked: request is not enabled",
                    "blocked: request is not enabled"
            );
        }

        if (!context.missingWithWorkInput.isEmpty()) {
            return new DiagnosticState(
                    "STORAGE",
                    "MISSING_REQUIRED_ITEMS_AFTER_MAID_AND_WORK_INPUT",
                    memories.targetType == 0 && memories.hasTargetPos ? "active: moving to storage target" : "needed: find storage containing missing items",
                    "blocked: still missing required items",
                    "blocked: cook prerequisites incomplete"
            );
        }

        if (!memories.hasTargetPos) {
            return new DiagnosticState(
                    "COOK",
                    "READY_FOR_WORK_BLOCK_SEARCH",
                    "not needed: required items appear available",
                    "needed: search/select work block and chair",
                    "blocked: TARGET_POS is absent"
            );
        }

        if (memories.targetType == 0) {
            return new DiagnosticState(
                    "COOK",
                    "HAS_STORAGE_TARGET_BUT_ITEMS_APPEAR_READY",
                    "suspicious: storage target remains even though required items appear available",
                    "blocked: storage target may need to stop/clear before approach can start",
                    "blocked: TARGET_TYPE is storage, not cooking"
            );
        }

        if (memories.targetType == 1) {
            return new DiagnosticState(
                    "COOK",
                    "APPROACHING_WORK_BLOCK",
                    "not needed: required items appear available",
                    memories.hasChairPos ? "active: moving to chair/work block" : "blocked: approach target lacks CHAIR_POS",
                    "blocked: TARGET_TYPE is approach, not cooking"
            );
        }

        if (memories.targetType != 2) {
            return new DiagnosticState(
                    "COOK",
                    "UNKNOWN_TARGET_TYPE_" + memories.targetType,
                    "not needed: required items appear available",
                    "blocked: target type is not approach/cooking",
                    "blocked: target type is not cooking"
            );
        }

        if (!memories.hasChairPos) {
            return new DiagnosticState(
                    "COOK",
                    "COOKING_PRECONDITION_FAILED_MISSING_CHAIR_POS",
                    "not needed: required items appear available",
                    "blocked: chair memory missing",
                    "blocked: CHAIR_POS is absent"
            );
        }

        if (context.workBlock == null) {
            return new DiagnosticState(
                    "COOK",
                    "COOKING_PRECONDITION_FAILED_MISSING_WORK_BLOCK",
                    "not needed: required items appear available",
                    "blocked: target work block missing",
                    "blocked: TARGET_POS/CACHED_WORK_BLOCK unavailable"
            );
        }

        int users = BlockUsageManager.getExistingUserCount(context.workBlock.pos);
        boolean usedByMaid = BlockUsageManager.isExistingUser(context.workBlock.pos, context.maid.getUUID());
        if (users > 0 && !usedByMaid) {
            return new DiagnosticState(
                    "COOK",
                    "COOKING_PRECONDITION_FAILED_WORK_BLOCK_USED_BY_OTHER",
                    "not needed: required items appear available",
                    "blocked: work block usage claim belongs to another entity",
                    "blocked: blockUsage users=" + users + " usedByThisMaid=false"
            );
        }

        if (!context.workBlock.valid) {
            return new DiagnosticState(
                    "COOK",
                    "COOKING_PRECONDITION_FAILED_WORK_BLOCK_INVALID",
                    "not needed: required items appear available",
                    "blocked: selected work block is invalid for recipe task",
                    "blocked: ICookTask.isValidWorkBlock returned false"
            );
        }

        return new DiagnosticState(
                "COOK",
                "COOKING_PRECONDITIONS_APPEAR_READY",
                "not needed: required items appear available",
                "complete: target/chair memories present",
                "ready: target type, request, usage, inventory, and work block checks passed"
        );
    }

    private static EnableSnapshot evaluateRequestEnable(ServerLevel level, CookRequest request, UUID maidId) {
        List<String> notes = new ArrayList<>();
        if (request.targets == null || request.targets.length == 0) {
            return new EnableSnapshot(false, true, "NO_TARGETS", notes);
        }
        if (request.type == null) {
            return new EnableSnapshot(false, true, "NO_RECIPE_TYPE", notes);
        }
        if (request.id == null) {
            return new EnableSnapshot(false, true, "NO_RECIPE_ID", notes);
        }
        ICookTask task = CookTasks.getTask(request.type);
        if (task == null) {
            return new EnableSnapshot(false, true, "NO_COOK_TASK_FOR_TYPE", notes);
        }
        Optional<? extends RecipeHolder<?>> recipe = level.getRecipeManager().byKey(request.id);
        if (recipe.isEmpty()) {
            return new EnableSnapshot(false, true, "RECIPE_NOT_FOUND", notes);
        }

        StockingMode stockingMode = request.attributes.getStockingMode();
        if (stockingMode == StockingMode.DISABLED) {
            return new EnableSnapshot(true, true, "STOCKING_DISABLED_BYPASSES_TARGET_CHECK", notes);
        }

        if (stockingMode == StockingMode.INSERTABLE) {
            boolean any = Arrays.stream(request.targets).mapToObj(EncodeUtils::decode).anyMatch(pos -> checkInsertableTarget(level, maidId, pos, notes));
            return new EnableSnapshot(any, notes.isEmpty(), any ? "INSERTABLE_TARGET_AVAILABLE" : "NO_INSERTABLE_TARGET_AVAILABLE", notes);
        }

        if (stockingMode == StockingMode.SPACE_ENOUGH) {
            @SuppressWarnings("unchecked")
            RecipeHolder<? extends Recipe<?>> typedRecipe = (RecipeHolder<? extends Recipe<?>>) recipe.get();
            ItemStack result = task.getResult(typedRecipe, level);
            int requiredSlots = request.requested * result.getCount();
            int slots = 0;
            boolean blockResult = result.getItem() instanceof BlockItem;
            for (long target : request.targets) {
                if (slots >= requiredSlots) break;
                slots += countReadOnlyTargetSpace(level, maidId, EncodeUtils.decode(target), result, blockResult, requiredSlots - slots, notes);
            }
            return new EnableSnapshot(slots >= requiredSlots, notes.isEmpty(), "SPACE_ENOUGH slots=" + slots + "/" + requiredSlots, notes);
        }

        return new EnableSnapshot(true, false, "UNKNOWN_STOCKING_MODE_TREATED_AS_ENABLED", notes);
    }

    private static boolean checkInsertableTarget(ServerLevel level, UUID maidId, BlockPos pos, List<String> notes) {
        int count = BlockUsageManager.getExistingUserCount(pos);
        if (count > 0 && !(BlockUsageManager.isExistingUser(pos, maidId) && count == 1)) return false;
        BlockState state = level.getBlockState(pos);
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof TableBlockEntity table) {
            return level.getBlockState(pos.immutable().above()).canBeReplaced() && table.getItems().getStackInSlot(3).isEmpty();
        }
        if (state.is(TagBlock.SERVE_MEAL_BLOCK) && level.getBlockState(pos.immutable().above()).canBeReplaced()) {
            return true;
        }
        IMaidStorage storage = MaidStorages.tryGetType(level, pos);
        if (storage != null) {
            notes.add("target " + formatPos(pos) + " is storage; exact insertability simulation intentionally skipped to avoid storage side effects");
            return true;
        }
        return false;
    }

    private static int countReadOnlyTargetSpace(ServerLevel level, UUID maidId, BlockPos pos, ItemStack result, boolean blockResult, int needed, List<String> notes) {
        int count = BlockUsageManager.getExistingUserCount(pos);
        if (count > 0 && !(BlockUsageManager.isExistingUser(pos, maidId) && count == 1)) return 0;
        BlockState state = level.getBlockState(pos);
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof TableBlockEntity table) {
            if (!level.getBlockState(pos.immutable().above()).canBeReplaced()) return 0;
            int empty = getEmptySlots(table.getItems());
            return blockResult ? (empty == 4 ? 1 : 0) : empty;
        }
        if (state.is(TagBlock.SERVE_MEAL_BLOCK)) {
            return level.getBlockState(pos.immutable().above()).canBeReplaced() && blockResult ? 1 : 0;
        }
        IMaidStorage storage = MaidStorages.tryGetType(level, pos);
        if (storage != null) {
            notes.add("target " + formatPos(pos) + " is storage; exact capacity simulation intentionally skipped for " + describeStack(result) + " to avoid storage side effects");
            return needed;
        }
        return 0;
    }

    private static int getEmptySlots(IItemHandler itemStackHandler) {
        int count = 0;
        for (int i = 0; i < itemStackHandler.getSlots(); i++) {
            if (itemStackHandler.getStackInSlot(i).isEmpty()) count++;
        }
        return count;
    }

    private static String describeRequest(CookRequest request) {
        return "id=" + safeLocation(request.id) +
                " type=" + safeRecipeType(request.type) +
                " remain=" + request.remain +
                " requested=" + request.requested +
                " stocking=" + request.attributes.getStockingMode() +
                " cycle=" + request.attributes.cycle() +
                " targets=" + (request.targets == null ? 0 : request.targets.length);
    }

    private static String describeTargets(ServerLevel level, CookRequest request, UUID maidId) {
        if (request.targets == null || request.targets.length == 0) return "[]";
        List<String> values = new ArrayList<>();
        for (int i = 0; i < Math.min(request.targets.length, MAX_TARGETS_TO_PRINT); i++) {
            BlockPos pos = EncodeUtils.decode(request.targets[i]);
            values.add(formatPos(pos) + " state=" + blockStateName(level, pos) + usageSuffix(pos, maidId));
        }
        if (request.targets.length > MAX_TARGETS_TO_PRINT) values.add("..." + (request.targets.length - MAX_TARGETS_TO_PRINT) + " more");
        return values.toString();
    }

    private static String describeStacks(List<ItemStack> stacks) {
        if (stacks.isEmpty()) return "[]";
        List<String> values = new ArrayList<>();
        for (int i = 0; i < Math.min(stacks.size(), MAX_STACKS_PER_SECTION); i++) {
            values.add(describeStack(stacks.get(i)));
        }
        if (stacks.size() > MAX_STACKS_PER_SECTION) values.add("..." + (stacks.size() - MAX_STACKS_PER_SECTION) + " more");
        return values.toString();
    }

    private static String describeMissing(List<Pair<StackPredicate, Integer>> missing) {
        if (missing.isEmpty()) return "[]";
        List<String> values = new ArrayList<>();
        for (int i = 0; i < Math.min(missing.size(), MAX_STACKS_PER_SECTION); i++) {
            Pair<StackPredicate, Integer> pair = missing.get(i);
            values.add("predicate#" + i + " x" + pair.right());
        }
        if (missing.size() > MAX_STACKS_PER_SECTION) values.add("..." + (missing.size() - MAX_STACKS_PER_SECTION) + " more");
        return values.toString();
    }

    private static String describeStack(ItemStack stack) {
        if (stack.isEmpty()) return "empty";
        return BuiltInRegistries.ITEM.getKey(stack.getItem()) + "x" + stack.getCount();
    }

    private static String taskName(EntityMaid maid) {
        return maid.getTask() == null ? "null" : maid.getTask().getUid().toString();
    }

    private static String safeLocation(ResourceLocation location) {
        return location == null ? "null" : location.toString();
    }

    private static String safeRecipeType(net.minecraft.world.item.crafting.RecipeType<?> type) {
        return type == null ? "null" : CookTasks.getUID(type);
    }

    private static String blockStateName(ServerLevel level, BlockPos pos) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
    }

    private static String formatPos(BlockPos pos) {
        return pos == null ? "absent" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static String usageSuffix(BlockPos pos, UUID maidId) {
        if (pos == null) return "";
        return " usageCount=" + BlockUsageManager.getExistingUserCount(pos) + " usedByThisMaid=" + BlockUsageManager.isExistingUser(pos, maidId);
    }

    private record DiagnosticContext(
            ServerLevel level,
            EntityMaid maid,
            CookRequest request,
            ICookTask task,
            List<StackPredicate> required,
            List<ItemStack> maidInventory,
            WorkBlockSnapshot workBlock,
            List<Pair<StackPredicate, Integer>> missingFromMaidOnly,
            List<Pair<StackPredicate, Integer>> missingWithWorkInput,
            EnableSnapshot enable,
            DiagnosticState state
    ) {
        private static DiagnosticContext create(ServerLevel level, EntityMaid maid, CookRequest request, MemorySnapshot memories) {
            ICookTask task = request.type == null ? null : CookTasks.getTask(request.type);
            List<StackPredicate> required = new ArrayList<>();
            if (task != null && request.id != null) {
                Optional<? extends RecipeHolder<?>> recipe = level.getRecipeManager().byKey(request.id);
                if (recipe.isPresent()) {
                    @SuppressWarnings("unchecked")
                    RecipeHolder<? extends Recipe<?>> typedRecipe = (RecipeHolder<? extends Recipe<?>>) recipe.get();
                    required.addAll(task.getIngredients(typedRecipe, level));
                    required.addAll(task.getKitchenWares());
                }
            }

            List<ItemStack> maidInventory = ItemHandlerUtils.toStacks(maid.getAvailableInv(false)).stream().map(ItemStack::copy).toList();
            WorkBlockSnapshot workBlock = WorkBlockSnapshot.create(level, maid, task, request, memories);
            List<ItemStack> inventoryWithWorkInput = new ArrayList<>(maidInventory);
            if (workBlock != null && canCountWorkInput(workBlock, maid.getUUID())) inventoryWithWorkInput.addAll(workBlock.currentInput);

            List<Pair<StackPredicate, Integer>> missingFromMaidOnly = missing(required, maidInventory, task, request);
            List<Pair<StackPredicate, Integer>> missingWithWorkInput = missing(required, inventoryWithWorkInput, task, request);
            EnableSnapshot enable = evaluateRequestEnable(level, request, maid.getUUID());

            DiagnosticContext partial = new DiagnosticContext(level, maid, request, task, required, maidInventory, workBlock, missingFromMaidOnly, missingWithWorkInput, enable, null);
            return new DiagnosticContext(level, maid, request, task, required, maidInventory, workBlock, missingFromMaidOnly, missingWithWorkInput, enable, classify(partial, memories));
        }

        private static List<Pair<StackPredicate, Integer>> missing(List<StackPredicate> required, List<ItemStack> stacks, ICookTask task, CookRequest request) {
            if (required.isEmpty() || task == null) return List.of();
            if (task.getType().equals(ModRecipes.STOCKPOT_RECIPE)) {
                return ItemHandlerUtils.filterByCountStockpot(required, stacks, request.remain);
            }
            return ItemHandlerUtils.filterByCount(required, stacks, request.remain);
        }

        private static boolean canCountWorkInput(WorkBlockSnapshot workBlock, UUID maidId) {
            int count = BlockUsageManager.getExistingUserCount(workBlock.pos);
            return count <= 0 || BlockUsageManager.isExistingUser(workBlock.pos, maidId);
        }
    }

    private record MemorySnapshot(
            boolean hasTargetPos,
            BlockPos targetPos,
            String targetPosText,
            int targetType,
            boolean hasCachedWorkBlock,
            BlockPos cachedWorkBlock,
            String cachedWorkBlockText,
            boolean hasChairPos,
            BlockPos chairPos,
            String chairPosText,
            boolean walkTargetPresent,
            boolean lookTargetPresent
    ) {
        private static MemorySnapshot of(EntityMaid maid) {
            Optional<PositionTracker> targetPos = maid.getBrain().getMemory(ModEntities.TARGET_POS.get());
            Optional<PositionTracker> cachedWorkBlock = maid.getBrain().getMemory(ModEntities.CACHED_WORK_BLOCK.get());
            Optional<PositionTracker> chairPos = maid.getBrain().getMemory(ModEntities.CHAIR_POS.get());
            return new MemorySnapshot(
                    targetPos.isPresent(),
                    targetPos.map(PositionTracker::currentBlockPosition).orElse(null),
                    targetPos.map(tracker -> formatPos(tracker.currentBlockPosition())).orElse("absent"),
                    maid.getBrain().getMemory(ModEntities.TARGET_TYPE.get()).orElse(-1),
                    cachedWorkBlock.isPresent(),
                    cachedWorkBlock.map(PositionTracker::currentBlockPosition).orElse(null),
                    cachedWorkBlock.map(tracker -> formatPos(tracker.currentBlockPosition())).orElse("absent"),
                    chairPos.isPresent(),
                    chairPos.map(PositionTracker::currentBlockPosition).orElse(null),
                    chairPos.map(tracker -> formatPos(tracker.currentBlockPosition())).orElse("absent"),
                    maid.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET),
                    maid.getBrain().hasMemoryValue(MemoryModuleType.LOOK_TARGET)
            );
        }
    }

    private record WorkBlockSnapshot(
            BlockPos pos,
            String blockState,
            String blockEntity,
            boolean valid,
            List<ItemStack> currentInput,
            String currentInputError,
            boolean currentInputCoversRequired
    ) {
        private static WorkBlockSnapshot create(ServerLevel level, EntityMaid maid, ICookTask task, CookRequest request, MemorySnapshot memories) {
            if (task == null) return null;
            BlockPos pos = memories.hasTargetPos ? memories.targetPos : memories.cachedWorkBlock;
            if (pos == null) return null;
            BlockEntity blockEntity = level.getBlockEntity(pos);
            boolean valid = task.isValidWorkBlock(level, maid, pos);
            CurrentInputSnapshot inputSnapshot = readCurrentInput(level, maid, task, pos);
            List<ItemStack> currentInput = inputSnapshot.stacks;
            List<StackPredicate> required = new ArrayList<>();
            if (request.id != null) {
                Optional<? extends RecipeHolder<?>> recipe = level.getRecipeManager().byKey(request.id);
                if (recipe.isPresent()) {
                    @SuppressWarnings("unchecked")
                    RecipeHolder<? extends Recipe<?>> typedRecipe = (RecipeHolder<? extends Recipe<?>>) recipe.get();
                    required.addAll(task.getIngredients(typedRecipe, level));
                    required.addAll(task.getKitchenWares());
                }
            }
            boolean coversRequired = ItemHandlerUtils.containsAllRequired(required, currentInput);
            return new WorkBlockSnapshot(
                    pos,
                    blockStateName(level, pos),
                    blockEntity == null ? "none" : blockEntity.getClass().getName(),
                    valid,
                    currentInput,
                    inputSnapshot.error,
                    coversRequired
            );
        }

        private static CurrentInputSnapshot readCurrentInput(ServerLevel level, EntityMaid maid, ICookTask task, BlockPos pos) {
            try {
                return new CurrentInputSnapshot(task.getCurrentInput(level, pos, maid).stream().map(ItemStack::copy).toList(), null);
            } catch (RuntimeException exception) {
                String message = exception.getClass().getSimpleName() + ": " + exception.getMessage();
                com.mastermarisa.maid_restaurant.MaidRestaurant.LOGGER.warn("Cook maid diagnostics could not read current input at {}", pos, exception);
                return new CurrentInputSnapshot(List.of(), message);
            }
        }
    }

    private record CurrentInputSnapshot(List<ItemStack> stacks, String error) {}

    private record EnableSnapshot(boolean enabled, boolean exact, String reason, List<String> notes) {}

    private record DiagnosticState(String state, String reason, String storageStage, String approachStage, String cookingStage) {}
}
