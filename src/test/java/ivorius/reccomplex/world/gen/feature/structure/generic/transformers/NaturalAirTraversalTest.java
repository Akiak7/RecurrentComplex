package ivorius.reccomplex.world.gen.feature.structure.generic.transformers;

import org.junit.Test;

import java.util.*;
import java.util.function.BiPredicate;

import static org.junit.Assert.*;

public class NaturalAirTraversalTest
{
    @Test public void singletonAndExpandedWavesKeepOrderedCallbacksAcrossReusedTables()
    {
        for (int capacity : new int[]{0, 300, 8192})
        {
            HashSet<Integer> old = new HashSet<>(), actual = new HashSet<>();
            for (int i = 0; i < capacity; i++) { old.add(i); actual.add(i); }
            old.clear(); actual.clear();
            for (int trial = 0; trial < 60; trial++)
            {
                final int seed = trial;
                List<Integer> expectedVisits = new ArrayList<>(), actualVisits = new ArrayList<>();
                int root = trial * 73;
                old.add(root);
                boolean expected = TransformerAbstractCloud.visitRecursively(old, visitor(seed, expectedVisits));
                boolean result = TransformerNaturalAir.visitFromSingleRoot(root, actual, visitor(seed, actualVisits));
                assertEquals(expected, result);
                assertEquals("capacity=" + capacity + " trial=" + trial, expectedVisits, actualVisits);
                old.clear(); actual.clear();
            }
        }
    }

    private BiPredicate<Set<Integer>, Integer> visitor(int seed, List<Integer> visited)
    {
        Set<Integer> expanded = new HashSet<>();
        return (next, pos) -> {
            visited.add(pos);
            if (seed % 4 == 0) return false; // terminal obstacle
            if (seed % 4 == 1) return true; // terminal replaceable block
            if (seed % 4 == 2 && visited.size() == 87) return false; // partial wave abort
            if (expanded.size() < 300 && expanded.add(pos))
            {
                next.add(pos); // Natural Air's neighbours include the centre
                next.add(pos + 1); next.add(pos - 1);
                next.add(pos + 31); next.add(pos - 31);
            }
            return true;
        };
    }
}
