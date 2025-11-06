/*
 *  Copyright (c) 2014, Lukas Tenbrink.
 *  * http://lukas.axxim.net
 */

package ivorius.reccomplex.world.gen.feature.structure.generic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import ivorius.ivtoolkit.tools.IvFileHelper;
import ivorius.reccomplex.RecurrentComplex;
import ivorius.reccomplex.files.loading.FileLoaderRegistry;
import ivorius.reccomplex.files.loading.RCFileSuffix;
import ivorius.reccomplex.files.saving.FileSaverAdapter;
import ivorius.reccomplex.json.NBTToJson;
import ivorius.reccomplex.utils.ByteArrays;
import ivorius.reccomplex.utils.zip.ZipFinder;
import ivorius.reccomplex.utils.zip.IvZips;
import ivorius.reccomplex.world.gen.feature.structure.Structure;
import ivorius.reccomplex.world.gen.feature.structure.StructureRegistry;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Created by lukas on 25.05.14.
 */
public class StructureSaveHandler
{
    public static final StructureSaveHandler INSTANCE = new StructureSaveHandler(RCFileSuffix.STRUCTURE, StructureRegistry.INSTANCE);

    public static final String STRUCTURE_INFO_JSON_FILENAME = "structure.json";
    public static final String WORLD_DATA_NBT_FILENAME = "worldData.nbt";

    public final Gson gson;

    public String suffix;
    public StructureRegistry registry;

    public StructureSaveHandler(String suffix, StructureRegistry registry)
    {
        gson = createGson();
        this.suffix = suffix;
        this.registry = registry;
    }

    public Gson createGson()
    {
        GsonBuilder builder = new GsonBuilder();

        builder.registerTypeAdapter(GenericStructure.class, new GenericStructure.Serializer());
        StructureRegistry.TRANSFORMERS.constructGson(builder);
        StructureRegistry.GENERATION_TYPES.constructGson(builder);
        builder.registerTypeAdapter(GenericVariableDomain.Variable.class, new GenericVariableDomain.Variable.Serializer());

        NBTToJson.registerSafeNBTSerializer(builder);

        return builder.create();
    }

    public GenericStructure fromJSON(String jsonData, NBTTagCompound worldData) throws JsonSyntaxException
    {
        GenericStructure structure = gson.fromJson(jsonData, GenericStructure.class);
        structure.worldDataCompound = worldData;
        return structure;
    }

    public String toJSON(GenericStructure structureInfo)
    {
        return gson.toJson(structureInfo, GenericStructure.class);
    }

    public GenericStructure fromResource(ResourceLocation resourceLocation)
    {
        SanitizedCacheInfo cacheInfo = sanitizedCacheInfo(resourceLocation);

        try (ZipInputStream zipInputStream = new ZipInputStream(IvFileHelper.inputStreamFromResourceLocation(resourceLocation)))
        {
            return fromZip(zipInputStream, cacheInfo);
        }
        catch (Exception ex)
        {
            RecurrentComplex.logger.error("Could not read generic structure " + resourceLocation.toString(), ex);
        }

        return null;
    }

    public GenericStructure fromZip(ZipInputStream zipInputStream) throws IOException
    {
        return fromZip(zipInputStream, null);
    }

    private GenericStructure fromZip(ZipInputStream zipInputStream, @Nullable SanitizedCacheInfo cacheInfo) throws IOException
    {
        ZipFinder finder = new ZipFinder();

        ZipFinder.Result<String> json = finder.bytes(STRUCTURE_INFO_JSON_FILENAME, String::new);
        ZipFinder.Result<byte[]> worldDataBytes = finder.bytes(WORLD_DATA_NBT_FILENAME, bytes -> bytes);

        try
        {
            finder.read(zipInputStream);
            String jsonData = json.get();
            byte[] rawWorldData = worldDataBytes.get();
            NBTTagCompound worldData = CompressedStreamTools.readCompressed(new ByteArrayInputStream(rawWorldData));

            GenericStructure structure = fromJSON(jsonData, worldData);
            NBTTagCompound sanitized = worldData;

            if (cacheInfo != null)
            {
                Path cachePath = sanitizedCachePath(cacheInfo);
                String hash = StructureWorldDataSanitizer.computeHash(rawWorldData);
                NBTTagCompound cached = null;

                try
                {
                    cached = StructureWorldDataSanitizer.readCache(cachePath, hash);
                }
                catch (IOException e)
                {
                    RecurrentComplex.logger.warn("Failed to read sanitized structure cache {}", cachePath, e);
                }

                if (cached != null)
                {
                    sanitized = cached;
                }
                else
                {
                    StructureWorldDataSanitizer.SanitizationResult result = StructureWorldDataSanitizer.sanitize(worldData);
                    if (result != null)
                    {
                        sanitized = result.getWorldData();
                        try
                        {
                            StructureWorldDataSanitizer.writeCache(cachePath, hash, result);
                        }
                        catch (IOException e)
                        {
                            RecurrentComplex.logger.warn("Failed to write sanitized structure cache {}", cachePath, e);
                        }
                    }
                }

                structure.applySanitizedWorldData(sanitized, cachePath, hash);
            }
            else
            {
                StructureWorldDataSanitizer.SanitizationResult result = StructureWorldDataSanitizer.sanitize(worldData);
                NBTTagCompound sanitizedWorldData = result != null ? result.getWorldData() : sanitized;
                structure.applySanitizedWorldData(sanitizedWorldData, null, null);
            }

            return structure;
        }
        catch (IOException | ZipFinder.MissingEntryException e)
        {
            throw new IOException("Error loading structure", e);
        }
    }

    public void toZip(Structure<?> structure, ZipOutputStream zipOutputStream) throws IOException
    {
        GenericStructure copy = structure.copyAsGenericStructure();
        Objects.requireNonNull(copy);

        IvZips.addZipEntry(zipOutputStream, STRUCTURE_INFO_JSON_FILENAME, toJSON(copy).getBytes());
        IvZips.addZipEntry(zipOutputStream, WORLD_DATA_NBT_FILENAME, ByteArrays.toByteArray(s -> CompressedStreamTools.writeCompressed(copy.worldDataCompound, s)));

        zipOutputStream.close();
    }

    private SanitizedCacheInfo sanitizedCacheInfo(ResourceLocation location)
    {
        String pack = derivePackFromResource(location);
        String name = stripExtension(location.getResourcePath());
        return new SanitizedCacheInfo(pack, name);
    }

    private SanitizedCacheInfo sanitizedCacheInfo(Path path, String name)
    {
        return new SanitizedCacheInfo(derivePackFromPath(path), name);
    }

    private Path sanitizedCachePath(SanitizedCacheInfo info)
    {
        Path root = new File(RecurrentComplex.proxy.getDataDirectory(), "config/rc_cache/sanitized").toPath();
        Path path = root;

        if (info.pack != null && !info.pack.isEmpty())
        {
            for (String segment : info.pack.split("/"))
            {
                String sanitized = sanitizeSegment(segment);
                if (!sanitized.isEmpty())
                    path = path.resolve(sanitized);
            }
        }

        String sanitizedName = sanitizeSegment(info.name);
        if (sanitizedName.isEmpty())
            sanitizedName = "_";

        return path.resolve(sanitizedName + ".nbt");
    }

    private String derivePackFromResource(ResourceLocation location)
    {
        String[] rawParts = location.getResourcePath().split("/");
        List<String> parts = new ArrayList<>();
        for (String part : rawParts)
        {
            if (!part.isEmpty())
                parts.add(part);
        }

        int structuresIndex = -1;
        for (int i = parts.size() - 1; i >= 0; i--)
        {
            if ("structures".equals(parts.get(i)))
            {
                structuresIndex = i;
                break;
            }
        }

        String relative = "";
        if (structuresIndex >= 0 && structuresIndex + 1 < parts.size() - 1)
            relative = joinPath(parts.subList(structuresIndex + 1, parts.size() - 1));
        if (relative.isEmpty() && parts.size() > 1)
            relative = joinPath(parts.subList(0, parts.size() - 1));

        String domain = location.getResourceDomain();
        return relative.isEmpty() ? domain : domain + "/" + relative;
    }

    private String derivePackFromPath(Path path)
    {
        Path parent = path.getParent();
        if (parent == null)
            return "";

        List<String> parts = new ArrayList<>();
        for (Path element : parent.normalize())
            parts.add(element.toString());

        int assetsIndex = parts.indexOf("assets");
        String domain = (assetsIndex >= 0 && assetsIndex + 1 < parts.size()) ? parts.get(assetsIndex + 1) : "";

        int structuresIndex = -1;
        for (int i = parts.size() - 1; i >= 0; i--)
        {
            if ("structures".equals(parts.get(i)))
            {
                structuresIndex = i;
                break;
            }
        }

        String relative = "";
        if (structuresIndex >= 0 && structuresIndex + 1 < parts.size())
            relative = joinPath(parts.subList(structuresIndex + 1, parts.size()));
        if (relative.isEmpty() && !parts.isEmpty())
            relative = parts.get(parts.size() - 1);

        if (!domain.isEmpty())
            return relative.isEmpty() ? domain : domain + "/" + relative;

        return relative;
    }

    private String stripExtension(String filename)
    {
        int slash = filename.lastIndexOf('/');
        String name = slash >= 0 ? filename.substring(slash + 1) : filename;
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }

    private String sanitizeSegment(String raw)
    {
        if (raw == null)
            return "";

        StringBuilder builder = new StringBuilder(raw.length());
        for (char c : raw.toCharArray())
        {
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|')
                builder.append('_');
            else
                builder.append(c);
        }

        return builder.toString().trim();
    }

    private String joinPath(List<String> segments)
    {
        return String.join("/", segments);
    }

    private static class SanitizedCacheInfo
    {
        final String pack;
        final String name;

        SanitizedCacheInfo(String pack, String name)
        {
            this.pack = pack == null ? "" : pack;
            this.name = name == null ? "" : name;
        }
    }

    public class Loader extends FileLoaderRegistry<GenericStructure>
    {
        public Loader()
        {
            super(StructureSaveHandler.this.suffix, StructureSaveHandler.this.registry);
        }

        @Override
        public GenericStructure read(Path path, String name) throws Exception
        {
            SanitizedCacheInfo cacheInfo = sanitizedCacheInfo(path, name);
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(path)))
            {
                return fromZip(zip, cacheInfo);
            }
        }
    }

    public class Saver extends FileSaverAdapter<Structure<?>>
    {
        public Saver(String id)
        {
            super(id, StructureSaveHandler.this.suffix, StructureSaveHandler.this.registry);
        }

        @Override
        public void saveFile(Path path, Structure<?> structure) throws Exception
        {
            try (ZipOutputStream zipOutputStream = new ZipOutputStream(Files.newOutputStream(path)))
            {
                toZip(structure, zipOutputStream);
            }
        }
    }
}
