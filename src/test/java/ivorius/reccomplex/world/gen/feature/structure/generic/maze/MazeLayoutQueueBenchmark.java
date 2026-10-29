package ivorius.reccomplex.world.gen.feature.structure.generic.maze;

import com.google.gson.*;
import ivorius.ivtoolkit.math.AxisAlignedTransform2D;
import ivorius.ivtoolkit.maze.components.*;
import ivorius.reccomplex.RCConfig;
import ivorius.reccomplex.RecurrentComplex;
import ivorius.reccomplex.files.loading.LeveledRegistry;
import ivorius.reccomplex.world.gen.feature.structure.*;
import ivorius.reccomplex.world.gen.feature.structure.generic.*;
import ivorius.reccomplex.world.gen.feature.structure.generic.generation.*;
import ivorius.reccomplex.world.gen.feature.structure.generic.maze.*;
import ivorius.reccomplex.world.gen.feature.structure.generic.maze.rules.*;
import ivorius.reccomplex.world.gen.feature.structure.generic.maze.rules.saved.*;
import ivorius.reccomplex.world.gen.script.WorldScriptMazeGenerator;
import net.minecraft.init.Bootstrap;
import net.minecraft.nbt.*;
import net.minecraft.world.WorldServer;
import org.apache.logging.log4j.LogManager;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import java.util.stream.*;

/** Isolated investigation. Uses bundled layout definitions and production layout solver;
 * never prepares room blocks, generates chunks, or writes a world. */
public class MazeLayoutQueueBenchmark {
 static final Path ROOT=Paths.get("src/main/resources/assets/reccomplex/structures/active/structures");
 static final Gson VARS=new GsonBuilder().registerTypeAdapter(GenericVariableDomain.Variable.class,new GenericVariableDomain.Variable.Serializer()).create();
 static NBTTagCompound worldData(ZipFile zip) throws Exception {
  try(InputStream in=zip.getInputStream(zip.getEntry("worldData.nbt"))) {return CompressedStreamTools.readCompressed(in);}
 }
 static void scripts(NBTBase n,List<NBTTagCompound> out) {
  if(n instanceof NBTTagCompound){
   NBTTagCompound c=(NBTTagCompound)n;
   if(c.hasKey("mazeID")&&c.hasKey("roomSize"))out.add(c);
   for(String key:c.getKeySet())scripts(c.getTag(key),out);
  } else if(n instanceof NBTTagList){
   NBTTagList l=(NBTTagList)n;for(int i=0;i<l.tagCount();i++)scripts(l.get(i),out);
  }
 }
 public static void main(String[] args) throws Exception {
  Bootstrap.register();RecurrentComplex.logger=LogManager.getLogger("MazeInvestigation");
  MazeRuleRegistry.INSTANCE.register("connectall",MazeRuleConnectAll.class);
  MazeRuleRegistry.INSTANCE.register("connect",MazeRuleConnect.class);
  RCConfig.mazeTimeout=20000; RCConfig.mazePlacementReversesPerRoom=3;
  RCConfig.mazeLayoutQueue=Boolean.getBoolean("maze.queue");RCConfig.mazeLayoutQueueSize=2;FreshMazeLayouts.clear();
  List<Path> paths;
  try(Stream<Path> s=Files.walk(ROOT)){paths=s.filter(p->p.toString().endsWith(".rcst")).sorted().collect(Collectors.toList());}
  int registered=0;
  for(Path path:paths)try(ZipFile zip=new ZipFile(path.toFile())){
   JsonObject json=new JsonParser().parse(new InputStreamReader(zip.getInputStream(zip.getEntry("structure.json")),"UTF-8")).getAsJsonObject();
   List<MazeGeneration> gens=new ArrayList<>();
   if(json.has("generationInfos"))for(JsonElement e:json.getAsJsonArray("generationInfos")){
    JsonObject g=e.getAsJsonObject();if("mazeComponent".equals(g.get("type").getAsString()))
     gens.add(MazeGeneration.getGson().fromJson(g.get("generationInfo"),MazeGeneration.class));
   }
   if(gens.isEmpty())continue;
   GenericStructure st=new GenericStructure();st.generationTypes.addAll(gens);
   st.rotatable=json.get("rotatable").getAsBoolean();st.mirrorable=json.get("mirrorable").getAsBoolean();
   if(json.has("variableDomain"))st.variableDomain=VARS.fromJson(json.get("variableDomain"),GenericVariableDomain.class);
   st.worldDataCompound=worldData(zip);
   String id=path.getFileName().toString().replace(".rcst","");
   StructureRegistry.INSTANCE.register(id,"reccomplex",st,true,LeveledRegistry.Level.INTERNAL);
   registered++;
  }
  String structure=args.length>0?args[0]:"BigPyramid";long seed=args.length>1?Long.parseLong(args[1]):0;int count=args.length>2?Integer.parseInt(args[2]):5;
  int rotation=args.length>3?Integer.parseInt(args[3]):0;
  Path target=paths.stream().filter(p->p.getFileName().toString().equals(structure+".rcst")).findFirst().get();
  List<NBTTagCompound> definitions=new ArrayList<>();try(ZipFile z=new ZipFile(target.toFile())){scripts(worldData(z),definitions);}
  WorldServer world=mock(WorldServer.class);Environment env=new Environment(world,null,null,null);
  System.out.println("META java="+System.getProperty("java.version")+" arch="+System.getProperty("os.arch")+" registered="+registered+" active="+StructureRegistry.INSTANCE.allActive().size()+" scripts="+definitions.size()+" timeoutMs="+RCConfig.mazeTimeout+" reversesPerRoom="+RCConfig.mazePlacementReversesPerRoom+" rotation="+rotation+" queue="+RCConfig.mazeLayoutQueue);
  System.out.println("CODE solver="+MazeComponentConnector.class.getProtectionDomain().getCodeSource().getLocation()+" caller="+WorldScriptMazeGenerator.class.getProtectionDomain().getCodeSource().getLocation());
  Set<List<?>> consumed=Collections.newSetFromMap(new IdentityHashMap<>());
  for(int sample=0;sample<count;sample++)for(NBTTagCompound def:definitions){
   if(RCConfig.mazeLayoutQueue && sample>0 && sample<4) awaitReady(1);
   long hitsBefore=FreshMazeLayouts.statistics()[0];
   WorldScriptMazeGenerator script=new WorldScriptMazeGenerator();script.readFromNBT(def);
   System.out.println("BEGIN structure="+structure+" sample="+sample+" seed="+(seed+sample)+" maze="+script.mazeID+" rooms="+script.mazeComponent.rooms.compile(true).size()+" componentDefinitions="+MazeGeneration.structures(StructureRegistry.INSTANCE,script.mazeID).count());System.out.flush();
   long start=System.nanoTime();
   try{
    List<PlacedMazeComponent<MazeComponentStructure<Connector>,Connector>> result=script.getPlacedRooms(new Random(seed+sample),AxisAlignedTransform2D.from(rotation,false),env);
    double ms=(System.nanoTime()-start)/1e6;
    if(!consumed.add(result))throw new AssertionError("Reused consumed layout list");
    long rooms=result.stream().mapToLong(c->c.component().rooms().size()).sum();
    System.out.printf(Locale.ROOT,"RESULT structure=%s sample=%d seed=%d elapsedMs=%.3f components=%d occupiedRooms=%d status=success queueHit=%s stats=%s%n",structure,sample,seed+sample,ms,result.size(),rooms,FreshMazeLayouts.statistics()[0]>hitsBefore,Arrays.toString(FreshMazeLayouts.statistics()));
   }catch(WorldScriptMazeGenerator.GenerationException ex){
    System.out.printf(Locale.ROOT,"RESULT structure=%s sample=%d seed=%d elapsedMs=%.3f status=failed message=%s%n",structure,sample,seed+sample,(System.nanoTime()-start)/1e6,ex.getMessage());
    System.exit(2);
   }
  }
  FreshMazeLayouts.clear();verifyZeroInteractions(world);System.out.println("VERIFIED no world method calls and no consumed list reused; excludes room preparation, block placement and chunk generation.");System.exit(0);
 }
 static void awaitReady(int count) throws Exception {
  long start=System.nanoTime();
  while(FreshMazeLayouts.statistics()[5]<count){
   if((System.nanoTime()-start)>java.util.concurrent.TimeUnit.SECONDS.toNanos(45))
    throw new AssertionError("Background queue did not fill: "+Arrays.toString(FreshMazeLayouts.statistics()));
   Thread.sleep(10);
  }
  System.out.printf(Locale.ROOT,"IDLE waitForReadyMs=%.3f stats=%s%n",(System.nanoTime()-start)/1e6,Arrays.toString(FreshMazeLayouts.statistics()));
 }
}
