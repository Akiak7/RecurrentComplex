package ivorius.reccomplex.world.gen.feature.structure.generic.transformers;

import gnu.trove.map.TObjectDoubleMap;
import gnu.trove.map.hash.TObjectDoubleHashMap;
import ivorius.ivtoolkit.blocks.BlockArea;
import ivorius.ivtoolkit.math.AxisAlignedTransform2D;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;

import java.util.*;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import java.lang.management.ManagementFactory;

/** Manual benchmark, not a timing assertion or part of the normal test run. */
public class NaturalAirBenchmark
{
    private static volatile long sink;
    private static final com.sun.management.ThreadMXBean ALLOCATION = allocationBean();

    private static com.sun.management.ThreadMXBean allocationBean()
    {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean)) return null;
        com.sun.management.ThreadMXBean allocation = (com.sun.management.ThreadMXBean) bean;
        if (!allocation.isThreadAllocatedMemorySupported()) return null;
        allocation.setThreadAllocatedMemoryEnabled(true);
        return allocation;
    }

    private static long allocated() { return ALLOCATION == null ? -1 : ALLOCATION.getThreadAllocatedBytes(Thread.currentThread().getId()); }

    static HashSet<BlockPos> legacyCandidates(TObjectDoubleMap<BlockPos> cloud, AxisAlignedTransform2D transform, int[] size, BlockPos origin)
    {
        return cloud.keySet().stream()
                .flatMap(pos -> new BlockArea(pos.subtract(new Vec3i(2, 2, 2)), pos.add(new Vec3i(2, 2, 2))).stream())
                .filter(pos -> !cloud.containsKey(pos))
                .map(pos -> transform.apply(pos, size).add(origin))
                .collect(Collectors.toCollection(HashSet::new));
    }

    static HashSet<BlockPos> loopCandidates(TObjectDoubleMap<BlockPos> cloud, AxisAlignedTransform2D transform, int[] size, BlockPos origin)
    {
        HashSet<BlockPos> result = new HashSet<>();
        for (BlockPos center : cloud.keySet())
            for (BlockPos pos : new BlockArea(center.add(-2, -2, -2), center.add(2, 2, 2)))
                if (!cloud.containsKey(pos)) result.add(transform.apply(pos, size).add(origin));
        return result;
    }

    static TObjectDoubleMap<BlockPos> cloud(int width, boolean sparse)
    {
        TObjectDoubleMap<BlockPos> cloud = new TObjectDoubleHashMap<>();
        for (int x = 0; x < width; x++)
            for (int y = 0; y < width; y++)
                for (int z = 0; z < width; z++)
                    if (!sparse || (x + y + z) % 5 == 0) cloud.put(new BlockPos(x, y, z), 1);
        return cloud;
    }

    private static void measure(String name, Runnable old, Runnable candidate)
    {
        for (int i = 0; i < 5; i++) { old.run(); candidate.run(); }
        long[] a = new long[15], b = new long[15];
        for (int i = 0; i < 15; i++)
        {
            // Alternate order to reduce consistent first/second-run bias.
            if (i % 2 == 0) { a[i] = time(old); b[i] = time(candidate); }
            else { b[i] = time(candidate); a[i] = time(old); }
        }
        Arrays.sort(a); Arrays.sort(b);
        long start = allocated(); old.run(); long oldBytes = allocated() - start;
        start = allocated(); candidate.run(); long candidateBytes = allocated() - start;
        System.out.printf(Locale.ROOT, "%s oldMedianMs=%.3f candidateMedianMs=%.3f ratio=%.2f oldBytes=%d candidateBytes=%d%n",
                name, a[7]/1e6, b[7]/1e6, (double)a[7]/b[7], ALLOCATION == null ? -1 : oldBytes, ALLOCATION == null ? -1 : candidateBytes);
    }

    private static long time(Runnable run) { long start = System.nanoTime(); run.run(); return System.nanoTime() - start; }

    public static void main(String[] args)
    {
        System.out.println("java=" + System.getProperty("java.version") + " vm=" + System.getProperty("java.vm.name")
                + " arch=" + System.getProperty("os.arch") + " warmups=5 samples=15 diagnostics=not initialized");
        for (int grown : new int[]{0, 300, 8192})
        {
            HashSet<Integer> old = new HashSet<>(), candidate = new HashSet<>();
            for (int i = 0; i < grown; i++) { old.add(i); candidate.add(i); }
            old.clear(); candidate.clear();
            BiPredicate<Set<Integer>, Integer> terminal = (next, pos) -> { sink += pos; return true; };
            measure("terminalRoots=20000 previousSetSize=" + grown,
                    () -> { for (int i = 0; i < 20000; i++) { old.add(i); TransformerAbstractCloud.visitRecursively(old, terminal); old.clear(); } },
                    () -> { for (int i = 0; i < 20000; i++) { TransformerNaturalAir.visitFromSingleRoot(i, candidate, terminal); candidate.clear(); } });
        }
        for (int width : new int[]{4, 24}) for (boolean sparse : new boolean[]{false, true})
        {
            TObjectDoubleMap<BlockPos> cloud = cloud(width, sparse);
            int[] size = {width, width, width};
            BlockPos origin = new BlockPos(-70, 88, 502);
            measure("collection width=" + width + " sparse=" + sparse + " cloud=" + cloud.size() + " enumerated=" + cloud.size()*125L,
                    () -> sink += legacyCandidates(cloud, AxisAlignedTransform2D.R0, size, origin).size(),
                    () -> sink += loopCandidates(cloud, AxisAlignedTransform2D.R0, size, origin).size());
            for (boolean trees : new boolean[]{false, true})
            {
                long[] oldCounts = process(cloud, size, origin, false, trees);
                long[] newCounts = process(cloud, size, origin, true, trees);
                if (!Arrays.equals(oldCounts, newCounts)) throw new AssertionError("Operation counts/order differ");
                System.out.println("operations width="+width+" sparse="+sparse+" trees="+trees+" [candidates,visits,scheduledRemoval,orderHash]="+Arrays.toString(oldCounts));
                measure("threePartialPasses width="+width+" sparse="+sparse+" trees="+trees,
                        () -> sink += process(cloud, size, origin, false, trees)[1],
                        () -> sink += process(cloud, size, origin, true, trees)[1]);
            }
        }
        System.out.println("sink=" + sink);
    }

    // Pure in-memory foliage model: writes fall outside the active clip, so each partial pass sees the same terrain.
    // Uses unchanged candidate collection and real neighbour/traversal implementations; no Mockito timing overhead.
    private static long[] process(TObjectDoubleMap<BlockPos> cloud, int[] size, BlockPos origin, boolean optimized, boolean trees)
    {
        long[] counts = new long[4];
        for (int pass = 0; pass < 3; pass++)
        {
            HashSet<BlockPos> candidates = legacyCandidates(cloud, AxisAlignedTransform2D.R0, size, origin);
            BlockPos treeOrigin = candidates.iterator().next();
            counts[0] += candidates.size();
            HashSet<BlockPos> start = new HashSet<>(), remove = new HashSet<>();
            BiPredicate<Set<BlockPos>, BlockPos> visitor = (next, pos) -> {
                counts[1]++; counts[3] = counts[3]*31 + pos.hashCode();
                int x = pos.getX()-treeOrigin.getX(), y = pos.getY()-treeOrigin.getY(), z = pos.getZ()-treeOrigin.getZ();
                if (trees && x >= 0 && x < 5 && y >= 0 && y < 12 && z >= 0 && z < 5
                        && remove.size() < 300 && remove.add(pos)) TransformerNaturalAir.neighbors(pos).forEach(next::add);
                return true;
            };
            for (BlockPos root : candidates)
            {
                if (optimized) TransformerNaturalAir.visitFromSingleRoot(root, start, visitor);
                else { start.add(root); TransformerAbstractCloud.visitRecursively(start, visitor); }
                counts[2] += remove.size();
                start.clear(); remove.clear();
            }
        }
        return counts;
    }
}
