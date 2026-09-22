package com.charles445.rltweaker.handler;

import java.util.UUID;

/** Test-only reflective caller; this is not the RLTweaker implementation. */
public final class DiagnosticCallerFixture
{
    public static Object generate(Object generator) throws Exception
    {
        return generator.getClass().getMethod("generate").invoke(generator);
    }

    public static void cleanup(Object data, UUID id) throws Exception
    {
        data.getClass().getMethod("removeEntry", UUID.class).invoke(data, id);
    }
}
