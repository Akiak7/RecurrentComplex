package ivorius.reccomplex.utils.presets;

import com.google.gson.GsonBuilder;
import ivorius.reccomplex.RecurrentComplex;
import ivorius.reccomplex.files.loading.LeveledRegistry;
import org.apache.logging.log4j.Logger;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.concurrent.atomic.AtomicInteger;

public class PresettedObjectTest
{
    @Test
    public void logsMissingPresetOnlyOnce()
    {
        AtomicInteger warns = new AtomicInteger();
        Logger previous = RecurrentComplex.logger;
        Logger countingLogger = countingLogger(warns);
        RecurrentComplex.logger = countingLogger;

        try
        {
            TestPresetRegistry registry = new TestPresetRegistry();
            registry.addPreset("default", "fallback");
            registry.setDefault("default");

            PresettedObject<String> presettedObject = new PresettedObject<>(registry, "missing");
            presettedObject.getContents();
            presettedObject.getContents();

            PresettedObject<String> second = new PresettedObject<>(registry, "missing");
            second.getContents();

            Assert.assertEquals("Only one warning should be emitted for the missing preset", 1, warns.get());
            Assert.assertEquals("Fallback contents should be provided", "fallback", presettedObject.getContents());
        }
        finally
        {
            RecurrentComplex.logger = previous;
        }
    }

    private static Logger countingLogger(AtomicInteger warns)
    {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().equals("warn"))
                warns.incrementAndGet();

            Class<?> returnType = method.getReturnType();
            if (returnType.equals(Void.TYPE))
                return null;
            if (returnType.equals(Boolean.TYPE))
                return true;
            if (returnType.equals(Character.TYPE))
                return '\0';
            if (returnType.isPrimitive())
            {
                if (returnType.equals(Byte.TYPE))
                    return (byte) 0;
                if (returnType.equals(Short.TYPE))
                    return (short) 0;
                if (returnType.equals(Integer.TYPE))
                    return 0;
                if (returnType.equals(Long.TYPE))
                    return 0L;
                if (returnType.equals(Float.TYPE))
                    return 0f;
                if (returnType.equals(Double.TYPE))
                    return 0d;
            }
            if (returnType.isInstance(proxy))
                return proxy;
            return null;
        };

        return (Logger) Proxy.newProxyInstance(Logger.class.getClassLoader(), new Class[]{Logger.class}, handler);
    }

    private static class TestPresetRegistry extends PresetRegistry<String>
    {
        protected TestPresetRegistry()
        {
            super("test", "Test Presets");
        }

        void addPreset(String id, String value)
        {
            getRegistry().register(id, "test", PresetRegistry.fullPreset(id, value, null), true, LeveledRegistry.Level.INTERNAL);
        }

        @Override
        protected void registerGson(GsonBuilder builder)
        {
        }

        @Override
        protected Type getType()
        {
            return String.class;
        }
    }
}
