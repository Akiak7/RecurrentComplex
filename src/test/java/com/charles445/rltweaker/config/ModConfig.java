package com.charles445.rltweaker.config;

/** Test-only public configuration shape; this is not the RLTweaker implementation. */
public final class ModConfig
{
    public static final Server server = new Server();
    public static final class Server { public final Recurrent recurrentcomplex = new Recurrent(); }
    public static final class Recurrent
    {
        public boolean enabled = true;
        public boolean manageRCEvents = true;
        public boolean generatePartially = false;
        public boolean cleanStructureData = true;
        public int cleanStructureThreshold = 100;
    }
}
