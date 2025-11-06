/*
 *  Copyright (c) 2014, Lukas Tenbrink.
 *  * http://lukas.axxim.net
 */

package ivorius.reccomplex.world.gen.feature.structure.generic;

import com.google.gson.*;
import ivorius.ivtoolkit.blocks.BlockAreas;
import ivorius.ivtoolkit.blocks.IvBlockCollection;
import ivorius.ivtoolkit.blocks.IvMutableBlockPos;
import ivorius.ivtoolkit.tools.IvWorldData;
import ivorius.ivtoolkit.tools.NBTCompoundObjects;
import ivorius.ivtoolkit.transform.Mover;
import ivorius.ivtoolkit.transform.PosTransformer;
import ivorius.ivtoolkit.world.chunk.gen.StructureBoundingBoxes;
import ivorius.reccomplex.RecurrentComplex;
import ivorius.reccomplex.json.JsonUtils;
import ivorius.reccomplex.json.NBTToJson;
import ivorius.reccomplex.nbt.NBTStorable;
import ivorius.reccomplex.temp.RCMover;
import ivorius.reccomplex.temp.RCPosTransformer;
import ivorius.reccomplex.utils.ItemHandlers;
import ivorius.reccomplex.utils.RCStructureBoundingBoxes;
import ivorius.reccomplex.utils.accessor.RCAccessorEntity;
import ivorius.reccomplex.utils.accessor.RCAccessorWorldServer;
import ivorius.reccomplex.utils.expression.DependencyExpression;
import ivorius.reccomplex.world.gen.feature.structure.Structure;
import ivorius.reccomplex.world.gen.feature.structure.StructureRegistry;
import ivorius.reccomplex.world.gen.feature.structure.Structures;
import ivorius.reccomplex.world.gen.feature.structure.VariableDomain;
import ivorius.reccomplex.world.gen.feature.structure.context.StructureLoadContext;
import ivorius.reccomplex.world.gen.feature.structure.context.StructurePrepareContext;
import ivorius.reccomplex.world.gen.feature.structure.context.StructureSpawnContext;
import ivorius.reccomplex.world.gen.feature.structure.generic.generation.GenerationType;
import ivorius.reccomplex.world.gen.feature.structure.generic.generation.MazeGeneration;
import ivorius.reccomplex.world.gen.feature.structure.generic.generation.NaturalGeneration;
import ivorius.reccomplex.world.gen.feature.structure.generic.transformers.RunTransformer;
import ivorius.reccomplex.world.gen.feature.structure.generic.transformers.Transformer;
import ivorius.reccomplex.world.gen.feature.structure.generic.transformers.TransformerGenerationBehavior;
import ivorius.reccomplex.world.gen.feature.structure.generic.transformers.TransformerMulti;
import ivorius.reccomplex.world.gen.feature.RCWorldgenMonitor;
import ivorius.reccomplex.world.storage.loot.LootGenerationHandler;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import net.minecraft.util.text.TextComponentBase;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.structure.StructureBoundingBox;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Created by lukas on 24.05.14.
 */
public class GenericStructure implements Structure<GenericStructure.InstanceData>, Cloneable
{
    public static final int LATEST_VERSION = 3;
    public static final int MAX_GENERATING_LAYERS = 30;

    public final List<GenerationType> generationTypes = new ArrayList<>();
    public TransformerMulti transformer = new TransformerMulti();
    public final DependencyExpression dependencies = new DependencyExpression();

    public NBTTagCompound worldDataCompound;
    protected transient Path sanitizedWorldDataPath;
    protected transient String sanitizedWorldDataHash;
    protected transient boolean sanitizedWorldDataResolved;

    public boolean rotatable;
    public boolean mirrorable;
    public boolean blocking;

    public GenericVariableDomain variableDomain = new GenericVariableDomain();

    public Metadata metadata = new Metadata();

    public JsonObject customData;

    public static GenericStructure createDefaultStructure()
    {
        GenericStructure genericStructureInfo = new GenericStructure();
        genericStructureInfo.rotatable = true;
        genericStructureInfo.mirrorable = true;
        genericStructureInfo.blocking = true;

        genericStructureInfo.transformer.getData().setPreset("structure");
        genericStructureInfo.generationTypes.add(new NaturalGeneration());

        return genericStructureInfo;
    }

    private static double[] getEntityPos(NBTTagCompound compound)
    {
        NBTTagList pos = compound.getTagList("Pos", Constants.NBT.TAG_DOUBLE);
        return new double[]{pos.getDoubleAt(0), pos.getDoubleAt(1), pos.getDoubleAt(2)};
    }

    public static void setBlock(@Nonnull StructureSpawnContext context, int[] areaSize, @Nonnull BlockPos worldPos, @Nonnull IBlockState state, @Nonnull Supplier<NBTTagCompound> tileEntity)
    {
        WorldServer world = context.environment.world;
        if (context.setBlock(worldPos, state, 2)) {
            NBTTagCompound tileEntityCompound = tileEntity.get(); // Wants to set

            if (tileEntityCompound != null && world.getBlockState(worldPos).getBlock() == state.getBlock()) {
                TileEntity worldTileEntity = world.getTileEntity(worldPos);

                if (worldTileEntity != null) // Do set
                {
                    tileEntityCompound = RCMover.setTileEntityPos(tileEntityCompound, worldPos);
                    worldTileEntity.readFromNBT(tileEntityCompound);

                    RCPosTransformer.transformAdditionalData(worldTileEntity, context.transform, areaSize);
                    RCMover.setAdditionalDataPos(worldTileEntity, worldPos);

                    generateTileEntityContents(context, worldTileEntity);
                }
            }
        }
    }

    public static void generateEntityContents(@Nonnull StructureSpawnContext context, Entity entity)
    {
        if (!context.generateAsSource && ItemHandlers.hasModifiable(entity, null))
            LootGenerationHandler.generateAllTags(context, ItemHandlers.getModifiable(entity, null));
    }

    public static void generateTileEntityContents(@Nonnull StructureSpawnContext context, TileEntity tileEntity)
    {
        if (!context.generateAsSource && ItemHandlers.hasModifiable(tileEntity, null))
            LootGenerationHandler.generateAllTags(context, ItemHandlers.getModifiable(tileEntity, null));
    }

    @Nonnull
    @Override
    public int[] size()
    {
        return Structures.size(resolvedWorldDataCompound(), new int[]{0, 0, 0});
    }

    @Override
    public boolean isRotatable()
    {
        return rotatable;
    }

    @Override
    public boolean isMirrorable()
    {
        return mirrorable;
    }

    @Override
    public boolean isBlocking()
    {
        return blocking;
    }

    @Override
    public void generate(@Nonnull final StructureSpawnContext context, @Nonnull InstanceData instanceData, @Nonnull TransformerMulti foreignTransformer)
    {
        WorldServer world = context.environment.world;
        IvWorldData worldData = constructWorldData();
        boolean asSource = context.generateAsSource;

        RunTransformer transformer = getRunTransformer(instanceData, foreignTransformer, asSource);

        instanceData.variableDomain.fill(context.environment.variables);

        // The world initializes the block event array after it generates the world - in the constructor
        // This hackily sets the field to a temporary value. Yay.
        RCAccessorWorldServer.ensureBlockEventArray(world); // Hax

        IvBlockCollection blockCollection = worldData.blockCollection;
        int[] areaSize = new int[]{blockCollection.width, blockCollection.height, blockCollection.length};
        BlockPos origin = StructureBoundingBoxes.min(context.boundingBox);

        Map<BlockPos, NBTTagCompound> tileEntityCompounds = new HashMap<>();
        for (NBTTagCompound tileEntityCompound : worldData.tileEntities) {
            BlockPos src = RCMover.getTileEntityPos(tileEntityCompound);
            tileEntityCompounds.put(src, tileEntityCompound);
        }

        if (transformer != null)
            transformer.transformer.transform(transformer.instanceData, Transformer.Phase.BEFORE, context, worldData, transformer);

        StructureBoundingBox relevantSourceArea = context.sourceIntersection(BlockAreas.toBoundingBox(blockCollection.area()));

        if (relevantSourceArea != null) // Why did we get asked to generate again?
        {
            context.freezeHeightMap(relevantSourceArea);

            BlockPos.MutableBlockPos worldPos = new BlockPos.MutableBlockPos();
            for (int pass = 0; pass < 2; pass++) {
                for (BlockPos sourcePos : RCStructureBoundingBoxes.mutablePositions(relevantSourceArea)) {
                    IvMutableBlockPos.add(context.transform.applyOn(sourcePos, worldPos, areaSize), origin);

                    if (context.includesComplex(worldPos)) {
                        IBlockState state = PosTransformer.transformBlockState(blockCollection.getBlockState(sourcePos), context.transform);

                        if (pass == getPass(state) && (transformer == null || !transformer.transformer.skipGeneration(transformer.instanceData, context, worldPos, state, worldData, sourcePos))) {
                            setBlock(context, areaSize, worldPos, state, () -> tileEntityCompounds.get(sourcePos));
                        }
                    }
                }
            }

            context.meltHeightMap();
        }

        if (transformer != null)
            transformer.transformer.transform(transformer.instanceData, Transformer.Phase.AFTER, context, worldData, transformer);

        Map<ResourceLocation, Integer> skippedEntities = new HashMap<>();
        Map<String, Integer> invalidEntityIds = new HashMap<>();

        for (NBTTagCompound entityCompound : worldData.entities) {
            double[] transformedEntityPos = context.transform.applyOn(getEntityPos(entityCompound), areaSize);
            if (context.includes(new Vec3i(transformedEntityPos[0] + origin.getX(), transformedEntityPos[1] + origin.getY(), transformedEntityPos[2] + origin.getZ()))) {
                String entityIdString = entityCompound.getString("id");

                if (!ResourceLocation.isValidResourceLocation(entityIdString)) {
                    invalidEntityIds.merge(entityIdString.isEmpty() ? "<empty>" : entityIdString, 1, Integer::sum);
                    continue;
                }

                ResourceLocation entityId;
                try {
                    entityId = new ResourceLocation(entityIdString);
                } catch (RuntimeException e) {
                    entityId = null;
                }

                if (entityId == null) {
                    invalidEntityIds.merge(entityIdString.isEmpty() ? "<empty>" : entityIdString, 1, Integer::sum);
                    continue;
                }

                if (MissingEntityCache.isMissing(entityId)) {
                    MissingEntityCache.recordSkip(entityId);
                    skippedEntities.merge(entityId, 1, Integer::sum);
                    continue;
                }

                Entity entity = EntityList.createEntityFromNBT(entityCompound, world);

                if (entity != null) {
                    PosTransformer.transformEntityPos(entity, context.transform, areaSize);
                    Mover.moveEntity(entity, origin);

                    RCAccessorEntity.setEntityUniqueID(entity, UUID.randomUUID());
                    generateEntityContents(context, entity);
                    world.spawnEntity(entity);
                }
                else {
                    if (!ForgeRegistries.ENTITIES.containsKey(entityId)) {
                        MissingEntityCache.recordSkip(entityId);
                        skippedEntities.merge(entityId, 1, Integer::sum);
                    }
                    else {
                        RecurrentComplex.logger.warn(
                                "Failed to instantiate registered entity {} while generating structure at {} in dimension {}; skipping corrupted entity NBT.",
                                entityId,
                                context.boundingBox,
                                world.provider.getDimension());
                    }
                }
            }
        }

        MissingEntityReporter.schedule(context, skippedEntities, invalidEntityIds);
    }

    @Nullable
    public RunTransformer getRunTransformer(@Nonnull InstanceData instanceData, @Nonnull TransformerMulti foreignTransformer, boolean asSource)
    {
        if (asSource)
            return null;

        if (instanceData.transformerData == null || instanceData.foreignTransformerData == null)
            throw new IllegalStateException();

        TransformerGenerationBehavior transformerGenerationBehavior = new TransformerGenerationBehavior();

        TransformerMulti fused = TransformerMulti.fuse(Arrays.asList(
                transformerGenerationBehavior,
                this.transformer,
                foreignTransformer
        ));
        fused.setID("FusedStructureTransformer");

        return new RunTransformer(fused, fused.fuseDatas(Arrays.asList(
                instanceData.transformerGenerationBehavior,
                instanceData.transformerData,
                instanceData.foreignTransformerData
        )));
    }

    private static final class MissingEntityCache
    {
        private static final Set<ResourceLocation> MISSING_ENTITY_IDS = Collections.newSetFromMap(new ConcurrentHashMap<>());
        private static final ConcurrentMap<ResourceLocation, LongAdder> MISSING_ENTITY_COUNTS = new ConcurrentHashMap<>();

        private MissingEntityCache()
        {
        }

        public static boolean isMissing(ResourceLocation id)
        {
            return MISSING_ENTITY_IDS.contains(id);
        }

        public static void recordSkip(ResourceLocation id)
        {
            MISSING_ENTITY_IDS.add(id);
            MISSING_ENTITY_COUNTS.computeIfAbsent(id, key -> new LongAdder()).increment();
        }

        public static long total(ResourceLocation id)
        {
            LongAdder adder = MISSING_ENTITY_COUNTS.get(id);
            return adder != null ? adder.sum() : 0L;
        }
    }

    private static final class MissingEntityReporter
    {
        private MissingEntityReporter()
        {
        }

        public static void schedule(StructureSpawnContext context, Map<ResourceLocation, Integer> skippedEntities, Map<String, Integer> invalidEntityIds)
        {
            if (skippedEntities.isEmpty() && invalidEntityIds.isEmpty())
                return;

            WorldServer world = context.environment.world;
            StructureBoundingBox boundingBox = context.boundingBox;
            Map<ResourceLocation, Integer> skippedSnapshot = new LinkedHashMap<>(skippedEntities);
            Map<String, Integer> invalidSnapshot = new LinkedHashMap<>(invalidEntityIds);
            String location = boundingBox.toString();
            int dimension = world.provider.getDimension();

            RCWorldgenMonitor.report(() -> {
                if (!skippedSnapshot.isEmpty()) {
                    long totalSkipped = skippedSnapshot.values().stream().mapToLong(Integer::longValue).sum();
                    String details = skippedSnapshot.entrySet().stream()
                            .map(entry -> entry.getKey() + "=" + entry.getValue() + " (total=" + MissingEntityCache.total(entry.getKey()) + ")")
                            .collect(Collectors.joining(", "));
                    RecurrentComplex.logger.warn("Skipped {} entity instantiation(s) for missing IDs while generating structure at {} in dimension {}: {}", totalSkipped, location, dimension, details);
                }

                if (!invalidSnapshot.isEmpty()) {
                    long totalInvalid = invalidSnapshot.values().stream().mapToLong(Integer::longValue).sum();
                    String details = invalidSnapshot.entrySet().stream()
                            .map(entry -> entry.getKey() + "=" + entry.getValue())
                            .collect(Collectors.joining(", "));
                    RecurrentComplex.logger.warn("Encountered {} invalid entity ID(s) while generating structure at {} in dimension {}: {}", totalInvalid, location, dimension, details);
                }
            });
        }
    }

    @Nullable
    @Override
    public InstanceData prepareInstanceData(@Nonnull StructurePrepareContext context, @Nonnull TransformerMulti foreignTransformer)
    {
        InstanceData instanceData = new InstanceData();

        if (!context.generateAsSource) {
            IvWorldData worldData = constructWorldData();

            context.environment.variables.fill(instanceData.variableDomain); // Fill with already set vars
            variableDomain.fill(instanceData.variableDomain, context.environment, context.random); // Determine the rest

            TransformerGenerationBehavior transformerGenerationBehavior = new TransformerGenerationBehavior();

            TransformerMulti fused = TransformerMulti.fuse(Arrays.asList(
                    transformerGenerationBehavior,
                    this.transformer,
                    foreignTransformer
            ));

            TransformerMulti.InstanceData fusedDatas = fused.prepareInstanceData(context, worldData);

            instanceData.transformerGenerationBehavior = (TransformerGenerationBehavior.InstanceData)
                    fusedDatas.pairedTransformers.stream()
                            .filter(t -> t.getLeft() instanceof TransformerGenerationBehavior)
                            .map(Pair::getRight).findFirst().get();

            instanceData.transformerData = (TransformerMulti.InstanceData)
                    fusedDatas.pairedTransformers.stream()
                            .filter(t -> t.getLeft() == transformer)
                            .map(Pair::getRight).findFirst().get();

            instanceData.foreignTransformerData = (TransformerMulti.InstanceData)
                    fusedDatas.pairedTransformers.stream()
                            .filter(t -> t.getLeft() == foreignTransformer)
                            .map(Pair::getRight).findFirst().get();

            if (context.generateMaturity.isSuggest() && !fused.mayGenerate(fusedDatas, context, worldData))
                return null;

            RunTransformer runTransformer = new RunTransformer(fused, fusedDatas);
            fused.configureInstanceData(fusedDatas, context, worldData, runTransformer);
        }

        return instanceData;
    }

    @Nonnull
    @Override
    public InstanceData loadInstanceData(@Nonnull StructureLoadContext context, @Nonnull final NBTBase nbt, @Nonnull TransformerMulti transformer)
    {
        InstanceData instanceData = new InstanceData();
        instanceData.readFromNBT(context, nbt, this.transformer, transformer, constructWorldData());
        return instanceData;
    }

    public IvWorldData constructWorldData()
    {
        NBTTagCompound data = resolvedWorldDataCompound();
        return new IvWorldData(data != null ? data : new NBTTagCompound(), RecurrentComplex.specialRegistry.itemHidingMode());
    }

    void applySanitizedWorldData(@Nullable NBTTagCompound sanitizedWorldData, @Nullable Path cachePath, @Nullable String sourceHash)
    {
        if (sanitizedWorldData != null)
            worldDataCompound = sanitizedWorldData;

        sanitizedWorldDataPath = cachePath;
        sanitizedWorldDataHash = sourceHash;
        sanitizedWorldDataResolved = sanitizedWorldData != null || cachePath == null;
    }

    private NBTTagCompound resolvedWorldDataCompound()
    {
        if (!sanitizedWorldDataResolved)
        {
            sanitizedWorldDataResolved = true;
            if (sanitizedWorldDataPath != null && sanitizedWorldDataHash != null)
            {
                try
                {
                    NBTTagCompound cached = StructureWorldDataSanitizer.readCache(sanitizedWorldDataPath, sanitizedWorldDataHash);
                    if (cached != null)
                        worldDataCompound = cached;
                }
                catch (IOException e)
                {
                    RecurrentComplex.logger.warn("Failed to read sanitized structure cache {}", sanitizedWorldDataPath, e);
                }
            }
        }

        return worldDataCompound;
    }

    @Nonnull
    @Override
    public <I extends GenerationType> List<I> generationTypes(@Nonnull Class<? extends I> clazz)
    {
        //noinspection unchecked
        return generationTypes.stream().filter(info -> clazz.isAssignableFrom(info.getClass())).map(info -> (I) info).collect(Collectors.toList());
    }

    @Override
    public GenerationType generationType(@Nonnull String id)
    {
        for (GenerationType info : generationTypes) {
            if (Objects.equals(info.id(), id))
                return info;
        }

        return null;
    }

    private int getPass(IBlockState state)
    {
        return (state.isNormalCube() || state.getMaterial() == Material.AIR) ? 0 : 1;
    }

    @Override
    @Nonnull
    public GenericStructure copyAsGenericStructure()
    {
        return copy();
    }

    @Override
    public boolean areDependenciesResolved()
    {
        return dependencies.test(RecurrentComplex.saver);
    }

    @Nullable
    @Override
    public IvBlockCollection blockCollection()
    {
        return constructWorldData().blockCollection;
    }

    @Nonnull
    @Override
    public GenericVariableDomain declaredVariables()
    {
        return variableDomain;
    }

    @Override
    public String toString()
    {
        String s = StructureRegistry.INSTANCE.id(this);
        return s != null ? s : "Generic Structure";
    }

    @Override
    public List<TextComponentBase> instanceDataInfo(InstanceData instanceData)
    {
        if (!instanceData.variableDomain.all().isEmpty())
            return Collections.singletonList(new TextComponentString(instanceData.variableDomain.all().toString()));
        return Collections.emptyList();
    }

    public GenericStructure copy()
    {
        NBTTagCompound resolved = resolvedWorldDataCompound();
        return StructureSaveHandler.INSTANCE.fromJSON(StructureSaveHandler.INSTANCE.toJSON(this),
                resolved != null ? resolved.copy() : null);
    }

    public static class Serializer implements JsonDeserializer<GenericStructure>, JsonSerializer<GenericStructure>
    {
        public GenericStructure deserialize(JsonElement jsonElement, Type par2Type, JsonDeserializationContext context)
        {
            JsonObject jsonObject = JsonUtils.asJsonObject(jsonElement, "status");
            GenericStructure structureInfo = new GenericStructure();

            Integer version;
            if (jsonObject.has("version")) {
                version = JsonUtils.getInt(jsonObject, "version");
            }
            else {
                version = LATEST_VERSION;
                RecurrentComplex.logger.warn("Structure JSON missing 'version', using latest (" + LATEST_VERSION + ")");
            }

            GenerationType.idRandomizers.push(new Random(0xDEADBEEF)); // Legacy for missing IDs
            if (jsonObject.has("generationInfos"))
                Collections.addAll(structureInfo.generationTypes, context.<GenerationType[]>deserialize(jsonObject.get("generationInfos"), GenerationType[].class));
            GenerationType.idRandomizers.pop();

            if (version == 1)
                structureInfo.generationTypes.add(NaturalGeneration.deserializeFromVersion1(jsonObject, context));

            {
                // Legacy version 2
                if (jsonObject.has("naturalGenerationInfo"))
                    structureInfo.generationTypes.add(NaturalGeneration.getGson().fromJson(jsonObject.get("naturalGenerationInfo"), NaturalGeneration.class));

                if (jsonObject.has("mazeGenerationInfo"))
                    structureInfo.generationTypes.add(MazeGeneration.getGson().fromJson(jsonObject.get("mazeGenerationInfo"), MazeGeneration.class));
            }

            Transformer.idRandomizers.push(new Random(0xDEADBEEF)); // Legacy for missing IDs
            if (jsonObject.has("transformer"))
                structureInfo.transformer = context.deserialize(jsonObject.get("transformer"), TransformerMulti.class);
            else if (jsonObject.has("transformers")) // Legacy
                Collections.addAll(structureInfo.transformer.getTransformers(), context.<Transformer[]>deserialize(jsonObject.get("transformers"), Transformer[].class));
            else if (jsonObject.has("blockTransformers")) // Legacy
                Collections.addAll(structureInfo.transformer.getTransformers(), context.<Transformer[]>deserialize(jsonObject.get("blockTransformers"), Transformer[].class));
            Transformer.idRandomizers.pop();

            structureInfo.rotatable = JsonUtils.getBoolean(jsonObject, "rotatable", false);
            structureInfo.mirrorable = JsonUtils.getBoolean(jsonObject, "mirrorable", false);
            structureInfo.blocking = JsonUtils.getBoolean(jsonObject, "blocking", true);

            structureInfo.variableDomain = context.deserialize(JsonUtils.getJsonObject(jsonObject, "variableDomain", new JsonObject()), GenericVariableDomain.class);

            if (jsonObject.has("dependencyExpression"))
                structureInfo.dependencies.setExpression(JsonUtils.getString(jsonObject, "dependencyExpression"));
            else if (jsonObject.has("dependencies")) // Legacy
                structureInfo.dependencies.setExpression(DependencyExpression.ofMods(context.<String[]>deserialize(jsonObject.get("dependencies"), String[].class)));

            if (jsonObject.has("worldData"))
                structureInfo.worldDataCompound = context.deserialize(jsonObject.get("worldData"), NBTTagCompound.class);
            else if (jsonObject.has("worldDataBase64"))
                structureInfo.worldDataCompound = NBTToJson.getNBTFromBase64(JsonUtils.getString(jsonObject, "worldDataBase64"));
            // And else it is taken out for packet size, or stored in the zip

            if (jsonObject.has("metadata")) // Else, use default
                structureInfo.metadata = context.deserialize(jsonObject.get("metadata"), Metadata.class);

            structureInfo.customData = JsonUtils.getJsonObject(jsonObject, "customData", new JsonObject());

            return structureInfo;
        }

        public JsonElement serialize(GenericStructure structureInfo, Type par2Type, JsonSerializationContext context)
        {
            JsonObject jsonObject = new JsonObject();

            jsonObject.addProperty("version", LATEST_VERSION);

            jsonObject.add("generationInfos", context.serialize(structureInfo.generationTypes));
            jsonObject.add("transformer", context.serialize(structureInfo.transformer));

            jsonObject.addProperty("rotatable", structureInfo.rotatable);
            jsonObject.addProperty("mirrorable", structureInfo.mirrorable);
            jsonObject.addProperty("blocking", structureInfo.blocking);

            jsonObject.add("variableDomain", context.serialize(structureInfo.variableDomain));

            jsonObject.add("dependencyExpression", context.serialize(structureInfo.dependencies.getExpression()));

            if (!RecurrentComplex.USE_ZIP_FOR_STRUCTURE_FILES && structureInfo.worldDataCompound != null) {
                if (RecurrentComplex.USE_JSON_FOR_NBT)
                    jsonObject.add("worldData", context.serialize(structureInfo.worldDataCompound));
                else
                    jsonObject.addProperty("worldDataBase64", NBTToJson.getBase64FromNBT(structureInfo.worldDataCompound));
            }

            jsonObject.add("metadata", context.serialize(structureInfo.metadata));
            jsonObject.add("customData", structureInfo.customData);

            return jsonObject;
        }
    }

    public static class InstanceData implements NBTStorable
    {
        public static final String KEY_TRANSFORMER = "transformer";
        public static final String KEY_FOREIGN_TRANSFORMER = "foreignTransformer";

        public final VariableDomain variableDomain = new VariableDomain();

        public TransformerMulti.InstanceData transformerData;
        public TransformerMulti.InstanceData foreignTransformerData;

        public TransformerGenerationBehavior.InstanceData transformerGenerationBehavior = new TransformerGenerationBehavior.InstanceData();

        public void readFromNBT(StructureLoadContext context, NBTBase nbt, TransformerMulti transformer, @Nonnull TransformerMulti foreignTransformer, IvWorldData worldData)
        {
            NBTTagCompound compound = nbt instanceof NBTTagCompound ? (NBTTagCompound) nbt : new NBTTagCompound();

            variableDomain.readFromNBT(compound.getCompoundTag("variables"));

            if (compound.hasKey(KEY_TRANSFORMER))
                transformerData = transformer.loadInstanceData(context, compound.getTag(KEY_TRANSFORMER));
            if (compound.hasKey(KEY_FOREIGN_TRANSFORMER))
                foreignTransformerData = foreignTransformer.loadInstanceData(context, compound.getTag(KEY_FOREIGN_TRANSFORMER));

            transformerGenerationBehavior.readFromNBT(context, nbt, worldData);
        }

        @Override
        public NBTBase writeToNBT()
        {
            NBTTagCompound compound = (NBTTagCompound) transformerGenerationBehavior.writeToNBT();

            NBTCompoundObjects.writeTo(compound, "variables", variableDomain);

            if (transformerData != null)
                compound.setTag(KEY_TRANSFORMER, transformerData.writeToNBT());
            if (foreignTransformerData != null)
                compound.setTag(KEY_FOREIGN_TRANSFORMER, foreignTransformerData.writeToNBT());

            return compound;
        }
    }
}
